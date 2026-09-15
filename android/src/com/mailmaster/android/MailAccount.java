package com.mailmaster.android;

/**
 * 邮箱账户模型。provider 决定 IMAP/SMTP 连接预设（QQ / 163 / Outlook）。
 */
public class MailAccount {
    public String id;
    public String provider;     // QQ / 163 / OUTLOOK / CUSTOM
    public String email;
    public String displayName;
    public String password;     // 授权码（持久化时经 Crypto 加密）
    public String imapHost;
    public int imapPort;
    public String smtpHost;
    public int smtpPort;
    public boolean useStartTls = true;

    public MailAccount() {}

    public static MailAccount preset(String provider, String email, String password) {
        MailAccount a = new MailAccount();
        a.id = "acc_" + System.currentTimeMillis();
        a.provider = provider;
        a.email = email;
        a.password = password;
        if ("QQ".equals(provider)) {
            a.imapHost = "imap.qq.com"; a.imapPort = 993;
            a.smtpHost = "smtp.qq.com"; a.smtpPort = 465; a.useStartTls = false;
        } else if ("163".equals(provider)) {
            a.imapHost = "imap.163.com"; a.imapPort = 993;
            a.smtpHost = "smtp.163.com"; a.smtpPort = 465; a.useStartTls = false;
        } else if ("OUTLOOK".equals(provider)) {
            a.imapHost = "outlook.office365.com"; a.imapPort = 993;
            a.smtpHost = "smtp.office365.com"; a.smtpPort = 587; a.useStartTls = true;
        }
        return a;
    }

    @Override
    public String toString() {
        if (displayName != null && !displayName.isEmpty()) {
            return email + " (" + displayName + ")";
        }
        return email;
    }
}
