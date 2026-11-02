package com.mailmaster.android;

import android.content.Context;
import android.content.SharedPreferences;
import com.mailmaster.android.MailCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户与邮件的本地存储（SharedPreferences + JSON）。
 *
 * 持久化约定（直接关系到「覆盖安装 / 更新」时数据是否保留，勿随意改动）：
 *  - 文件名固定为 "mailmaster_accounts"，自 v1.0 起一直沿用，改名会导致老用户数据读不到。
 *  - 账户以 JSON 数组存在 "json" 字段；密码经 AES 加密（见 Crypto），密钥固定不变。
 *  - 只持久化真实账户，演示账户绝不写入。
 *  - SharedPreferences 位于应用私有目录，覆盖安装（同包名、同签名）时默认保留，
 *    因此升级后已录入的邮箱与授权码不会丢失；卸载重装才会清空。
 */
public class MailStore {
    /** 持久化锚点：文件名一旦随版本发布就不可改，否则老用户升级后数据清零 */
    private static final String PREFS = "mailmaster_accounts";

    public List<MailAccount> accounts = new ArrayList<>();
    /** 供附件落盘等场景使用（保存 ApplicationContext，避免持有 Activity 泄漏） */
    private Context ctx;

    public Context context() {
        return ctx;
    }

    public void init(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("json", "");
        if (raw == null || raw.isEmpty()) {
            // 首次启动 / 尚未录入任何邮箱：空收件箱，不再塞演示数据
            accounts = new ArrayList<>();
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            List<MailAccount> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                // 逐条容错：以前只要有一条记录坏掉（解密失败/字段缺失/旧版本格式），
                // 整个 accounts 就被清空，界面上表现为「账户凭空消失、点刷新毫无反应」。
                // 现在跳过坏记录、保留其余账户，坏了几条也不至于全丢。
                try {
                    JSONObject o = arr.getJSONObject(i);
                    MailAccount a = new MailAccount();
                    a.id = o.getString("id");
                    a.provider = o.optString("provider", "CUSTOM");
                    a.email = o.optString("email", "");
                    a.displayName = o.optString("displayName", "");
                    a.name = o.optString("nm", "");
                    a.password = Crypto.decrypt(o.optString("pw", ""));
                    a.imapHost = o.optString("ih", "");
                    a.imapPort = o.optInt("ip", 993);
                    a.smtpHost = o.optString("sh", "");
                    a.smtpPort = o.optInt("sp", 465);
                    a.useStartTls = o.optBoolean("tls", false);
                    if (a.id.isEmpty() || a.email.isEmpty()) {
                        throw new IllegalStateException("id/email 缺失");
                    }
                    loaded.add(a);
                } catch (Exception bad) {
                    android.util.Log.w("MailStore", "跳过损坏的账户记录 #" + i, bad);
                }
            }
            accounts = loaded;
        } catch (Exception e) {
            // 整体 JSON 都坏了才走到这里（极少见）
            accounts = new ArrayList<>();
        }
    }

    public void addAccount(Context ctx, MailAccount a) {
        accounts.add(a);
        save(ctx);
    }

    /** 删除指定账户；删除后重新保存，并级联清除该账户的本地缓存邮件与附件 */
    public void removeAccount(String id) {
        List<MailAccount> kept = new ArrayList<>();
        for (MailAccount a : accounts) {
            if (!a.id.equals(id)) kept.add(a);
        }
        accounts = kept;
        save(ctx);
        // 级联删除：本地邮件缓存 + 落盘附件（防止隐私残留与空间浪费）
        try {
            MailCache.getInstance(ctx).deleteAccount(id);
        } catch (Exception ignored) {
        }
    }

    public void save(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (MailAccount a : accounts) {
                if (a.id.startsWith("demo-")) continue; // 防御：演示账户不落盘
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("provider", a.provider);
                o.put("email", a.email);
                o.put("displayName", a.displayName);
                o.put("nm", a.name);
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
        if (accounts.isEmpty()) return new ArrayList<>();
        return MailService.fetchAll(ctx, accounts);
    }
}
