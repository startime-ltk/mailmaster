package com.mailmaster.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地邮件缓存（SQLite）。
 *
 * 作用：把「已接收的邮件」持久化到本地，做到——
 *  1. 断网/弱网也能回看历史邮件（离线优先）；
 *  2. 列表秒开，不再每次等待 IMAP 全程拉取；
 *  4. 增量同步：sync_state 表记录每个账户的 UID 水位（UIDVALIDITY + min/max_uid），
 *     拉取时只取 UID 大于水位的「新邮件」，不再每次重拉全邮箱。
 *
 * 存储约定（影响「覆盖安装 / 更新」时数据是否保留，勿随意改动）：
 *  - 数据库名固定为 "mailmaster_cache.db"，随应用私有目录存放；
 *  - 同包名、同签名覆盖安装时默认保留，卸载才会清空；
 *  - v3 起主键采用 (account_id, msg_id)，其中 msg_id 即 MailMessage.id（形如 accountId-uid），
 *    以 IMAP UID 为锚点，全局唯一、跨会话稳定，天然去重。
 *    v2 及以前的旧缓存用服务器序号（accountId-msgNum）作 id，序号会随新邮件到达整体位移，
 *    升级 v3 时整体作废清空（见 onUpgrade），首次打开会重新拉取。
 */
public class MailCache extends SQLiteOpenHelper {

    private static final String DB_NAME = "mailmaster_cache.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "messages";
    private static final String TABLE_SYNC = "sync_state";

    // 单列引用，避免 Activity 泄漏
    private Context ctx;

    private static MailCache INSTANCE;

    /** 应用级单例 */
    public static synchronized MailCache getInstance(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new MailCache(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    private MailCache(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            " _id INTEGER PRIMARY KEY AUTOINCREMENT," +
            " account_id TEXT NOT NULL," +
            " msg_id TEXT NOT NULL," +
            " account_email TEXT," +
            " frm TEXT," +          // 发件人（含「显示名 <addr>」原样）
            " to_addr TEXT," +
            " subject TEXT," +
            " body TEXT," +
            " html TEXT," +
            " date INTEGER," +
            " unread INTEGER," +
            " category TEXT," +
            " attachments TEXT," +  // Attachment 列表的 JSON 序列化
            " msg_num INTEGER," +   // 旧序号体系遗留列，v3 起不再使用（恒为 -1）
            " uid INTEGER NOT NULL DEFAULT -1," + // IMAP UID：增量同步与已读回写的锚点
            " deleted INTEGER NOT NULL DEFAULT 0," +   // 回收站：0 正常 / 1 已删除
            " deleted_at INTEGER NOT NULL DEFAULT 0," + // 删除时间戳（回收站排序用）
            " UNIQUE(account_id, msg_id))"
        );
        db.execSQL(
            "CREATE TABLE " + TABLE_SYNC + " (" +
            " account_id TEXT PRIMARY KEY," +
            " uid_validity INTEGER NOT NULL," + // 服务器 UIDVALIDITY：变了说明邮箱被重建，需全量重拉
            " min_uid INTEGER NOT NULL," +     // 本地已缓存的最小 UID（向前翻页的起点）
            " max_uid INTEGER NOT NULL," +     // 本地已缓存的最大 UID（增量拉取的起点）
            " has_older INTEGER NOT NULL DEFAULT 1," + // 是否还有更早的邮件未拉取
            " updated_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // v2 回收站：软删除标记与删除时间。ALTER 追加列，既有邮件数据与缓存全部保留
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldV < 3) {
            // v3 UID 体系：旧缓存以服务器序号（会位移）为 id 与主键，与新体系不兼容，
            // 保留会造成同一封邮件以新旧两套 id 重复出现。整体作废：先删附件文件再清表，
            // 首次打开后按 UID 重新拉取（首次窗口 100 封）。
            Cursor c = db.query(TABLE, new String[]{"attachments"}, null, null, null, null, null);
            try {
                while (c.moveToNext()) deleteAttachmentFiles(c.getString(0));
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            db.delete(TABLE, null, null);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SYNC);
            db.execSQL(
                "CREATE TABLE " + TABLE_SYNC + " (" +
                " account_id TEXT PRIMARY KEY," +
                " uid_validity INTEGER NOT NULL," +
                " min_uid INTEGER NOT NULL," +
                " max_uid INTEGER NOT NULL," +
                " has_older INTEGER NOT NULL DEFAULT 1," +
                " updated_at INTEGER NOT NULL)"
            );
        }
    }

    /* ------------------------- 增量同步水位（sync_state） ------------------------- */

    /** 某账户的同步水位；无记录返回 null（从未拉取过） */
    public static class SyncState {
        public long uidValidity;
        public long minUid;
        public long maxUid;
        public boolean hasOlder;
    }

    /**
     * 写入/合并同步水位。min/max 与库中现值取并集（min 取更小、max 取更大），
     * 保证并发或分批拉取时水位只进不退。
     *
     * @param hasOlder -1 保持原值 / 0 明确没有更早邮件 / 1 明确还有
     */
    public void saveSyncState(String accountId, long uidValidity, long uid, int hasOlder) {
        if (accountId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long min = uid, max = uid;
            int older = (hasOlder < 0) ? 1 : hasOlder;
            Cursor c = db.query(TABLE_SYNC,
                    new String[]{"min_uid", "max_uid", "has_older"},
                    "account_id=?", new String[]{accountId}, null, null, null);
            try {
                if (c.moveToFirst()) {
                    long oldMin = c.getLong(0);
                    long oldMax = c.getLong(1);
                    if (oldMin < min) min = oldMin;
                    if (oldMax > max) max = oldMax;
                    if (hasOlder < 0) older = c.getInt(2);
                }
            } finally {
                if (c != null) c.close();
            }
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("account_id", accountId);
            v.put("uid_validity", uidValidity);
            v.put("min_uid", min);
            v.put("max_uid", max);
            v.put("has_older", older);
            v.put("updated_at", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_SYNC, null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 无条件重置同步水位（UIDVALIDITY 变化 / 邮箱被服务器重建时用）。
     * 与 {@link #saveSyncState} 的合并语义不同：这里直接覆盖，
     * 否则旧邮箱世代的 min/max 会污染新世代的水位，导致漏拉或重复拉。
     */
    public void resetSyncState(String accountId, long uidValidity,
                               long minUid, long maxUid, boolean hasOlder) {
        if (accountId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("account_id", accountId);
        v.put("uid_validity", uidValidity);
        v.put("min_uid", minUid);
        v.put("max_uid", maxUid);
        v.put("has_older", hasOlder ? 1 : 0);
        v.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_SYNC, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public SyncState getSyncState(String accountId) {
        if (accountId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE_SYNC, null, "account_id=?",
                    new String[]{accountId}, null, null, null);
            if (c.moveToFirst()) {
                SyncState st = new SyncState();
                st.uidValidity = c.getLong(c.getColumnIndexOrThrow("uid_validity"));
                st.minUid = c.getLong(c.getColumnIndexOrThrow("min_uid"));
                st.maxUid = c.getLong(c.getColumnIndexOrThrow("max_uid"));
                st.hasOlder = c.getInt(c.getColumnIndexOrThrow("has_older")) != 0;
                return st;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 是否还有更早的邮件可翻页加载（任一账户有即显示加载入口） */
    public boolean hasOlderMail() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_SYNC + " WHERE has_older=1", null);
            return c.moveToFirst() && c.getInt(0) > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 批量写入（存在则覆盖，按 account_id+msg_id 去重）。事务提交，避免逐条 IO。
     *
     * 防空覆盖（治「只有标题没内容」的固化）：若本次抓取 body/html 均为空
     * （抓取失败或解析异常导致的空结果），而库里已有非空内容，
     * 则保留旧记录，不把一次失败固化成永久空白。
     */
    public void upsertAll(List<MailMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (MailMessage m : msgs) {
                if (m == null || m.id == null) continue;
                // 回收站里的邮件不参与服务器同步：否则下次拉取会把用户删掉的邮件"复活"
                if (isDeletedInDb(db, m.accountId, m.id)) continue;
                if (isEmptyContent(m) && hasContent(db, m.accountId, m.id)) {
                    // 本次没抓到内容但历史有：保留历史，防止永久空白
                    continue;
                }
                db.insertWithOnConflict(TABLE, null, toValues(m), SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** body 与 html 是否都为空 */
    private boolean isEmptyContent(MailMessage m) {
        boolean bodyEmpty = m.body == null || m.body.trim().length() == 0;
        boolean htmlEmpty = m.html == null || m.html.trim().length() == 0;
        return bodyEmpty && htmlEmpty;
    }

    /** 库里是否已存有非空正文 */
    private boolean hasContent(SQLiteDatabase db, String accountId, String msgId) {
        if (accountId == null || msgId == null) return false;
        Cursor c = null;
        try {
            c = db.query(TABLE, new String[]{"body", "html"}, "account_id=? AND msg_id=?",
                    new String[]{accountId, msgId}, null, null, null);
            if (c.moveToFirst()) {
                String body = c.getString(0);
                String html = c.getString(1);
                return (body != null && body.trim().length() > 0)
                        || (html != null && html.trim().length() > 0);
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 取出全部正常邮件（不含回收站），按时间倒序（最新在前）。 */
    public List<MailMessage> getAll() {
        List<MailMessage> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, "deleted=0", null, null, null, "date DESC");
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 删除某账户的全部缓存邮件，并清理其附件目录与同步水位。 */
    public void deleteAccount(String accountId) {
        if (accountId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, "account_id=?", new String[]{accountId});
        db.delete(TABLE_SYNC, "account_id=?", new String[]{accountId});
        // 级联清理落盘的附件文件（防止隐私残留与空间浪费）
        MailService.deleteAccountFiles(ctx, accountId);
    }

    /** 已读回写时同步本地缓存的未读标记（按 IMAP UID 定位）。 */
    public void markRead(String accountId, long uid) {
        if (accountId == null || uid <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("unread", 0);
        db.update(TABLE, v, "account_id=? AND uid=?",
                new String[]{accountId, String.valueOf(uid)});
    }

    // ---------- 回收站（软删除） ----------

    /** 回收站列表：已软删除的邮件，按删除时间倒序（最新删的在前）。 */
    public List<MailMessage> getTrash() {
        List<MailMessage> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, null, "deleted=1", null, null, null, "deleted_at DESC");
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 回收站邮件数（菜单上显示角标）。 */
    public int countTrash() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE deleted=1", null);
            return (c.moveToFirst()) ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 软删除：移入回收站。只打标记，本地正文与附件原样保留，服务器不动——
     * 误删可完整恢复，这是回收站的意义所在。
     */
    public void softDelete(String accountId, String msgId) {
        if (accountId == null || msgId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("deleted", 1);
        v.put("deleted_at", System.currentTimeMillis());
        db.update(TABLE, v, "account_id=? AND msg_id=?", new String[]{accountId, msgId});
    }

    /** 从回收站恢复到收件箱。 */
    public void restore(String accountId, String msgId) {
        if (accountId == null || msgId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("deleted", 0);
        v.put("deleted_at", 0);
        db.update(TABLE, v, "account_id=? AND msg_id=?", new String[]{accountId, msgId});
    }

    /** 彻底删除一封：先删其落盘附件文件，再删数据库行。不可恢复。 */
    public void purge(String accountId, String msgId) {
        if (accountId == null || msgId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, new String[]{"attachments"}, "account_id=? AND msg_id=?",
                    new String[]{accountId, msgId}, null, null, null);
            if (c.moveToFirst()) deleteAttachmentFiles(c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        db.delete(TABLE, "account_id=? AND msg_id=?", new String[]{accountId, msgId});
    }

    /** 清空回收站：删除全部已删邮件的附件文件与数据行。不可恢复。 */
    public void purgeAll() {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        try {
            c = db.query(TABLE, new String[]{"attachments"}, "deleted=1", null, null, null, null);
            while (c.moveToNext()) deleteAttachmentFiles(c.getString(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        db.delete(TABLE, "deleted=1", null);
    }

    /** 按 attachments JSON 里记录的路径删除落盘附件文件 */
    private void deleteAttachmentFiles(String json) {
        if (json == null || json.isEmpty()) return;
        for (Attachment a : jsonToAttachments(json)) {
            if (a == null || a.path == null || a.path.isEmpty()) continue;
            try {
                new File(a.path).delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 库中该邮件是否已在回收站（upsertAll 用它防止服务器同步把删除的邮件复活） */
    private boolean isDeletedInDb(SQLiteDatabase db, String accountId, String msgId) {
        if (accountId == null || msgId == null) return false;
        Cursor c = null;
        try {
            c = db.query(TABLE, new String[]{"deleted"}, "account_id=? AND msg_id=?",
                    new String[]{accountId, msgId}, null, null, null);
            return c.moveToFirst() && c.getInt(0) == 1;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 清空所有本地缓存（谨慎调用）。 */
    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, null, null);
        db.delete(TABLE_SYNC, null, null);
    }

    private android.content.ContentValues toValues(MailMessage m) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("account_id", m.accountId);
        v.put("msg_id", m.id);
        v.put("account_email", m.accountEmail);
        v.put("frm", m.from);
        v.put("to_addr", m.to);
        v.put("subject", m.subject);
        v.put("body", m.body);
        v.put("html", m.html);
        v.put("date", m.date);
        v.put("unread", m.unread ? 1 : 0);
        v.put("category", m.category);
        v.put("attachments", attachmentsToJson(m.attachments));
        v.put("msg_num", -1);   // 旧序号体系作废，恒写 -1
        v.put("uid", m.uid);
        return v;
    }

    private MailMessage fromCursor(Cursor c) {
        MailMessage m = new MailMessage();
        m.accountId = c.getString(c.getColumnIndexOrThrow("account_id"));
        m.id = c.getString(c.getColumnIndexOrThrow("msg_id"));
        m.accountEmail = c.getString(c.getColumnIndexOrThrow("account_email"));
        m.from = c.getString(c.getColumnIndexOrThrow("frm"));
        m.to = c.getString(c.getColumnIndexOrThrow("to_addr"));
        m.subject = c.getString(c.getColumnIndexOrThrow("subject"));
        m.body = c.getString(c.getColumnIndexOrThrow("body"));
        m.html = c.getString(c.getColumnIndexOrThrow("html"));
        m.date = c.getLong(c.getColumnIndexOrThrow("date"));
        m.unread = c.getInt(c.getColumnIndexOrThrow("unread")) != 0;
        m.category = c.getString(c.getColumnIndexOrThrow("category"));
        m.attachments = jsonToAttachments(c.getString(c.getColumnIndexOrThrow("attachments")));
        m.msgNum = -1; // 旧序号体系作废
        m.uid = c.getLong(c.getColumnIndexOrThrow("uid"));
        return m;
    }

    private String attachmentsToJson(List<Attachment> list) {
        if (list == null) return "";
        JSONArray a = new JSONArray();
        for (Attachment at : list) {
            if (at == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", at.name == null ? "" : at.name);
                o.put("path", at.path == null ? "" : at.path);
                o.put("mime", at.mime == null ? "" : at.mime);
                o.put("size", at.size);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a.toString();
    }

    private List<Attachment> jsonToAttachments(String s) {
        List<Attachment> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Attachment at = new Attachment();
                at.name = o.optString("name", "");
                at.path = o.optString("path", "");
                at.mime = o.optString("mime", "");
                at.size = o.optLong("size", 0);
                out.add(at);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
