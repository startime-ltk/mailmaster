package com.mailmaster.core.model;

import java.util.Date;

/**
 * 邮件消息模型。统一收件箱里展示的就是它。
 * 多端共享，未来 Web / 小程序直接复用此结构。
 */
public class EmailMessage {
    private String id;             // 稳定唯一 id：账户id + ":" + UID（v1.20 起改用 UID，跨会话稳定）
    private String accountId;
    private String accountEmail;
    private String from;
    private String to;
    private String subject;
    private String bodyText;       // 纯文本正文
    private String bodyHtml;       // HTML 正文（用于 WebView 渲染）
    private Date sentDate;
    private boolean read;
    private long uid = -1;         // IMAP UID：增量同步与已读回写的锚点
    private String category;       // 智能分类：重要 / 推广 / 社交 / 其他

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountEmail() { return accountEmail; }
    public void setAccountEmail(String accountEmail) { this.accountEmail = accountEmail; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public Date getSentDate() { return sentDate; }
    public void setSentDate(Date sentDate) { this.sentDate = sentDate; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public long getUid() { return uid; }
    public void setUid(long uid) { this.uid = uid; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /** 正文是否为空（治「只有标题没内容」的防空覆盖判断用） */
    public boolean hasContent() {
        return (bodyText != null && !bodyText.trim().isEmpty())
                || (bodyHtml != null && !bodyHtml.trim().isEmpty());
    }
}
