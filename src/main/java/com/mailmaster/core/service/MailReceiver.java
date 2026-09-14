package com.mailmaster.core.service;

import com.mailmaster.core.model.EmailAccount;
import com.mailmaster.core.model.EmailMessage;

import javax.mail.*;
import javax.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IMAP 收信。主流邮箱 IMAP 均为 993 SSL（IMAPS），统一走加密连接。
 * 为演示稳定，仅拉取 INBOX 最近 limit 封；如需全量/增量可后续扩展。
 */
public class MailReceiver {

    public List<EmailMessage> fetchRecent(EmailAccount account, String password, int limit) throws Exception {
        Properties props = new Properties();
        props.put("mail.imaps.ssl.enable", "true");
        Session session = Session.getInstance(props, null);
        Store store = session.getStore("imaps");
        store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(), password);

        List<EmailMessage> result = new ArrayList<>();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message[] msgs = inbox.getMessages();
            int start = Math.max(0, msgs.length - limit);
            for (int i = start; i < msgs.length; i++) {
                result.add(parse(account, msgs[i]));
            }
            inbox.close(false);
        } finally {
            store.close();
        }
        return result;
    }

    private EmailMessage parse(EmailAccount account, Message m) throws Exception {
        EmailMessage em = new EmailMessage();
        em.setAccountId(account.getId());
        em.setAccountEmail(account.getEmail());
        Address[] from = m.getFrom();
        em.setFrom(from != null && from.length > 0 ? from[0].toString() : "");
        Address[] to = m.getRecipients(Message.RecipientType.TO);
        em.setTo(to != null && to.length > 0 ? to[0].toString() : "");
        em.setSubject(m.getSubject() == null ? "" : m.getSubject());
        em.setSentDate(m.getSentDate());
        em.setRead(m.getFlags().contains(Flags.Flag.SEEN));
        parseBody(m, em);
        long ts = m.getReceivedDate() != null ? m.getReceivedDate().getTime()
                : (m.getSentDate() != null ? m.getSentDate().getTime() : 0);
        em.setId(account.getId() + ":" + ts + ":" + (m.getSubject() == null ? 0 : m.getSubject().hashCode()));
        return em;
    }

    private void parseBody(Part part, EmailMessage em) throws Exception {
        if (part.isMimeType("text/plain")) {
            if (em.getBodyText() == null) em.setBodyText((String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            if (em.getBodyHtml() == null) em.setBodyHtml((String) part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (MimeMultipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                parseBody(mp.getBodyPart(i), em);
            }
        }
    }
}
