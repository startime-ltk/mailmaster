package com.mailmaster.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.regex.Pattern;
import javax.mail.Part;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;

/**
 * 中文邮件解析：专门治理国内邮箱（QQ / 163 / 企业邮箱）常见的乱码问题。
 *
 * 主流邮件客户端（多为国外开发者）在这类邮件上常踩三个坑：
 *  1. 主题、发件人没做 encoded-word 解码（形如 =?GBK?B?xxxx?=）→ 中文主题显示成乱码串
 *  2. 正文只处理纯文本，遇到 multipart 直接 toString() → 显示成 MimeMultipart@1a2b3c
 *  3. 邮件缺失 charset 声明时按 ISO-8859-1 或 UTF-8 硬解 → 中文正文乱码
 *
 * 本类逐条处理：头字段解码、multipart 递归提取、缺失 charset 时用 UTF-8 严格校验 + GBK 兜底探测。
 */
public final class MailParser {

    /** 正文最大长度，避免超长邮件把内存和列表拖垮 */
    private static final int MAX_BODY = 20_000;

    /**
     * 解码邮件头字段（主题 / 发件人）。
     * 已处理 =?GBK?B?...?= / =?UTF-8?Q?...?= 等 encoded-word。
     */
    public static String decodeHeader(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        try {
            String dec = MimeUtility.decodeText(s);
            return (dec == null) ? s : dec;
        } catch (Exception e) {
            // 解码失败保留原文，至少不崩、不丢信息
            return s;
        }
    }

    /**
     * 递归提取正文。优先 text/plain，其次 text/html（转纯文本），跳过附件。
     */
    public static String extractBody(Part p) {
        try {
            if (p == null) return "";
            if (p.isMimeType("multipart/*")) {
                Object c = p.getContent();
                if (c instanceof MimeMultipart) {
                    MimeMultipart mp = (MimeMultipart) c;
                    String plain = null;
                    String html = null;
                    for (int i = 0; i < mp.getCount(); i++) {
                        Part child = mp.getBodyPart(i);
                        if (child == null) continue;
                        String disp = child.getDisposition();
                        // 跳过附件（除非它同时是正文）
                        if (disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT)
                                && !child.isMimeType("text/*")) {
                            continue;
                        }
                        if (child.isMimeType("multipart/*")) {
                            String inner = extractBody(child);
                            if (plain == null && !inner.isEmpty()) plain = inner;
                        } else if (child.isMimeType("text/plain") && plain == null) {
                            plain = readTextPart(child);
                        } else if (child.isMimeType("text/html") && html == null) {
                            html = readTextPart(child);
                        }
                    }
                    if (plain != null && !plain.trim().isEmpty()) return clip(plain);
                    if (html != null && !html.trim().isEmpty()) return clip(stripTags(html));
                }
                return "";
            }
            if (p.isMimeType("text/plain")) return clip(readTextPart(p));
            if (p.isMimeType("text/html")) return clip(stripTags(readTextPart(p)));
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 读取文本部件：自行按 charset 解码，缺失时探测 */
    private static String readTextPart(Part p) throws Exception {
        InputStream in = null;
        try {
            in = p.getInputStream();   // 已做 QP / Base64 传输解码，得到原始字节
            byte[] bytes = readAll(in);
            if (bytes.length == 0) return "";
            String cs = charsetOf(p);
            if (cs == null || cs.isEmpty()) {
                cs = detectCharset(bytes);
            }
            try {
                return new String(bytes, cs);
            } catch (Exception e) {
                // charset 名不合法时兜底 GBK，再不行用 UTF-8
                try {
                    return new String(bytes, "GBK");
                } catch (Exception e2) {
                    return new String(bytes, "UTF-8");
                }
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 从 Content-Type 提取 charset，处理别名与引号 */
    private static String charsetOf(Part p) {
        try {
            String ct = p.getContentType();
            if (ct == null) return null;
            int i = ct.toLowerCase().indexOf("charset");
            if (i < 0) return null;
            int eq = ct.indexOf('=', i);
            if (eq < 0) return null;
            String cs = ct.substring(eq + 1).trim();
            cs = cs.replace("\"", "").replace("'", "");
            int semi = cs.indexOf(';');
            if (semi >= 0) cs = cs.substring(0, semi);
            cs = cs.trim();
            if (cs.isEmpty()) return null;
            // 常见别名归一
            if (cs.equalsIgnoreCase("gb2312") || cs.equalsIgnoreCase("gb-2312")) return "GBK";
            if (cs.equalsIgnoreCase("utf8")) return "UTF-8";
            if (cs.equalsIgnoreCase("iso-8859-1") || cs.equalsIgnoreCase("latin1")) {
                // 声明 ISO-8859-1 却含中文是国内老邮件的典型情况，交给探测
                return null;
            }
            return cs;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 探测编码：先用 UTF-8 严格模式校验，非法则用 GBK。
     * 国内邮件在缺失 charset 时绝大多数是 GBK / GB2312。
     */
    private static String detectCharset(byte[] bytes) {
        try {
            CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8").newDecoder();
            dec.onMalformedInput(CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(CodingErrorAction.REPORT);
            dec.decode(ByteBuffer.wrap(bytes));
            return "UTF-8";
        } catch (CharacterCodingException e) {
            return "GBK";
        } catch (Exception e) {
            return "GBK";
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** HTML 转纯文本 */
    public static String stripTags(String html) {
        if (html == null) return "";
        String s = html;
        s = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>").matcher(s).replaceAll("");
        s = Pattern.compile("(?i)<br\\s*/?>").matcher(s).replaceAll("\n");
        s = Pattern.compile("(?i)</(p|div|tr|li)>").matcher(s).replaceAll("\n");
        s = Pattern.compile("<[^>]+>").matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return (s.length() > MAX_BODY) ? s.substring(0, MAX_BODY) : s;
    }
}
