package com.mailmaster.core.demo;

import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 演示（Mock）数据源。
 *
 * 作用：无需任何真实邮箱、无需授权码，纯本地生成示例账户与邮件，
 * 用于「先看界面长什么样」、演示汇报、UI 打磨。
 *
 * 安全设计：
 *  - 演示账户统一使用 example.com 域名，不可能误连真实邮箱；
 *  - 演示模式下 MailEngine.sendMail 直接短路，绝不会真的联网发信。
 */
public final class DemoData {

    private DemoData() {}

    /** 示例账户：覆盖 QQ / 163 / Outlook 三种典型服务商，正好体现"多邮箱聚合" */
    public static List<EmailAccount> accounts() {
        List<EmailAccount> list = new ArrayList<>();
        list.add(account("demo-qq", "demo-qq@example.com", "QQ邮箱（演示）", "QQ",
                "imap.qq.com", 993, "smtp.qq.com", 465, false, "PASSWORD"));
        list.add(account("demo-163", "demo-163@example.com", "163邮箱（演示）", "NETEASE",
                "imap.163.com", 993, "smtp.163.com", 465, false, "PASSWORD"));
        list.add(account("demo-outlook", "demo-outlook@example.com", "Outlook（演示）", "OUTLOOK",
                "outlook.office365.com", 993, "smtp.office365.com", 587, true, "APP_PASSWORD"));
        return list;
    }

    /**
     * 演示邮件：把多个账户的邮件混在一起返回，
     * 这正是"统一收件箱"要展示的效果（UI 会按时间倒序排）。
     */
    public static List<EmailMessage> messages(List<EmailAccount> accounts) {
        long now = System.currentTimeMillis();
        List<EmailMessage> all = new ArrayList<>();

        EmailAccount qq = accounts.get(0);
        EmailAccount ne = accounts.get(1);
        EmailAccount ol = accounts.get(2);

        // —— QQ 邮箱（演示）——
        all.add(msg(qq, "学校教务处 <jwc@example.com>",
                "【重要】2026-2027 学年网上选课通知",
                "同学你好：<br/>本学期网上选课将于 <b>9 月 20 日 9:00</b> 正式开放，"
                        + "请登录教务系统完成选课。<br/>逾期未选视为自动放弃，届时将无法补选。",
                6, now));
        all.add(msg(qq, "GitHub <noreply@github.com>",
                "[mailmaster] 构建结果：通过",
                "你的仓库 <b>mailmaster</b> 最新一次构建已通过 ✅<br/>"
                        + "分支 main ｜ 耗时 42s ｜ 0 个失败用例。",
                95, now));
        all.add(msg(qq, "腾讯科技 <service@qq.example.com>",
                "登录验证码 8842（5 分钟内有效）",
                "你正在登录邮箱大师，验证码为 <b style='font-size:18px;letter-spacing:2px'>8842</b>"
                        + "<br/>如非本人操作，请忽略本邮件。",
                260, now));

        // —— 163 邮箱（演示）——
        all.add(msg(ne, "网易公开课 <open@example.com>",
                "本周上新：《从零开始的数据可视化》",
                "本周推荐课程已上线，包含 12 节实操视频与配套数据集，"
                        + "限时免费领取。<br/>点击「立即学习」即可开始。",
                40, now));
        all.add(msg(ne, "青年志愿者协会 <volunteer@example.com>",
                "周末志愿活动报名确认",
                "你好，你报名的 <b>社区敬老志愿服务</b> 已确认成功。<br/>"
                        + "集合时间：本周六 8:30 ｜ 地点：学校东门（示例地点）。",
                610, now));

        // —— Outlook（演示）——
        all.add(msg(ol, "Microsoft 账户团队 <account-security@outlook.example.com>",
                "安全提醒：你的账户在新设备上登录",
                "我们注意到你的 Microsoft 账户在 <b>一台新设备</b> 上登录。<br/>"
                        + "如果这是你本人操作，无需采取任何措施。",
                22, now));
        all.add(msg(ol, "Microsoft Teams <teams@outlook.example.com>",
                "会议邀请：项目进度同步（明天 15:00）",
                "你被邀请参加「邮箱大师项目进度同步」会议。<br/>"
                        + "时间：明天 15:00-15:30 ｜ 形式：线上会议。",
                1400, now));

        return all;
    }

    // ============ 内部辅助方法 ============

    private static EmailAccount account(String id, String email, String displayName, String provider,
                                        String imapHost, int imapPort, String smtpHost, int smtpPort,
                                        boolean startTls, String authType) {
        EmailAccount a = new EmailAccount();
        a.setId(id);
        a.setEmail(email);
        a.setDisplayName(displayName);
        a.setProvider(provider);
        a.setUsername(email);
        a.setImapHost(imapHost);
        a.setImapPort(imapPort);
        a.setSmtpHost(smtpHost);
        a.setSmtpPort(smtpPort);
        a.setUseStartTls(startTls);
        a.setAuthType(authType);
        a.setEncryptedPassword("DEMO-NOT-A-REAL-CREDENTIAL");
        return a;
    }

    private static EmailMessage msg(EmailAccount a, String from, String subject,
                                    String bodyInner, int minutesAgo, long now) {
        EmailMessage m = new EmailMessage();
        m.setId(a.getId() + "-" + minutesAgo);
        m.setAccountId(a.getId());
        m.setAccountEmail(a.getEmail());
        m.setFrom(from);
        m.setTo(a.getEmail());
        m.setSubject(subject);
        m.setSentDate(new Date(now - minutesAgo * 60_000L));
        m.setRead(minutesAgo > 300);
        m.setBodyText(subject);
        m.setBodyHtml(html(bodyInner));
        return m;
    }

    /** 包一层简单样式，让 WebView 渲染出来像"真邮件" */
    private static String html(String bodyInner) {
        return "<div style=\"font-family:'Microsoft YaHei','PingFang SC',sans-serif;"
                + "line-height:1.7;color:#222;max-width:560px\">"
                + bodyInner
                + "<hr style=\"border:none;border-top:1px solid #eee;margin:16px 0\"/>"
                + "<p style=\"color:#999;font-size:12px\">"
                + "📌 这是<b>演示数据</b>，未连接任何真实邮箱，也不会真正发送邮件。</p>"
                + "</div>";
    }
}
