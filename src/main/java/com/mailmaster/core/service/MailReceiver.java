package com.mailmaster.core.service;

import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;

import javax.mail.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IMAP 收信（UID 体系，v1.20 起与安卓端 MailService 同构）。
 *
 * 演进：旧版用「服务器序号」取最近 N 封——序号会随新邮件到达整体位移，
 * 同一封邮件在本地缓存里身份不稳，也无法增量。现全部改用 IMAP UID：
 *  - fetchWindow：首次/UIDVALIDITY 变化时拉最近 limit 封（默认 100）；
 *  - fetchNewer：只拉 UID 大于 afterUid 的新邮件（日常增量，零冗余）；
 *  - fetchRange：按 UID 区间拉取（向前翻页「加载更早的邮件」）；
 *  - fetchByUid：单封重拉（治「只有标题没内容」）；
 *  - markRead：按 UID 回写已读。
 *
 * 每条命令路径都带连接/读取超时（与安卓端一致），防弱网挂死。
 */
public class MailReceiver {

    /** 首次同步 / 全量重拉的窗口大小 */
    public static final int FIRST_WINDOW = 100;

    /** 连接/读取超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** 一次拉取的结果：邮件 + 水位信息（由调用方写 sync_state） */
    public static class FetchResult {
        public final List<EmailMessage> messages = new ArrayList<>();
        public long uidValidity;
        public long minUid;
        public long maxUid;
        public boolean hasOlder;
    }

    /** 全量窗口：最近 limit 封（按序号取窗口，再按 UID 记录水位） */
    public FetchResult fetchWindow(EmailAccount account, String password, int limit) throws Exception {
        Store store = connect(account, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uf = (UIDFolder) inbox;
            FetchResult r = new FetchResult();
            r.uidValidity = uf.getUIDValidity();
            int total = inbox.getMessageCount();
            int start = Math.max(0, total - limit);
            if (total > 0) {
                for (Message m : inbox.getMessages(start + 1, total)) {
                    addMessage(r, account, m, uf);
                }
            }
            r.hasOlder = total > limit;
            return r;
        } finally {
            close(store);
        }
    }

    /** 增量：UID 在 (afterUid, LASTUID] 的新邮件 */
    public FetchResult fetchNewer(EmailAccount account, String password, long afterUid) throws Exception {
        return fetchRange(account, password, afterUid + 1, UIDFolder.LASTUID);
    }

    /** 按 UID 区间拉取（toUid 传 UIDFolder.LASTUID 表示到最新） */
    public FetchResult fetchRange(EmailAccount account, String password, long fromUid, long toUid)
            throws Exception {
        Store store = connect(account, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uf = (UIDFolder) inbox;
            FetchResult r = new FetchResult();
            r.uidValidity = uf.getUIDValidity();
            for (Message m : uf.getMessagesByUID(fromUid, toUid)) {
                addMessage(r, account, m, uf);
            }
            return r;
        } finally {
            close(store);
        }
    }

    /** 单封重拉（详情页「重新加载正文」用） */
    public EmailMessage fetchByUid(EmailAccount account, String password, long uid) throws Exception {
        Store store = connect(account, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uf = (UIDFolder) inbox;
            Message m = uf.getMessageByUID(uid);
            return m == null ? null : parse(account, m, uid);
        } finally {
            close(store);
        }
    }

    /** 已读状态回写服务器（按 UID 定位） */
    public void markRead(EmailAccount account, String password, long uid) throws Exception {
        if (uid <= 0) return;
        Store store = connect(account, password);
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            Message m = ((UIDFolder) inbox).getMessageByUID(uid);
            if (m != null) m.setFlag(Flags.Flag.SEEN, true);
            inbox.close(true);
        } finally {
            close(store);
        }
    }

    /** 拉取结果里追加一封，并维护 min/max 水位 */
    private void addMessage(FetchResult r, EmailAccount account, Message m, UIDFolder uf)
            throws Exception {
        long uid = uf.getUID(m);
        if (r.minUid == 0 || uid < r.minUid) r.minUid = uid;
        if (uid > r.maxUid) r.maxUid = uid;
        r.messages.add(parse(account, m, uid));
    }

    private EmailMessage parse(EmailAccount account, Message m, long uid) throws Exception {
        EmailMessage em = new EmailMessage();
        em.setAccountId(account.getId());
        em.setAccountEmail(account.getEmail());
        em.setUid(uid);
        em.setId(account.getId() + ":" + uid);   // UID 体系：跨会话稳定的主键
        Address[] from = m.getFrom();
        em.setFrom(from != null && from.length > 0 ? decode(from[0].toString()) : "");
        Address[] to = m.getRecipients(Message.RecipientType.TO);
        em.setTo(to != null && to.length > 0 ? decode(to[0].toString()) : "");
        em.setSubject(m.getSubject() == null ? "" : decode(m.getSubject()));
        em.setSentDate(m.getSentDate());
        em.setRead(m.getFlags().contains(Flags.Flag.SEEN));
        parseBody(m, em);
        return em;
    }

    /** 主题/发件人做 encoded-word 解码（与安卓端一致，防中文变乱码） */
    private String decode(String s) {
        if (s == null) return null;
        try {
            return javax.mail.internet.MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 正文解析（递归 multipart）。
     * 单个 Part 解析失败只跳过该 Part，不再连累整封邮件——
     * 这正是治「只有标题没内容」的关键：标题走头字段永远有值，
     * 旧代码一个 Part 抛异常就把整封正文吞成 null。
     */
    private void parseBody(Part part, EmailMessage em) {
        try {
            if (part.isMimeType("text/plain") && em.getBodyText() == null) {
                em.setBodyText(asText(part));
            } else if (part.isMimeType("text/html") && em.getBodyHtml() == null) {
                em.setBodyHtml(asText(part));
            } else if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    try {
                        parseBody(mp.getBodyPart(i), em);
                    } catch (Exception skip) {
                        // 单个 Part 失败：跳过，保住其它部分与标题
                    }
                }
            }
        } catch (Exception skip) {
            // 顶层 Part 异常：跳过
        }
    }

    private String asText(Part part) {
        try {
            Object c = part.getContent();
            return c == null ? null : c.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Store connect(EmailAccount account, String password) throws Exception {
        Properties props = new Properties();
        props.put("mail.imaps.ssl.enable", "true");
        // 超时保护：连接 8s / 读取 15s，防弱网挂死（与安卓端一致）
        props.put("mail.imaps.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.imaps.timeout", String.valueOf(READ_TIMEOUT_MS));
        Session session = Session.getInstance(props, null);
        Store store = session.getStore("imaps");
        store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(), password);
        return store;
    }

    private void close(Store store) {
        try {
            if (store != null && store.isConnected()) store.close();
        } catch (Exception ignored) {
        }
    }
}
