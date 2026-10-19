package com.mailmaster.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mailmaster.core.model.EmailMessage;
import com.mailmaster.core.service.AiSettings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件分类（桌面端，与安卓端 Classifier 同规则）。
 *
 * 两类策略：
 *  1) 本地规则 classify()：不联网、零成本、隐私优先，弱网/离线也能用；
 *  2) AI 分类 aiClassifyBatch()：调用用户自填 Key 的大模型（OpenAI 兼容协议），
 *     对一批邮件统一分类，覆盖本地结果。失败/未配置时自动回退本地规则，绝不中断流程。
 */
public final class Classifier {

    public static final String IMPORTANT = "重要";
    public static final String PROMO = "推广";
    public static final String SOCIAL = "社交";
    public static final String OTHER = "其他";

    /** AI 分类缓存：邮件 id -> 分类，避免每次刷新重复调用烧 token */
    private static final Map<String, String> AI_CACHE = new HashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 营销 / 推广 */
    private static final String[] PROMO_SENDER = {
            "promo", "marketing", "newsletter", "mailer-daemon", "edm@", "ad@", "campaign"
    };
    private static final String[] PROMO_WORDS = {
            "退订", "unsubscribe", "优惠", "促销", "限时", "折扣", "打折",
            "秒杀", "活动价", "清仓", "广告", "领券", "满减"
    };

    /** 社交平台 */
    private static final String[] SOCIAL_SENDER = {
            "wechat", "weixin", "weibo", "facebook", "twitter", "instagram",
            "linkedin", "tiktok", "douyin", "xiaohongshu", "rednote"
    };
    private static final String[] SOCIAL_WORDS = {
            "好友请求", "新的关注", "点赞", "评论了你", "@了你", "私信", "你被提到"
    };

    /** 交易类：虽常来自 notify 地址，但对用户很重要，优先判定 */
    private static final String[] TRANSACTION_WORDS = {
            "订单", "发货", "物流", "快递", "支付", "工资", "报销", "退款", "到账"
    };

    /** 重要：学业 / 工作 / 安全 / 事务 */
    private static final String[] IMPORTANT_WORDS = {
            "验证码", "验证", "面试", "offer", "录取", "成绩", "考试", "校历",
            "注册", "到期", "答辩", "竞赛", "获奖", "通知", "security",
            "安全提醒", "异常登录", "密码", "毕业", "奖学金", "课表", "选课"
    };
    private static final String[] IMPORTANT_SENDER = {
            "jwc", "教务处", "图书馆", "学院", "学校", "edu.cn", "hr@", "招生", "老师"
    };

    /** 群发机器人地址 */
    private static final String[] BOT_SENDER = {
            "noreply", "no-reply", "donotreply", "notify", "notifications", "auto-reply"
    };

    /** AI 单次请求的邮件数上限：正文截断到 300 字，8 封一批兼顾成功率与延迟 */
    private static final int AI_BATCH = 8;

    private Classifier() {}

    /** 本地规则判定分类 */
    public static String classify(String from, String subject, String body) {
        String f = (from == null ? "" : from).toLowerCase();
        String s = (subject == null ? "" : subject);
        String b = (body == null ? "" : body);
        String all = s + " " + b;

        if (containsAny(all, TRANSACTION_WORDS)) return IMPORTANT;
        if (containsAny(f, PROMO_SENDER) || containsAny(all, PROMO_WORDS)) return PROMO;
        if (containsAny(f, SOCIAL_SENDER) || containsAny(all, SOCIAL_WORDS)) return SOCIAL;
        if (containsAny(f, IMPORTANT_SENDER) || containsAny(all, IMPORTANT_WORDS)) return IMPORTANT;
        if (containsAny(f, BOT_SENDER)) return OTHER;
        // 兜底是「其他」而不是「重要」：否则未命中任何规则的邮件也全进重要，重要类就失去意义
        return OTHER;
    }

    /** AI 配置是否已可用（启用 + 有 Key） */
    public static boolean aiAvailable(AiSettings ai) {
        return ai != null && ai.enabled && ai.apiKey != null && !ai.apiKey.isEmpty();
    }

    /**
     * 用大模型对一批邮件分类，覆盖其 category 字段。
     * 失败（网络/鉴权/解析）时返回 false，调用方保留本地规则结果；已缓存的邮件直接复用旧结果。
     */
    public static boolean aiClassifyBatch(List<EmailMessage> mails, AiSettings ai) {
        if (!aiAvailable(ai) || mails == null || mails.isEmpty()) return false;
        try {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < mails.size(); i++) {
                EmailMessage m = mails.get(i);
                if (m.getId() != null && AI_CACHE.containsKey(m.getId())) {
                    m.setCategory(AI_CACHE.get(m.getId()));
                    continue;
                }
                idx.add(i);
            }
            if (idx.isEmpty()) return true;

            boolean anyOk = false;
            for (int from = 0; from < idx.size(); from += AI_BATCH) {
                int to = Math.min(idx.size(), from + AI_BATCH);
                if (classifyChunk(mails, idx, from, to, ai)) anyOk = true;
            }
            return anyOk;
        } catch (Exception e) {
            System.err.println("AI 分类失败: " + e.getMessage());
            return false;
        }
    }

    private static boolean classifyChunk(List<EmailMessage> mails, List<Integer> idx,
                                         int from, int to, AiSettings ai) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int k = from; k < to; k++) {
                EmailMessage m = mails.get(idx.get(k));
                sb.append(k - from + 1).append(". 发件人: ").append(truncate(m.getFrom(), 60))
                  .append(" | 主题: ").append(truncate(m.getSubject(), 80))
                  .append(" | 正文: ").append(truncate(m.getBodyText(), 300)).append("\n");
            }

            JsonNode cats = parseCats(callAi(ai, sb.toString()));
            if (cats == null || !cats.isArray()) return false;

            int n = Math.min(to - from, cats.size());
            for (int k = 0; k < n; k++) {
                EmailMessage m = mails.get(idx.get(from + k));
                String c = normalizeCategory(cats.get(k).asText(""));
                if (c == null) continue;
                if (m.getId() != null) AI_CACHE.put(m.getId(), c);
                m.setCategory(c);
            }
            return true;
        } catch (Exception e) {
            System.err.println("AI 分类分批失败 " + from + "-" + to + ": " + e.getMessage());
            return false;
        }
    }

    /** 发起 HTTP 请求，返回模型回复文本（应为 JSON 字符串） */
    private static String callAi(AiSettings ai, String mailsText) throws Exception {
        String sys = "你是邮件智能分类器。请把用户提供的每封邮件分到且仅分到以下四类之一："
                + "重要、推广、社交、其他。规则：交易/订单/验证码/学业/工作/安全通知算「重要」；"
                + "广告/营销/促销/退订类算「推广」；社交平台通知算「社交」；"
                + "其余日常或群发通知算「其他」。请只输出一个 JSON，"
                + "形如 {\"cats\":[\"重要\",\"推广\"]}，数组顺序与邮件编号一一对应，不要任何解释。";
        String user = "以下是邮件列表（编号 1..N）：\n" + mailsText + "\n请输出对应编号的 cats 数组。";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", ai.model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode m1 = messages.addObject();
        m1.put("role", "system"); m1.put("content", sys);
        ObjectNode m2 = messages.addObject();
        m2.put("role", "user"); m2.put("content", user);
        body.put("temperature", 0);

        byte[] payload = MAPPER.writeValueAsBytes(body);

        HttpURLConnection conn = (HttpURLConnection) new URL(ai.endpoint()).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + ai.apiKey);
        conn.setRequestProperty("Accept", "application/json");
        OutputStream os = conn.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new java.io.IOException("HTTP " + code + " " + resp);
        }

        JsonNode root = MAPPER.readTree(resp.toString());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) throw new java.io.IOException("no choices");
        return choices.get(0).path("message").path("content").asText("");
    }

    /** 规范化为四类之一；无法识别返回 null（调用方兜底） */
    public static String normalizeCategory(String c) {
        if (c == null) return null;
        c = c.trim();
        if (c.contains("重要")) return IMPORTANT;
        if (c.contains("推广")) return PROMO;
        if (c.contains("社交")) return SOCIAL;
        if (c.contains("其他")) return OTHER;
        return null;
    }

    /**
     * 解析模型返回的 JSON（与安卓端同策略，三级兜底）：
     * 标准 JSON → 剥离代码块围栏截取首个 JSON 片段 → 自然语言按序抽类别词。
     */
    private static JsonNode parseCats(String content) {
        if (content == null || content.isEmpty()) return null;
        try {
            JsonNode node = MAPPER.readTree(content);
            JsonNode arr = extractArray(node);
            if (arr != null) return arr;
        } catch (Exception ignored) {
        }
        String s = content.trim().replaceAll("(?s)```(?:json|JSON)?", "");
        int iObj = s.indexOf('{');
        int iArr = s.indexOf('[');
        if (iObj >= 0 || iArr >= 0) {
            int start, end;
            if (iArr >= 0 && (iObj < 0 || iArr < iObj)) {
                start = iArr; end = s.lastIndexOf(']');
            } else {
                start = iObj; end = s.lastIndexOf('}');
            }
            if (end > start) {
                try {
                    JsonNode arr = extractArray(MAPPER.readTree(s.substring(start, end + 1)));
                    if (arr != null) return arr;
                } catch (Exception ignored) {
                }
            }
        }
        return looseExtract(s);
    }

    /** 取节点里的 cats/categories 数组，或对象首个数组，或节点自身是数组 */
    private static JsonNode extractArray(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) return node;
        JsonNode a = node.path("cats");
        if (!a.isArray()) a = node.path("categories");
        if (!a.isArray()) {
            for (JsonNode child : node) {
                if (child.isArray()) return child;
            }
            return null;
        }
        return a;
    }

    private static JsonNode looseExtract(String text) {
        Matcher mt = Pattern.compile("重要|推广|社交|其他").matcher(text);
        ArrayNode arr = MAPPER.createArrayNode();
        while (mt.find()) arr.add(mt.group());
        return arr.size() == 0 ? null : arr;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ").trim();
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 测试 AI 连通性：成功返回 null，失败返回错误描述 */
    public static String testConnection(AiSettings ai) {
        try {
            callAi(ai, "发件人: test@example.com | 主题: 验证码 | 正文: 您的验证码是 123456");
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return (m == null || m.isEmpty()) ? "未知错误" : m;
        }
    }

    private static boolean containsAny(String text, String[] keys) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase();
        for (String k : keys) {
            if (t.contains(k.toLowerCase())) return true;
        }
        return false;
    }
}
