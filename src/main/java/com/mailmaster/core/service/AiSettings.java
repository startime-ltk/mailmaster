package com.mailmaster.core.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * AI 分类设置（桌面端）：存于 ~/.mailmaster/ai.properties（明文 key，仅本机）。
 * 与安卓端 AiSettings 对齐：未启用/未填 Key 时自动回退本地规则分类，绝不中断。
 */
public class AiSettings {

    public boolean enabled = false;
    public String apiKey = "";
    public String baseUrl = "https://api.deepseek.com/v1";
    public String model = "deepseek-chat";

    private static final String FILE = "ai.properties";

    public static AiSettings load(File dir) {
        AiSettings s = new AiSettings();
        File f = new File(dir, FILE);
        if (!f.exists()) return s;
        FileInputStream in = null;
        try {
            Properties p = new Properties();
            in = new FileInputStream(f);
            p.load(in);
            s.enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
            s.apiKey = p.getProperty("apiKey", "");
            s.baseUrl = p.getProperty("baseUrl", s.baseUrl);
            s.model = p.getProperty("model", s.model);
        } catch (IOException ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        return s;
    }

    public void save(File dir) {
        try {
            if (!dir.exists()) dir.mkdirs();
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("apiKey", apiKey == null ? "" : apiKey);
            p.setProperty("baseUrl", baseUrl == null ? "" : baseUrl);
            p.setProperty("model", model == null ? "" : model);
            FileOutputStream os = new FileOutputStream(new File(dir, FILE));
            p.store(os, "MailMaster AI 分类设置（仅本机）");
            os.close();
        } catch (IOException ignored) {
        }
    }

    /** OpenAI 兼容端点（baseUrl 已带 /v1 时直接拼 /chat/completions） */
    public String endpoint() {
        String b = (baseUrl == null || baseUrl.trim().isEmpty())
                ? "https://api.deepseek.com/v1" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/chat/completions";
    }
}
