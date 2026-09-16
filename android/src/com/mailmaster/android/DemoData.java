package com.mailmaster.android;

import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据：3 个示例账户（QQ / 163 / Outlook）+ 7 封示例邮件。
 * 没有真实授权码时，App 用这些把界面填满，方便先看效果。
 */
public class DemoData {

    public static List<MailAccount> accounts() {
        List<MailAccount> list = new ArrayList<>();
        list.add(mk("demo-qq",     "QQ",      "demo-qq@example.com",     "小明", "imap.qq.com",     993, "smtp.qq.com",     465, false));
        list.add(mk("demo-163",    "163",     "demo-163@example.com",    "小红", "imap.163.com",    993, "smtp.163.com",    465, false));
        list.add(mk("demo-outlook","OUTLOOK", "demo-outlook@example.com","Leo",  "outlook.office365.com", 993, "smtp.office365.com", 587, true));
        return list;
    }

    private static MailAccount mk(String id, String p, String e, String n,
                                 String ih, int ip, String sh, int sp, boolean tls) {
        MailAccount a = new MailAccount();
        a.id = id; a.provider = p; a.email = e; a.displayName = n;
        a.password = "(demo)"; a.imapHost = ih; a.imapPort = ip;
        a.smtpHost = sh; a.smtpPort = sp; a.useStartTls = tls;
        return a;
    }

    public static List<MailMessage> messages() {
        List<MailMessage> m = new ArrayList<>();
        long now = System.currentTimeMillis();
        long H = 3600_000L;
        add(m, "demo-qq",      "demo-qq@example.com",     "德州学院教务处 <jwc@example.com>",       "【重要】2026-2027 学年校历安排",   "同学你好，附件为新学期校历，请按时注册。",                 now - 2*H,  true);
        add(m, "demo-qq",      "demo-qq@example.com",     "图书馆 <lib@example.com>",             "图书到期提醒",                     "您借阅的《Java 编程思想》将于 3 天后到期。",             now - 26*H, false);
        add(m, "demo-163",     "demo-163@example.com",    "GitHub <noreply@github.com>",         "[mailmaster] Your PR was merged",  "Congratulations! Your pull request has been merged.",   now - 5*H,  true);
        add(m, "demo-163",     "demo-163@example.com",    "淘宝 <notify@taobao.com>",            "订单发货通知",                     "您的订单已发货，预计 2 日内送达。",                    now - 30*H, false);
        add(m, "demo-outlook", "demo-outlook@example.com","Microsoft <account@microsoft.com>",    "Security alert",                  "We noticed a new sign-in to your account.",             now - 8*H,  true);
        add(m, "demo-outlook", "demo-outlook@example.com","Coursera <no-reply@coursera.org>",    "课程进度提醒",                     "您已连续学习 7 天，继续加油！",                       now - 50*H, false);
        add(m, "demo-qq",      "demo-qq@example.com",     "妈妈",                                "记得吃饭",                         "今天降温了，多穿点，好好学习。",                      now - 72*H, false);
        // 下面两封用于直观展示「智能分类」：一封会被判为推广、一封判为社交
        add(m, "demo-163",     "demo-163@example.com",    "京东 <promo@jd.com>",                 "限时优惠！家居专场满 300 减 100",   "限时促销，点击领取优惠券。若不想收到此类邮件请退订。",   now - 12*H, false);
        add(m, "demo-qq",      "demo-qq@example.com",     "微信团队 <wechat@weixin.qq.com>",      "好友请求通知",                     "你有一位新的好友请求，点击处理。",                    now - 40*H, false);
        return m;
    }

    private static void add(List<MailMessage> m, String accId, String accEmail,
                           String from, String subject, String body, long date, boolean unread) {
        MailMessage x = new MailMessage();
        x.id = accId + "-" + m.size();
        x.accountId = accId;
        x.accountEmail = accEmail;
        x.from = from;
        x.to = accEmail;
        x.subject = subject;
        x.body = body;
        x.date = date;
        x.unread = unread;
        x.category = Classifier.classify(from, subject, body);
        m.add(x);
    }
}
