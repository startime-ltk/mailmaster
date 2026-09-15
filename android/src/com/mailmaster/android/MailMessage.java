package com.mailmaster.android;

/** 邮件模型。 */
public class MailMessage {
    public String id;
    public String accountId;
    public String accountEmail;
    public String from;
    public String to;
    public String subject;
    public String body;
    public long date;       // epoch 毫秒
    public boolean unread;
}
