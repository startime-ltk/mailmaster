package com.mailmaster.core.config;

/**
 * 主流邮箱服务商的连接预设（IMAP/SMTP 服务器与端口、加密方式、认证类型）。
 * 新增服务商只改这里即可，UI 与逻辑层都不用动 —— 便于多端复用。
 */
public class ProviderPresets {

    public static class Preset {
        public final String label;
        public final String imapHost;
        public final int imapPort;
        public final String smtpHost;
        public final int smtpPort;
        public final boolean useStartTls; // true=STARTTLS；false=SSL/IMAPS
        public final String authType;     // PASSWORD / APP_PASSWORD / OAUTH2
        public final String hint;         // UI 引导文案

        public Preset(String label, String imapHost, int imapPort, String smtpHost, int smtpPort,
                      boolean useStartTls, String authType, String hint) {
            this.label = label;
            this.imapHost = imapHost;
            this.imapPort = imapPort;
            this.smtpHost = smtpHost;
            this.smtpPort = smtpPort;
            this.useStartTls = useStartTls;
            this.authType = authType;
            this.hint = hint;
        }
    }

    public static Preset get(String key) {
        switch (key) {
            case "QQ":
                return new Preset("QQ邮箱", "imap.qq.com", 993, "smtp.qq.com", 465, false,
                        "APP_PASSWORD", "QQ邮箱需先在网页端开启 IMAP/SMTP 服务，并用生成的 16 位【授权码】登录（不是QQ密码）");
            case "NETEASE":
                return new Preset("网易163", "imap.163.com", 993, "smtp.163.com", 465, false,
                        "APP_PASSWORD", "163邮箱需开启 IMAP/SMTP 并生成【授权码】登录");
            case "OUTLOOK":
                return new Preset("Outlook/Hotmail", "outlook.office365.com", 993, "smtp.office365.com", 587, true,
                        "APP_PASSWORD", "Outlook 需开启两步验证并生成【应用密码】，SMTP 用 587 + STARTTLS");
            case "GMAIL":
                return new Preset("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465, false,
                        "OAUTH2", "Gmail 需 OAuth2 授权（国内还需代理），本期暂未实现，留作后续");
            default:
                return null; // CUSTOM：由调用方自行填写服务器信息
        }
    }

    /** 下拉框可选项 */
    public static String[] keys() {
        return new String[]{"QQ", "NETEASE", "OUTLOOK", "GMAIL", "CUSTOM"};
    }

    public static String labelOf(String key) {
        if ("CUSTOM".equals(key)) return "自定义";
        Preset p = get(key);
        return p == null ? key : p.label;
    }
}
