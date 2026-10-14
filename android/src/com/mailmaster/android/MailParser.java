package com.mailmaster.android;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
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

    private static final String TAG = "MailParser";

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
     *
     * 容错策略（「有的邮件只有标题没内容」的根治点）：
     * 旧版把整个方法包在一个 try-catch 里，任何一个 Part 抛异常都会让整封邮件正文归零；
     * 而标题走的是 getSubject() 头字段、不经过这里，于是出现「标题在、正文空」。
     * 现改为逐个 Part 独立容错——单个部件解析失败只跳过它，不连累整封。
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
                        Part child = null;
                        try {
                            child = mp.getBodyPart(i);
                        } catch (Exception e) {
                            Log.w(TAG, "extractBody: getBodyPart(" + i + ") failed", e);
                            continue;
                        }
                        if (child == null) continue;
                        String disp = null;
                        try {
                            disp = child.getDisposition();
                        } catch (Exception ignored) {
                        }
                        // 跳过附件（除非它同时是正文）
                        if (disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT)
                                && !child.isMimeType("text/*")) {
                            continue;
                        }
                        if (child.isMimeType("multipart/*")) {
                            // 递归方法自带整体容错，单个失败返回空串，不影响其它分支
                            String inner = extractBody(child);
                            if (plain == null && inner != null && !inner.isEmpty()) plain = inner;
                        } else if (child.isMimeType("text/plain") && plain == null) {
                            plain = readTextPartSafe(child);
                        } else if (child.isMimeType("text/html") && html == null) {
                            html = readTextPartSafe(child);
                        } else if (plain == null && child.isMimeType("text/*")
                                && !child.isMimeType("text/html")) {
                            // text/calendar（会议邀请）、text/xml、text/enriched 等非标准文本：
                            // 作为正文兜底候选，避免整封邮件只剩标题
                            plain = readTextPartSafe(child);
                        }
                    }
                    if (plain != null && !plain.trim().isEmpty()) return clip(plain);
                    if (html != null && !html.trim().isEmpty()) return clip(stripTags(html));
                }
                return "";
            }
            if (p.isMimeType("text/plain")) return clip(readTextPartSafe(p));
            if (p.isMimeType("text/html")) return clip(stripTags(readTextPartSafe(p)));
            // 顶层 text/calendar、text/xml 等非标准文本兜底
            if (p.isMimeType("text/*")) return clip(readTextPartSafe(p));
            return "";
        } catch (Exception e) {
            // 只有顶层彻底失败才返回空；单个 Part 的失败已在循环内单独消化
            Log.e(TAG, "extractBody failed", e);
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

    /**
     * 带容错的文本部件读取：单个部件失败只记日志并返回空串，
     * 不再让异常穿透到调用方、把整封邮件正文清零。
     */
    private static String readTextPartSafe(Part p) {
        try {
            return readTextPart(p);
        } catch (Exception e) {
            Log.w(TAG, "readTextPart failed, type=" + safeContentType(p), e);
            return "";
        }
    }

    /** 日志辅助：安全地取 Content-Type，避免日志代码本身再抛异常 */
    private static String safeContentType(Part p) {
        try {
            return p.getContentType();
        } catch (Exception e) {
            return "<unknown>";
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

    /**
     * 提取 HTML 原文（保留富文本，供 WebView 渲染）。
     * 与 extractBody 的区别：extractBody 会把标签扒掉变成纯文本，
     * 而这里保留原始排版——课程通知、成绩单这类带表格的邮件必须靠它才看得清。
     * 没有 HTML 部件时返回空串，由调用方决定回退到纯文本。
     */
    public static String extractHtml(Part p) {
        try {
            if (p == null) return "";
            if (p.isMimeType("multipart/*")) {
                Object c = p.getContent();
                if (c instanceof MimeMultipart) {
                    MimeMultipart mp = (MimeMultipart) c;
                    for (int i = 0; i < mp.getCount(); i++) {
                        Part child = null;
                        try {
                            child = mp.getBodyPart(i);
                        } catch (Exception e) {
                            Log.w(TAG, "extractHtml: getBodyPart(" + i + ") failed", e);
                            continue;
                        }
                        if (child == null) continue;
                        if (child.isMimeType("multipart/*")) {
                            // multipart/signed 的签名体、multipart/related 的图片都走这里递归或跳过，
                            // 正文（text/html）会被优先找到
                            String inner = extractHtml(child);
                            if (inner != null && !inner.isEmpty()) return inner;
                        } else if (child.isMimeType("text/html")) {
                            String h = readTextPartSafe(child);
                            if (h != null && !h.trim().isEmpty()) return h;
                        }
                    }
                }
                return "";
            }
            if (p.isMimeType("text/html")) return readTextPartSafe(p);
            return "";
        } catch (Exception e) {
            Log.e(TAG, "extractHtml failed", e);
            return "";
        }
    }

    /**
     * 递归收集附件并保存到本地目录。
     * 只把"非文本且有文件名"的部件当附件，避免把 HTML 正文里的内联图片误判成附件。
     */
    public static List<Attachment> extractAttachments(Part p, File dir) {
        List<Attachment> out = new ArrayList<>();
        try {
            if (p == null || dir == null) return out;
            if (!dir.exists()) dir.mkdirs();
            collectAttachments(p, dir, out);
        } catch (Exception e) {
            // 单个附件失败不影响整体收信
        }
        return out;
    }

    private static void collectAttachments(Part p, File dir, List<Attachment> out) throws Exception {
        if (p == null) return;
        if (p.isMimeType("multipart/*")) {
            Object c = p.getContent();
            if (c instanceof MimeMultipart) {
                MimeMultipart mp = (MimeMultipart) c;
                for (int i = 0; i < mp.getCount(); i++) {
                    collectAttachments(mp.getBodyPart(i), dir, out);
                }
            }
            return;
        }
        String fn = p.getFileName();
        String disp = p.getDisposition();
        boolean declared = (disp != null && disp.equalsIgnoreCase(Part.ATTACHMENT));
        boolean inline = (disp != null && disp.equalsIgnoreCase(Part.INLINE));
        boolean looksLikeFile = (fn != null && fn.length() > 0 && !p.isMimeType("text/*"));
        // 内联图片（cid 引用）已经嵌在正文里，不当附件重复展示
        if (!declared && (!looksLikeFile || inline)) return;
        if (fn == null || fn.length() == 0) return;

        Attachment a = saveAttachment(p, dir, decodeHeader(fn));
        if (a != null) out.add(a);
    }

    private static Attachment saveAttachment(Part p, File dir, String rawName) throws Exception {
        String name = safeFileName(rawName);
        if (name.length() == 0) name = "attachment.bin";
        File f = new File(dir, name);
        // 同名文件自动加序号，避免后一个覆盖前一个
        int dup = 1;
        while (f.exists()) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                f = new File(dir, name.substring(0, dot) + "(" + dup + ")" + name.substring(dot));
            } else {
                f = new File(dir, name + "(" + dup + ")");
            }
            dup++;
        }
        InputStream in = null;
        FileOutputStream os = null;
        long size = 0;
        try {
            in = p.getInputStream();
            os = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                size += n;
            }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
        Attachment a = new Attachment();
        a.name = name;
        a.path = f.getAbsolutePath();
        a.mime = baseMime(p.getContentType());
        a.size = size;
        return a;
    }

    /** 去掉路径分隔符，防止文件名里的 ../../ 写出到沙箱外面 */
    private static String safeFileName(String name) {
        if (name == null) return "";
        String s = name.replace("\\", "/");
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replace("/", "_").replace("\\", "_")
                .replace("\n", "").replace("\r", "").replace("\t", "");
        return s.trim();
    }

    /** 从 Content-Type 去掉 charset 等参数，只留主类型 */
    private static String baseMime(String ct) {
        if (ct == null) return "application/octet-stream";
        int i = ct.indexOf(';');
        String m = (i > 0) ? ct.substring(0, i) : ct;
        return m.trim();
    }

    /**
     * 把 HTML 原文整理成可直接喂给 WebView 的形态。
     * 关键一步：抹掉邮件里的原始 charset 声明，统一按 UTF-8 渲染——
     * 否则一封 GBK 的国内邮件在 WebView 里会显示成乱码，前面的解码功夫全白费。
     */
    public static String wrapForDisplay(String html) {
        if (html == null) return "";
        String s = html.trim();
        if (s.isEmpty()) return "";
        s = Pattern.compile("(?is)<meta[^>]*charset[^>]*>").matcher(s).replaceAll("");
        String meta = "<meta charset=\"utf-8\">";
        if (Pattern.compile("(?is)<head[^>]*>").matcher(s).find()) {
            s = Pattern.compile("(?is)<head[^>]*>").matcher(s).replaceFirst("<head>" + meta);
        } else if (Pattern.compile("(?is)<html[^>]*>").matcher(s).find()) {
            s = Pattern.compile("(?is)<html[^>]*>").matcher(s)
                    .replaceFirst("<html><head>" + meta + "</head>");
        } else {
            s = "<html><head>" + meta + "</head><body>" + s + "</body></html>";
        }
        return s;
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
