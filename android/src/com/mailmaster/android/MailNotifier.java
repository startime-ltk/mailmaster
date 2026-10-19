package com.mailmaster.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.List;

/**
 * 新邮件系统通知（框架原生 Notification，无 AndroidX 依赖）。
 *
 * 设计要点：
 *  - API 26+ 走 NotificationChannel；API 21-25 用旧构造器（版本分支保证不崩）；
 *  - 摘要式 InboxStyle：一次检查发现多封新邮件只发一条，最多列 5 封，避免通知轰炸；
 *  - PendingIntent 在 API 31+ 强制 FLAG_IMMUTABLE（不指定会直接崩）；
 *  - 点击通知打开主界面（详情由用户自己点，通知不直达正文）。
 */
public final class MailNotifier {

    private static final String CHANNEL_ID = "new_mail";
    private static final int NOTIFY_ID = 1001;

    private MailNotifier() {}

    /** 有新邮件时调用；列表为空或通知服务不可用时静默跳过 */
    public static void notifyNewMail(Context ctx, List<MailMessage> newOnes) {
        if (newOnes == null || newOnes.isEmpty()) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(nm);

        int count = newOnes.size();
        MailMessage first = newOnes.get(0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(count == 1 ? "新邮件：" + safe(first.subject) : count + " 封新邮件")
                .setContentText(count == 1 ? safe(first.from) : "点击查看全部新邮件")
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent(ctx));

        // 多封时用 InboxStyle 逐封列出（最多 5 封，其余折成摘要行）
        if (Build.VERSION.SDK_INT >= 21 && count > 1) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            int n = Math.min(count, 5);
            for (int i = 0; i < n; i++) {
                MailMessage m = newOnes.get(i);
                if (m == null) continue;
                style.addLine(safe(m.from) + "：" + safe(m.subject));
            }
            if (count > n) style.setSummaryText("等共 " + count + " 封");
            b.setStyle(style);
        }

        try {
            nm.notify(NOTIFY_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    /** 通知渠道（API 26+ 必需；重复创建同 id 渠道只会更新配置，无害） */
    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "新邮件提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("后台自动收到新邮件时提醒");
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent contentIntent(Context ctx) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE; // API 31+ 不指定 mutability 直接崩
        }
        return PendingIntent.getActivity(ctx, 0, it, flags);
    }

    private static String safe(String s) {
        return (s == null || s.trim().isEmpty()) ? "(无主题)" : s.trim();
    }
}
