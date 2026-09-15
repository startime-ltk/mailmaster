package com.mailmaster.android;

import android.util.Log;
import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 真实收发逻辑（基于安卓版 JavaMail）。
 * 演示模式下 MainActivity 不会调用这里，只有录入真实账户后才走网络。
 */
public class MailService {

    public static List<MailMessage> fetchAll(List<MailAccount> accounts) {
        List<MailMessage> out = new ArrayList<>();
        for (MailAccount a : accounts) {
            if (a.id.startsWith("demo-")) continue;
            try {
                out.addAll(fetchOne(a));
            } catch (Exception e) {
                Log.e("MailService", "fetch failed: " + a.email, e);
            }
        }
        if (out.isEmpty()) out = DemoData.messages(); // 失败兜底，仍展示示例
        return out;
    }

    private static List<MailMessage> fetchOne(MailAccount a) throws MessagingException {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", a.imapHost);
        p.put("mail.imaps.port", String.valueOf(a.imapPort));
        Session s = Session.getInstance(p, new javax.mail.Authenticator() {
            @Override
            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                return new javax.mail.PasswordAuthentication(a.email, a.password);
            }
        });
        Store store = s.getStore("imaps");
        store.connect();
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        int total = inbox.getMessageCount();
        int start = Math.max(1, total - 19);
        Message[] msgs = inbox.getMessages(start, total);
        List<MailMessage> list = new ArrayList<>();
        for (Message mm : msgs) {
            MailMessage x = new MailMessage();
            x.id = a.id + "-" + mm.getMessageNumber();
            x.accountId = a.id;
            x.accountEmail = a.email;
            Address[] f = mm.getFrom();
            x.from = (f != null && f.length > 0) ? f[0].toString() : "";
            x.to = a.email;
            x.subject = (mm.getSubject() == null) ? "(无主题)" : mm.getSubject();
            try {
                Object c = mm.getContent();
                x.body = (c instanceof String) ? (String) c : c.toString();
            } catch (Exception e) {
                x.body = "";
            }
            x.date = (mm.getSentDate() != null) ? mm.getSentDate().getTime() : System.currentTimeMillis();
            try {
                x.unread = !mm.getFlags().contains(Flags.Flag.SEEN);
            } catch (Exception e) {
                x.unread = false;
            }
            list.add(x);
        }
        inbox.close(false);
        store.close();
        return list;
    }

    public static boolean send(MailAccount from, String to, String subject, String body) {
        try {
            Properties p = new Properties();
            p.put("mail.smtp.host", from.smtpHost);
            p.put("mail.smtp.port", String.valueOf(from.smtpPort));
            p.put("mail.smtp.auth", "true");
            if (from.useStartTls) {
                p.put("mail.smtp.starttls.enable", "true");
            } else {
                p.put("mail.smtp.ssl.enable", "true");
            }
            Session s = Session.getInstance(p, new javax.mail.Authenticator() {
                @Override
                protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(from.email, from.password);
                }
            });
            MimeMessage m = new MimeMessage(s);
            m.setFrom(new InternetAddress(from.email, from.displayName));
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            m.setSubject(subject, "UTF-8");
            m.setText(body, "UTF-8");
            Transport.send(m);
            return true;
        } catch (Exception e) {
            Log.e("MailService", "send failed", e);
            return false;
        }
    }
}
