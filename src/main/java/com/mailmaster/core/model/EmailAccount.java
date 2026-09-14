package com.mailmaster.core.model;

import java.util.UUID;

/**
 * 邮箱账户模型。注意：密码字段只存 AES 加密后的密文，绝不保存明文。
 * 该模型为多端共享（PC / Web / 小程序后端）的核心数据结构。
 */
public class EmailAccount {
    private String id = UUID.randomUUID().toString();
    private String email;
    private String displayName;
    private String provider;          // QQ / NETEASE / OUTLOOK / GMAIL / CUSTOM
    private String username;          // 通常等于邮箱地址
    private String encryptedPassword; // AES 密文
    private String imapHost;
    private int imapPort;
    private String smtpHost;
    private int smtpPort;
    private boolean useStartTls;      // true=STARTTLS(Outlook 587)；false=SSL/IMAPS(QQ/163 465/993)
    private String authType;          // PASSWORD / APP_PASSWORD / OAUTH2

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String encryptedPassword) { this.encryptedPassword = encryptedPassword; }
    public String getImapHost() { return imapHost; }
    public void setImapHost(String imapHost) { this.imapHost = imapHost; }
    public int getImapPort() { return imapPort; }
    public void setImapPort(int imapPort) { this.imapPort = imapPort; }
    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }
    public boolean isUseStartTls() { return useStartTls; }
    public void setUseStartTls(boolean useStartTls) { this.useStartTls = useStartTls; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
}
