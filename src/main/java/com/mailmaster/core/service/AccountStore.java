package com.mailmaster.core.service;

import com.mailmaster.core.model.EmailAccount;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户持久化（H2 本地文件库）。只存"已加密"的凭证，不负责加密本身。
 * 该存储格式多端共享：Web / 小程序后端可直接复用同一张表。
 */
public class AccountStore {
    private final String dbUrl;

    public AccountStore(String dbPath) {
        // 例：/home/you/.mailmaster/db
        this.dbUrl = "jdbc:h2:" + dbPath;
        init();
    }

    private void init() {
        try (Connection c = DriverManager.getConnection(dbUrl);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts (" +
                    "id VARCHAR(64) PRIMARY KEY," +
                    "email VARCHAR(255)," +
                    "display_name VARCHAR(255)," +
                    "provider VARCHAR(32)," +
                    "username VARCHAR(255)," +
                    "enc_password VARCHAR(1024)," +
                    "imap_host VARCHAR(128)," +
                    "imap_port INT," +
                    "smtp_host VARCHAR(128)," +
                    "smtp_port INT," +
                    "use_starttls BOOLEAN," +
                    "auth_type VARCHAR(32))");
        } catch (SQLException e) {
            throw new RuntimeException("初始化账户数据库失败", e);
        }
    }

    public void save(EmailAccount a) {
        String sql = "MERGE INTO accounts (id,email,display_name,provider,username,enc_password," +
                "imap_host,imap_port,smtp_host,smtp_port,use_starttls,auth_type) KEY(id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.getId());
            ps.setString(2, a.getEmail());
            ps.setString(3, a.getDisplayName());
            ps.setString(4, a.getProvider());
            ps.setString(5, a.getUsername());
            ps.setString(6, a.getEncryptedPassword());
            ps.setString(7, a.getImapHost());
            ps.setInt(8, a.getImapPort());
            ps.setString(9, a.getSmtpHost());
            ps.setInt(10, a.getSmtpPort());
            ps.setBoolean(11, a.isUseStartTls());
            ps.setString(12, a.getAuthType());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("保存账户失败", e);
        }
    }

    public List<EmailAccount> loadAll() {
        List<EmailAccount> list = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(dbUrl);
             ResultSet rs = c.createStatement().executeQuery("SELECT * FROM accounts")) {
            while (rs.next()) {
                EmailAccount a = new EmailAccount();
                a.setId(rs.getString("id"));
                a.setEmail(rs.getString("email"));
                a.setDisplayName(rs.getString("display_name"));
                a.setProvider(rs.getString("provider"));
                a.setUsername(rs.getString("username"));
                a.setEncryptedPassword(rs.getString("enc_password"));
                a.setImapHost(rs.getString("imap_host"));
                a.setImapPort(rs.getInt("imap_port"));
                a.setSmtpHost(rs.getString("smtp_host"));
                a.setSmtpPort(rs.getInt("smtp_port"));
                a.setUseStartTls(rs.getBoolean("use_starttls"));
                a.setAuthType(rs.getString("auth_type"));
                list.add(a);
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取账户失败", e);
        }
        return list;
    }

    public void delete(String id) {
        try (Connection c = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = c.prepareStatement("DELETE FROM accounts WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除账户失败", e);
        }
    }
}
