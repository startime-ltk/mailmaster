package com.mailmaster.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新邮件系统通知（框架原生 Notification，无 AndroidX 依赖）。
 *
 * 设计要点：
 *  - API 26+ 走 NotificationChannel；API 21-25 用旧构造器（版本分支保证不崩）；
 *  - 【按账户区分】（B5）：每个邮箱一条独立通知渠道（channel id = new_mail_&lt;accountId&gt;，
 *    名称 = 邮箱地址）+ 独立通知 id。多账户用户在通知栏分开看到各邮箱的新邮件，
 *    还能在系统设置里按邮箱单独关闭提醒（比如工作邮箱关掉、私人邮箱留着）；
 *  - 同一账户一次检查到多封新邮件只发一条（InboxStyle 最多列 5 封），避免通知轰炸；
 *  - PendingIntent 在 API 31+ 强制 FLAG_IMMUTABLE（不指定会直接崩）；
 *  - 点击通知打开主界面并自动切到该账户的筛选视图（Intent 带 accountId）。
 */
public final class MailNotifier {

    private static final String CHANNEL_PREFIX = "new_mail_";

    private MailNotifier() {}

    /** 有新邮件时调用；按账户分组逐组发通知。列表为空或通知服务不可用时静默跳过 */
    public static void notifyNewMail(Context ctx, List<MailMessage> newOnes) {
        if (newOnes == null || newOnes.isEmpty()) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 按账户分组（保持到达顺序，通知栏展示顺序稳定）
        Map<String, List<MailMessage>> byAccount = new LinkedHashMap<>();
        for (MailMessage m : newOnes) {
            if (m == null) continue;
            String key = (m.accountId == null) ? "" : m.accountId;
            List<MailMessage> list = byAccount.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byAccount.put(key, list);
            }
            list.add(m);
        }

        for (Map.Entry<String, List<MailMessage>> e : byAccount.entrySet()) {
            List<MailMessage> group = e.getValue();
            if (group.isEmpty()) continue;
            String channelId = ensureChannel(nm, e.getKey(), group.get(0));
            postGroup(ctx, nm, channelId, e.getKey(), group);
        }
    }

    /** 单账户一组：标题带账户邮箱，多封用 InboxStyle 列 5 封 */
    private static void postGroup(Context ctx, NotificationManager nm, String channelId,
                                  String accountId, List<MailMessage> group) {
        int count = group.size();
        MailMessage first = group.get(0);
        String email = (first.accountEmail == null || first.accountEmail.trim().isEmpty())
                ? "邮箱" : first.accountEmail.trim();

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, channelId);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(count == 1 ? "新邮件：" + safe(first.subject) : count + " 封新邮件")
                .setContentText(count == 1 ? email + " · " + safe(first.from) : email + " · 点击查看")
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent(ctx, accountId, email));

        // 多封时用 InboxStyle 逐封列出（最多 5 封，其余折成摘要行）
        if (Build.VERSION.SDK_INT >= 21 && count > 1) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            int n = Math.min(count, 5);
            for (int i = 0; i < n; i++) {
                MailMessage m = group.get(i);
                if (m == null) continue;
                style.addLine(safe(m.from) + "：" + safe(m.subject));
            }
            if (count > n) style.setSummaryText("等共 " + count + " 封");
            b.setStyle(style);
        }

        try {
            nm.notify(notifyIdFor(accountId), b.build());
        } catch (Exception ignored) {
        }
    }

    /**
     * 确保账户对应的通知渠道存在（API 26+），返回 channel id。
     * 渠道名 = 邮箱地址：用户在系统设置里看到的是「哪个邮箱的提醒」，可单独开关。
     */
    private static String ensureChannel(NotificationManager nm, String accountId, MailMessage sample) {
        if (Build.VERSION.SDK_INT < 26) return CHANNEL_PREFIX + "default";
        try {
            String channelId = CHANNEL_PREFIX + accountId;
            String name = (sample != null && sample.accountEmail != null
                    && !sample.accountEmail.trim().isEmpty())
                    ? sample.accountEmail.trim() : "邮箱";
            NotificationChannel ch = new NotificationChannel(channelId,
                    "新邮件提醒 · " + name, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("该邮箱后台收到新邮件时提醒（可按邮箱单独关闭）");
            nm.createNotificationChannel(ch);
            return channelId;
        } catch (Exception e) {
            return CHANNEL_PREFIX + "default";
        }
    }

    /**
     * 每个账户一个稳定的通知 id（accountId 哈希取模 + 基数）。
     * 同一账户的新通知覆盖旧通知（不堆叠），不同账户互不干扰。
     */
    private static int notifyIdFor(String accountId) {
        int h = (accountId == null) ? 0 : accountId.hashCode();
        if (h == Integer.MIN_VALUE) h = 42;
        return 1001 + Math.abs(h % 100000);
    }

    private static PendingIntent contentIntent(Context ctx, String accountId, String email) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (accountId != null && !accountId.isEmpty()) {
            it.putExtra("accountId", accountId); // 直达该账户筛选视图
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE; // API 31+ 不指定 mutability 直接崩
        }
        // requestCode 与 notifyId 同基：不同账户的 PendingIntent 互不覆盖（extras 不串）
        return PendingIntent.getActivity(ctx, notifyIdFor(accountId), it, flags);
    }

    private static String safe(String s) {
        return (s == null || s.trim().isEmpty()) ? "(无主题)" : s.trim();
    }
}
