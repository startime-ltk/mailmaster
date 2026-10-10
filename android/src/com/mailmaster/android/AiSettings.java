package com.mailmaster.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 分类配置。
 *
 * 设计取舍：
 *  - 用户自填 API Key，仅用 AES 加密存本机（与邮箱授权码同一套加密），绝不外传；
 *  - 只在调用所选大模型服务时用于鉴权，且只把「发件人 + 主题 + 截断正文」发给模型，
 *    不传附件、不传账户密码；
 *  - 兼容 OpenAI 协议：填入 baseUrl（通常是 .../v1）与模型名即可对接
 *    DeepSeek / OpenAI / 通义千问（兼容模式）/ 任意 OpenAI 兼容端点。
 *
 * 持久化文件固定为 "mailmaster_ai"，独立的 SP 文件，不影响账户数据（覆盖安装照旧保留）。
 */
public class AiSettings {
    private static final String PREFS = "mailmaster_ai";
    private static final String DEF_BASE = "https://api.deepseek.com/v1";
    private static final String DEF_MODEL = "deepseek-chat";

    public boolean enabled = false;
    public String apiKey = "";
    public String baseUrl = DEF_BASE;
    public String model = DEF_MODEL;

    public static AiSettings load(Context ctx) {
        AiSettings s = new AiSettings();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        s.enabled = sp.getBoolean("enabled", false);
        String ek = sp.getString("key", "");
        s.apiKey = (ek == null || ek.isEmpty()) ? "" : Crypto.decrypt(ek);
        s.baseUrl = sp.getString("base", DEF_BASE);
        s.model = sp.getString("model", DEF_MODEL);
        if (s.baseUrl == null || s.baseUrl.isEmpty()) s.baseUrl = DEF_BASE;
        if (s.model == null || s.model.isEmpty()) s.model = DEF_MODEL;
        return s;
    }

    public void save(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("enabled", enabled);
        e.putString("key", (apiKey == null || apiKey.isEmpty()) ? "" : Crypto.encrypt(apiKey));
        e.putString("base", baseUrl);
        e.putString("model", model);
        e.apply();
    }

    /** 拼接完整的 chat/completions 端点 */
    public String endpoint() {
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/chat/completions";
    }
}
