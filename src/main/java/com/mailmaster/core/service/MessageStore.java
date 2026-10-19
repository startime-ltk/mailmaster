package com.mailmaster.core.service;

import com.mailmaster.core.model.EmailMessage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息持久化（H2 本地文件库）：邮件缓存 + 同步水位 + 草稿箱，三表同库。
 *
 * 与安卓端 MailCache v3 同构的设计（便于两端行为对齐、评审时好讲）：
 *  - messages：以 (account_id, msg_id) 为主键，msg_id = accountId + ":" + UID，
 *    IMAP UID 跨会话稳定，天然去重；deleted 列实现回收站（软删除，可恢复）；
 *  - sync_state：每账户的 UID 水位（uid_validity / min_uid / max_uid / has_older），
 *    是增量同步与向前翻页的唯一事实源；
 *  - drafts：草稿箱（只存本地，不同步服务器）。
 *
 * 三条不可动的保护逻辑（与安卓端一一对应）：
 *  1) 防「回收站复活」：服务器同步 upsert 时跳过本地已软删除的邮件；
 *  2) 防空覆盖：本次抓取正文为空而库里有非空内容时，保留旧记录；
 *  3) UIDVALIDITY 变化走 resetSyncState（重置而非合并），防止两个邮箱世代水位串味。
 */
public class MessageStore {

    private final String dbUrl;

    public MessageStore(String dbPath) {
        this.dbUrl = "jdbc:h2:" + dbPath;
        init();
    }

    private void init() {
        try (Connection c = DriverManager.getConnection(dbUrl);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "account_id VARCHAR(64) NOT NULL," +
                    "msg_id VARCHAR(160) NOT NULL," +
                    "uid BIGINT NOT NULL DEFAULT -1," +
                    "account_email VARCHAR(255)," +
                    "frm VARCHAR(512)," +
                    "to_addr VARCHAR(512)," +
                    "subject VARCHAR(1024)," +
                    "body_text CLOB," +
                    "body_html CLOB," +
                    "sent_date BIGINT," +
                    "read BOOLEAN," +
                    "category VARCHAR(16)," +
                    "deleted BOOLEAN NOT NULL DEFAULT FALSE," +
                    "deleted_at BIGINT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (account_id, msg_id))");
            st.execute("CREATE TABLE IF NOT EXISTS sync_state (" +
                    "account_id VARCHAR(64) PRIMARY KEY," +
                    "uid_validity BIGINT NOT NULL," +
                    "min_uid BIGINT NOT NULL," +
                    "max_uid BIGINT NOT NULL," +
                    "has_older BOOLEAN NOT NULL DEFAULT TRUE," +
                    "updated_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS drafts (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "account_id VARCHAR(64) NOT NULL," +
                    "account_email VARCHAR(255)," +
                    "to_addr VARCHAR(512)," +
                    "subject VARCHAR(1024)," +
                    "body CLOB," +
                    "updated_at BIGINT NOT NULL)");
        } catch (SQLException e) {
            throw new RuntimeException("初始化消息数据库失败", e);
        }
    }

    // ---------------- 邮件缓存 ----------------

    /**
     * 批量写入（存在则覆盖）。单事务提交，带两条保护：
     * 回收站里的邮件不参与同步（防复活）；空正文不覆盖已有非空正文（防空覆盖）。
     */
    public void upsertAll(List<EmailMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        String merge = "MERGE INTO messages (account_id,msg_id,uid,account_email,frm,to_addr,subject," +
                "body_text,body_html,sent_date,read,category,deleted,deleted_at) KEY(account_id,msg_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,FALSE,0)";
        String delCheck = "SELECT deleted FROM messages WHERE account_id=? AND msg_id=?";
        String hasContent = "SELECT body_text FROM messages WHERE account_id=? AND msg_id=?";
        try (Connection c = DriverManager.getConnection(dbUrl)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(merge);
                 PreparedStatement psDel = c.prepareStatement(delCheck);
                 PreparedStatement psHas = c.prepareStatement(hasContent)) {
                for (EmailMessage m : msgs) {
                    if (m == null || m.getId() == null) continue;
                    // 1) 防复活：回收站里的邮件不被服务器同步带回来
                    psDel.setString(1, m.getAccountId());
                    psDel.setString(2, m.getId());
                    try (ResultSet rs = psDel.executeQuery()) {
                        if (rs.next() && rs.getBoolean(1)) continue;
                    }
                    // 2) 防空覆盖：本次没抓到内容但历史有 → 保留历史
                    if (!m.hasContent()) {
                        psHas.setString(1, m.getAccountId());
                        psHas.setString(2, m.getId());
                        try (ResultSet rs = psHas.executeQuery()) {
                            if (rs.next()) {
                                String old = rs.getString(1);
                                if (old != null && !old.trim().isEmpty()) continue;
                            }
                        }
                    }
                    ps.setString(1, m.getAccountId());
                    ps.setString(2, m.getId());
                    ps.setLong(3, m.getUid());
                    ps.setString(4, m.getAccountEmail());
                    ps.setString(5, m.getFrom());
                    ps.setString(6, m.getTo());
                    ps.setString(7, m.getSubject());
                    ps.setString(8, m.getBodyText());
                    ps.setString(9, m.getBodyHtml());
                    ps.setLong(10, m.getSentDate() == null ? 0 : m.getSentDate().getTime());
                    ps.setBoolean(11, m.isRead());
                    ps.setString(12, m.getCategory());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存邮件缓存失败", e);
        }
    }

    /** 收件箱（不含回收站），按时间倒序 */
    public List<EmailMessage> getAll() {
        return query("deleted=FALSE ORDER BY sent_date DESC");
    }

    /** 回收站，按删除时间倒序 */
    public List<EmailMessage> getTrash() {
        return query("deleted=TRUE ORDER BY deleted_at DESC");
    }

    private List<EmailMessage> query(String where) {
        List<EmailMessage> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(dbUrl);
             ResultSet rs = c.createStatement().executeQuery("SELECT * FROM messages WHERE " + where)) {
            while (rs.next()) out.add(fromRow(rs));
        } catch (SQLException e) {
            throw new RuntimeException("读取邮件缓存失败", e);
        }
        return out;
    }

    private EmailMessage fromRow(ResultSet rs) throws SQLException {
        EmailMessage m = new EmailMessage();
        m.setId(rs.getString("msg_id"));
        m.setAccountId(rs.getString("account_id"));
        m.setUid(rs.getLong("uid"));
        m.setAccountEmail(rs.getString("account_email"));
        m.setFrom(rs.getString("frm"));
        m.setTo(rs.getString("to_addr"));
        m.setSubject(rs.getString("subject"));
        m.setBodyText(rs.getString("body_text"));
        m.setBodyHtml(rs.getString("body_html"));
        long t = rs.getLong("sent_date");
        m.setSentDate(t > 0 ? new Date(t) : null);
        m.setRead(rs.getBoolean("read"));
        m.setCategory(rs.getString("category"));
        return m;
    }

    /** 软删除：移入回收站（本地标记，服务器原件不动） */
    public void softDelete(String accountId, String msgId) {
        exec("UPDATE messages SET deleted=TRUE, deleted_at=" + System.currentTimeMillis()
                + " WHERE account_id=? AND msg_id=?", accountId, msgId);
    }

    /** 从回收站恢复 */
    public void restore(String accountId, String msgId) {
        exec("UPDATE messages SET deleted=FALSE, deleted_at=0 WHERE account_id=? AND msg_id=?",
                accountId, msgId);
    }

    /** 彻底删除一封（不可恢复） */
    public void purge(String accountId, String msgId) {
        exec("DELETE FROM messages WHERE account_id=? AND msg_id=?", accountId, msgId);
    }

    /** 清空回收站 */
    public int purgeAll() {
        try (Connection c = DriverManager.getConnection(dbUrl);
             Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM messages WHERE deleted=TRUE");
        } catch (SQLException e) {
            throw new RuntimeException("清空回收站失败", e);
        }
    }

    public int countTrash() {
        return count("SELECT COUNT(*) FROM messages WHERE deleted=TRUE");
    }

    /** 已读回写：按 UID 定位本地缓存行 */
    public void markRead(String accountId, long uid) {
        if (accountId == null || uid <= 0) return;
        exec("UPDATE messages SET read=TRUE WHERE account_id=? AND uid=?", accountId, String.valueOf(uid));
    }

    // ---------------- 同步水位 ----------------

    public static class SyncState {
        public long uidValidity;
        public long minUid;
        public long maxUid;
        public boolean hasOlder;
    }

    /** 合并写水位：min 取更小、max 取更大（只进不退）；hasOlder=-1 保持原值 */
    public void saveSyncState(String accountId, long uidValidity, long uid, int hasOlder) {
        if (accountId == null) return;
        try (Connection c = DriverManager.getConnection(dbUrl)) {
            long min = uid, max = uid;
            boolean older = hasOlder >= 0 ? hasOlder == 1 : true;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT min_uid,max_uid,has_older FROM sync_state WHERE account_id=?")) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        min = Math.min(min, rs.getLong(1));
                        max = Math.max(max, rs.getLong(2));
                        if (hasOlder < 0) older = rs.getBoolean(3);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO sync_state (account_id,uid_validity,min_uid,max_uid,has_older,updated_at) KEY(account_id) " +
                            "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, accountId);
                ps.setLong(2, uidValidity);
                ps.setLong(3, min);
                ps.setLong(4, max);
                ps.setBoolean(5, older);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存同步水位失败", e);
        }
    }

    /** 重置水位（UIDVALIDITY 变化 / 全量重拉后）：直接覆盖，不与旧世代合并 */
    public void resetSyncState(String accountId, long uidValidity,
                               long minUid, long maxUid, boolean hasOlder) {
        if (accountId == null) return;
        try (Connection c = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = c.prepareStatement(
                     "MERGE INTO sync_state (account_id,uid_validity,min_uid,max_uid,has_older,updated_at) KEY(account_id) " +
                             "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, accountId);
            ps.setLong(2, uidValidity);
            ps.setLong(3, minUid);
            ps.setLong(4, maxUid);
            ps.setBoolean(5, hasOlder);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("重置同步水位失败", e);
        }
    }

    public SyncState getSyncState(String accountId) {
        if (accountId == null) return null;
        try (Connection c = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM sync_state WHERE account_id=?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SyncState st = new SyncState();
                    st.uidValidity = rs.getLong("uid_validity");
                    st.minUid = rs.getLong("min_uid");
                    st.maxUid = rs.getLong("max_uid");
                    st.hasOlder = rs.getBoolean("has_older");
                    return st;
                }
                return null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** 是否还有更早的邮件可翻页加载（任一账户有即显示入口） */
    public boolean hasOlderMail() {
        return count("SELECT COUNT(*) FROM sync_state WHERE has_older=TRUE") > 0;
    }

    // ---------------- 草稿箱 ----------------

    /** 新建/更新草稿，返回草稿 id（id<=0 时插入） */
    public long saveDraft(long id, String accountId, String accountEmail,
                          String to, String subject, String body) {
        try (Connection c = DriverManager.getConnection(dbUrl)) {
            if (id > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE drafts SET account_id=?,account_email=?,to_addr=?,subject=?,body=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, accountId);
                    ps.setString(2, accountEmail);
                    ps.setString(3, to);
                    ps.setString(4, subject);
                    ps.setString(5, body);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.setLong(7, id);
                    ps.executeUpdate();
                }
                return id;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO drafts (account_id,account_email,to_addr,subject,body,updated_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, accountId);
                ps.setString(2, accountEmail);
                ps.setString(3, to);
                ps.setString(4, subject);
                ps.setString(5, body);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet ks = ps.getGeneratedKeys()) {
                    return ks.next() ? ks.getLong(1) : -1;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存草稿失败", e);
        }
    }

    /** 草稿行（id, accountId, accountEmail, to, subject, body, updatedAt 七列） */
    public List<String[]> getDrafts() {
        List<String[]> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(dbUrl);
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT * FROM drafts ORDER BY updated_at DESC")) {
            while (rs.next()) {
                out.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("account_id"),
                        rs.getString("account_email"),
                        rs.getString("to_addr"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        String.valueOf(rs.getLong("updated_at"))
                });
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取草稿失败", e);
        }
        return out;
    }

    public void deleteDraft(long id) {
        exec("DELETE FROM drafts WHERE id=?", String.valueOf(id), null);
    }

    public int countDrafts() {
        return count("SELECT COUNT(*) FROM drafts");
    }

    // ---------------- 账户级联清理 ----------------

    /** 删除某账户的邮件缓存、同步水位与草稿 */
    public void deleteAccount(String accountId) {
        if (accountId == null) return;
        exec("DELETE FROM messages WHERE account_id=?", accountId, null);
        exec("DELETE FROM sync_state WHERE account_id=?", accountId, null);
        exec("DELETE FROM drafts WHERE account_id=?", accountId, null);
    }

    // ---------------- 工具 ----------------

    private int count(String sql) {
        try (Connection c = DriverManager.getConnection(dbUrl);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void exec(String sql, String p1, String p2) {
        try (Connection c = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (p1 != null) ps.setString(1, p1);
            if (p2 != null) ps.setString(2, p2);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("执行 SQL 失败: " + sql, e);
        }
    }
}
