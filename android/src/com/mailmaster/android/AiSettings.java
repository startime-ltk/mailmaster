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
        // 构建时注入的 key（来自 gitignore 保护的本地文件，不进仓库）；仓库直编时为空
        String inj = injectedKey(ctx);
        boolean hasInj = (inj != null && !inj.isEmpty());
        boolean init = sp.getBoolean("init", false);
        s.enabled = sp.getBoolean("enabled", hasInj);
        String ek = sp.getString("key", "");
        s.apiKey = (ek != null && !ek.isEmpty()) ? Crypto.decrypt(ek) : inj;
        s.baseUrl = sp.getString("base", DEF_BASE);
        s.model = sp.getString("model", DEF_MODEL);
        if (s.baseUrl == null || s.baseUrl.isEmpty()) s.baseUrl = DEF_BASE;
        if (s.model == null || s.model.isEmpty()) s.model = DEF_MODEL;
        // 首次运行且有注入 key：默认启用并选用豆包（火山方舟）
        if (!init && hasInj) {
            s.baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
            s.model = "doubao-seed-2-1-lite-260915";
        }
        return s;
    }

    public void save(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean("init", true);
        e.putBoolean("enabled", enabled);
        e.putString("key", (apiKey == null || apiKey.isEmpty()) ? "" : Crypto.encrypt(apiKey));
        e.putString("base", baseUrl);
        e.putString("model", model);
        e.apply();
    }

    /** 读取构建时注入的 key（R.string.ai_api_key，由 build_apk.sh 从本地文件写入） */
    private static String injectedKey(Context ctx) {
        try {
            String v = ctx.getApplicationContext().getString(R.string.ai_api_key);
            return (v == null) ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** 拼接完整的 chat/completions 端点 */
    public String endpoint() {
        String b = baseUrl.trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/chat/completions";
    }
}
