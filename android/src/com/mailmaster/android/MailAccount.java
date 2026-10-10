package com.mailmaster.android;

/**
 * 邮箱账户模型。provider 决定 IMAP/SMTP 连接预设（QQ / 163 / Outlook）。
 */
public class MailAccount {
    public String id;
    public String provider;     // QQ / 163 / OUTLOOK / CUSTOM
    public String email;
    public String displayName;
    public String name;         // 账户备注（如「学校邮箱」），用于列表与筛选显示，可空
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

    /** 自定义邮箱：手填 IMAP/SMTP 主机、端口与是否 STARTTLS（学校/企业邮箱常用） */
    public static MailAccount presetCustom(String email, String password,
            String imapHost, int imapPort, String smtpHost, int smtpPort, boolean useStartTls) {
        MailAccount a = new MailAccount();
        a.id = "acc_" + System.currentTimeMillis();
        a.provider = "CUSTOM";
        a.email = email;
        a.password = password;
        a.imapHost = imapHost;
        a.imapPort = imapPort;
        a.smtpHost = smtpHost;
        a.smtpPort = smtpPort;
        a.useStartTls = useStartTls;
        return a;
    }

    /** 列表/筛选显示名：备注 > 邮箱；发信时仍用 displayName 作为发件人名 */
    public String label() {
        if (name != null && !name.isEmpty()) return name;
        return email;
    }

    @Override
    public String toString() {
        if (name != null && !name.isEmpty()) {
            return name + " (" + email + ")";
        }
        if (displayName != null && !displayName.isEmpty()) {
            return email + " (" + displayName + ")";
        }
        return email;
    }
}
