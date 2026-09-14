package com.mailmaster.core.model;

import java.util.Date;

/**
 * 邮件消息模型。统一收件箱里展示的就是它。
 * 多端共享，未来 Web / 小程序直接复用此结构。
 */
public class EmailMessage {
    private String id;             // 稳定唯一 id：账户id + 接收时间戳
    private String accountId;
    private String accountEmail;
    private String from;
    private String to;
    private String subject;
    private String bodyText;       // 纯文本正文
    private String bodyHtml;       // HTML 正文（用于 WebView 渲染）
    private Date sentDate;
    private boolean read;

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
}
