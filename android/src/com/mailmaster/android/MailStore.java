package com.mailmaster.android;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户与邮件的本地存储（SharedPreferences + JSON）。
 * 未录入真实账户时进入演示模式，返回 DemoData。
 */
public class MailStore {
    private static final String PREFS = "mailmaster_accounts";

    public List<MailAccount> accounts = new ArrayList<>();
    public boolean demoMode = true;

    public void init(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("json", "");
        if (raw == null || raw.isEmpty()) {
            demoMode = true;
            accounts = DemoData.accounts();
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            List<MailAccount> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                MailAccount a = new MailAccount();
                a.id = o.getString("id");
                a.provider = o.getString("provider");
                a.email = o.getString("email");
                a.displayName = o.optString("displayName", "");
                a.password = Crypto.decrypt(o.getString("pw"));
                a.imapHost = o.getString("ih");
                a.imapPort = o.getInt("ip");
                a.smtpHost = o.getString("sh");
                a.smtpPort = o.getInt("sp");
                a.useStartTls = o.getBoolean("tls");
                loaded.add(a);
            }
            demoMode = loaded.isEmpty();
            accounts = demoMode ? DemoData.accounts() : loaded;
        } catch (Exception e) {
            demoMode = true;
            accounts = DemoData.accounts();
        }
    }

    public void addAccount(Context ctx, MailAccount a) {
        accounts.add(a);
        demoMode = false;
        save(ctx);
    }

    /** 是否存在真实（非演示）账户 */
    public boolean hasRealAccount() {
        for (MailAccount a : accounts) {
            if (!a.id.startsWith("demo-")) return true;
        }
        return false;
    }

    public void save(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (MailAccount a : accounts) {
                if (a.id.startsWith("demo-")) continue; // 不持久化演示账户
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("provider", a.provider);
                o.put("email", a.email);
                o.put("displayName", a.displayName);
                o.put("pw", Crypto.encrypt(a.password));
                o.put("ih", a.imapHost);
                o.put("ip", a.imapPort);
                o.put("sh", a.smtpHost);
                o.put("sp", a.smtpPort);
                o.put("tls", a.useStartTls);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putString("json", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<MailMessage> getInbox() {
        if (demoMode) return DemoData.messages();
        return MailService.fetchAll(accounts);
    }
}
