package com.mailmaster.core.service;

import com.mailmaster.core.model.EmailAccount;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * SMTP 发信。根据账户的 useStartTls 决定加密方式：
 *  - false -> SSL（465，QQ/163/Gmail）
 *  - true  -> STARTTLS（587，Outlook）
 */
public class MailSender {

    public void send(EmailAccount account, String password, String to, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", account.getSmtpHost());
        props.put("mail.smtp.port", account.getSmtpPort());
        props.put("mail.smtp.auth", "true");
        if (account.isUseStartTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        } else {
            props.put("mail.smtp.ssl.enable", "true");
        }

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(account.getUsername(), password);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(account.getEmail(), account.getDisplayName()));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject(subject, "UTF-8");
        msg.setText(body, "UTF-8");
        Transport.send(msg);
    }
}
