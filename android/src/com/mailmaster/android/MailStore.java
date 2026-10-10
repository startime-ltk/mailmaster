package com.mailmaster.android;

import android.content.Context;
import android.content.SharedPreferences;
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
            accounts = loaded;
        } catch (Exception e) {
            // 解析失败也不回退到演示数据，避免静默混入示例内容
            accounts = new ArrayList<>();
        }
    }

    public void addAccount(Context ctx, MailAccount a) {
        accounts.add(a);
        save(ctx);
    }

    /** 删除指定账户；删除后重新保存 */
    public void removeAccount(String id) {
        List<MailAccount> kept = new ArrayList<>();
        for (MailAccount a : accounts) {
            if (!a.id.equals(id)) kept.add(a);
        }
        accounts = kept;
        save(ctx);
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
