package com.mailmaster.android;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据：3 个示例账户（QQ / 163 / Outlook）+ 9 封示例邮件。
 * 没有真实授权码时，App 用这些把界面填满，方便先看效果。
 *
 * 演示模式下也会真实生成附件文件与一段 HTML 正文，
 * 这样「附件列表 / 富文本渲染 / 回复转发」在没录真实邮箱时也能演示完整。
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
        return messages(null);
    }

    public static List<MailMessage> messages(Context ctx) {
        List<MailMessage> m = new ArrayList<>();
        long now = System.currentTimeMillis();
        long H = 3600_000L;

        // 教务处这封带两个附件 + HTML 表格正文，用来演示富文本与附件
        File demoDir = demoDir(ctx);
        List<Attachment> jwcAtts = new ArrayList<>();
        if (demoDir != null) {
            Attachment a1 = fakeFile(demoDir, "2026-2027学年校历.txt",
                    "2026-2027 学年校历\n\n秋季学期：2026-09-07 开学\n寒假：2027-01-18 起\n"
                            + "春季学期：2027-02-28 开学\n暑假：2027-07-12 起\n");
            if (a1 != null) jwcAtts.add(a1);
            Attachment a2 = fakeFile(demoDir, "期末考试安排.csv",
                    "课程,时间,地点\nJava 程序设计,2027-01-11 09:00,博文楼 301\n"
                            + "数据结构,2027-01-13 14:00,博文楼 205\n");
            if (a2 != null) jwcAtts.add(a2);
        }
        add(m, "demo-qq", "demo-qq@example.com", "德州学院教务处 <jwc@example.com>",
                "【重要】2026-2027 学年校历安排",
                "同学你好，附件为新学期校历与期末考试安排，请按时注册。",
                now - 2 * H, true,
                "<p>同学你好：</p><p>附件为 <b>2026-2027 学年校历</b>，请按时注册。</p>"
                        + "<table border=\"1\" cellspacing=\"0\" cellpadding=\"6\">"
                        + "<tr><th>学期</th><th>开学</th><th>放假</th></tr>"
                        + "<tr><td>秋季</td><td>2026-09-07</td><td>2027-01-18</td></tr>"
                        + "<tr><td>春季</td><td>2027-02-28</td><td>2027-07-12</td></tr></table>",
                jwcAtts);

        add(m, "demo-qq", "demo-qq@example.com", "图书馆 <lib@example.com>",
                "图书到期提醒", "您借阅的《Java 编程思想》将于 3 天后到期。", now - 26 * H, false);

        add(m, "demo-163", "demo-163@example.com", "GitHub <noreply@github.com>",
                "[mailmaster] Your PR was merged",
                "Congratulations! Your pull request has been merged.", now - 5 * H, true);

        List<Attachment> tbAtts = new ArrayList<>();
        if (demoDir != null) {
            Attachment a3 = fakeFile(demoDir, "订单详情.pdf",
                    "订单号：20260912003421\n商品：机械键盘 x1\n金额：￥329.00\n");
            if (a3 != null) tbAtts.add(a3);
        }
        add(m, "demo-163", "demo-163@example.com", "淘宝 <notify@taobao.com>",
                "订单发货通知", "您的订单已发货，预计 2 日内送达，详情见附件。",
                now - 30 * H, false, null, tbAtts);

        add(m, "demo-outlook", "demo-outlook@example.com", "Microsoft <account@microsoft.com>",
                "Security alert", "We noticed a new sign-in to your account.", now - 8 * H, true);

        add(m, "demo-outlook", "demo-outlook@example.com", "Coursera <no-reply@coursera.org>",
                "课程进度提醒", "您已连续学习 7 天，继续加油！", now - 50 * H, false);

        add(m, "demo-qq", "demo-qq@example.com", "妈妈",
                "记得吃饭", "今天降温了，多穿点，好好学习。", now - 72 * H, false);

        // 下面两封用于直观展示「智能分类」：一封会被判为推广、一封判为社交
        add(m, "demo-163", "demo-163@example.com", "京东 <promo@jd.com>",
                "限时优惠！家居专场满 300 减 100",
                "限时促销，点击领取优惠券。若不想收到此类邮件请退订。", now - 12 * H, false);

        add(m, "demo-qq", "demo-qq@example.com", "微信团队 <wechat@weixin.qq.com>",
                "好友请求通知", "你有一位新的好友请求，点击处理。", now - 40 * H, false);

        return m;
    }

    private static void add(List<MailMessage> m, String accId, String accEmail,
                           String from, String subject, String body, long date, boolean unread) {
        add(m, accId, accEmail, from, subject, body, date, unread, null, null);
    }

    private static void add(List<MailMessage> m, String accId, String accEmail,
                           String from, String subject, String body, long date, boolean unread,
                           String html, List<Attachment> atts) {
        MailMessage x = new MailMessage();
        x.id = accId + "-" + m.size();
        x.accountId = accId;
        x.accountEmail = accEmail;
        x.from = from;
        x.to = accEmail;
        x.subject = subject;
        x.body = body;
        x.html = html;
        if (atts != null) x.attachments = atts;
        x.date = date;
        x.unread = unread;
        x.category = Classifier.classify(from, subject, body);
        m.add(x);
    }

    /** 演示附件存放目录（只在传入 Context 时生成，纯 JVM 测试时为 null） */
    private static File demoDir(Context ctx) {
        if (ctx == null) return null;
        try {
            File d = new File(ctx.getFilesDir(), "demo-attachments");
            if (!d.exists()) d.mkdirs();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** 造一个真实存在的本地文件，让演示附件的「打开/分享」可以真的点下去 */
    private static Attachment fakeFile(File dir, String name, String content) {
        try {
            File f = new File(dir, name);
            FileOutputStream os = new FileOutputStream(f);
            os.write(content.getBytes("UTF-8"));
            os.close();
            Attachment a = new Attachment();
            a.name = name;
            a.path = f.getAbsolutePath();
            a.size = f.length();
            a.mime = name.endsWith(".pdf") ? "application/pdf"
                    : name.endsWith(".csv") ? "text/csv" : "text/plain";
            return a;
        } catch (Exception e) {
            return null;
        }
    }
}
