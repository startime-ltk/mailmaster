package com.mailmaster.android;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件模型。实现 Serializable 是为了在 Activity 之间整体传递，
 * 详情页才能拿到正文、HTML 与附件列表，而不必把字段一个个塞进 Intent。
 */
public class MailMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    public String id;
    public String accountId;
    public String accountEmail;
    public String from;
    public String to;
    public String subject;
    public String body;      // 纯文本正文（用于列表预览与搜索）
    public String html;      // HTML 原文（富文本渲染用，为空表示纯文本邮件）
    public List<Attachment> attachments = new ArrayList<>();
    public long date;       // epoch 毫秒
    public boolean unread;
    public String category; // 智能分类：重要 / 推广 / 社交 / 其他
    /** 邮件在服务器上的序号，用于回写已读状态；演示数据为 -1 */
    public int msgNum = -1;

    public boolean hasAttachment() {
        return attachments != null && !attachments.isEmpty();
    }

    /**
     * 从发件人字段里抽出纯邮箱地址。
     * 形如「学校教务处 &lt;jwc@example.com&gt;」要拿到 jwc@example.com 才能直接回信，
     * 否则会把显示名一起塞进收件人框，发出去必失败。
     */
    public String fromAddress() {
        String f = (from == null) ? "" : from.trim();
        int lt = f.indexOf('<');
        int gt = f.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return f.substring(lt + 1, gt).trim();
        }
        return f;
    }

    /** 回复时的默认主题（避免 Re: Re: 层层叠加） */
    public String replySubject() {
        String s = (subject == null) ? "" : subject;
        if (s.startsWith("Re:")) return s;
        return "Re: " + s;
    }

    /** 转发时的默认主题 */
    public String forwardSubject() {
        String s = (subject == null) ? "" : subject;
        if (s.startsWith("Fwd:")) return s;
        return "Fwd: " + s;
    }

    /** 回复/转发时引用的原文（带 > 前缀，符合邮件惯例） */
    public String quoted() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---------- 原始邮件 ----------\n");
        sb.append("发件人：").append(from == null ? "" : from).append('\n');
        sb.append("时　间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.CHINA).format(new java.util.Date(date > 0 ? date : System.currentTimeMillis()))).append('\n');
        sb.append("主　题：").append(subject == null ? "" : subject).append('\n');
        String t = (body == null) ? "" : body;
        String[] lines = t.split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append("> ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
