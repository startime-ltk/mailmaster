package com.mailmaster.android;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 邮件分类。
 *
 * 两类策略：
 *  1) 本地规则 classify()：不联网、零成本、隐私优先，弱网/离线也能用；
 *  2) AI 分类 aiClassifyBatch()：调用用户自填 Key 的大模型（OpenAI 兼容协议），
 *     对一批邮件统一分类，覆盖本地结果。失败/未配置时自动回退到本地规则，绝不中断流程。
 *
 * 推广（PROMO）类邮件由 MainActivity 的「疑似骚扰拦截」视图统一收纳，主收件箱默认不显示。
 */
public final class Classifier {

    public static final String IMPORTANT = "重要";
    public static final String PROMO = "推广";
    public static final String SOCIAL = "社交";
    public static final String OTHER = "其他";

    /** AI 分类缓存：邮件 id -> 分类，避免每次刷新重复调用烧 token */
    private static final Map<String, String> AI_CACHE = new HashMap<String, String>();

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

    /**
     * 本地规则判定分类。
     */
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

    /** AI 单次请求的邮件数上限：正文已截断到 300 字，8 封一批兼顾成功率与延迟 */
    private static final int AI_BATCH = 8;

    /**
     * 用大模型对一批邮件分类，覆盖其 category 字段。
     * 失败（网络/鉴权/解析）时返回 false，调用方保留本地规则结果；已缓存的邮件直接复用旧结果。
     *
     * 分批发送：一次塞几十封会撑爆上下文或直接超时，且中途任何一封出错会导致整批作废。
     * 拆成小批后，单批失败不影响其它批次，且已成功批次的分类结果照样生效。
     */
    public static boolean aiClassifyBatch(List<MailMessage> mails, AiSettings ai) {
        if (!aiAvailable(ai) || mails == null || mails.isEmpty()) return false;
        try {
            // 1) 先填充命中缓存的，收集需要请求 AI 的
            List<Integer> idx = new ArrayList<Integer>();
            for (int i = 0; i < mails.size(); i++) {
                MailMessage m = mails.get(i);
                if (m.id != null && AI_CACHE.containsKey(m.id)) {
                    m.category = AI_CACHE.get(m.id);
                    continue;
                }
                idx.add(i);
            }
            if (idx.isEmpty()) return true; // 全部命中缓存

            // 2) 分批调用：单批失败不影响其余批次
            boolean anyOk = false;
            for (int from = 0; from < idx.size(); from += AI_BATCH) {
                int to = Math.min(idx.size(), from + AI_BATCH);
                if (classifyChunk(mails, idx, from, to, ai)) anyOk = true;
            }
            return anyOk;
        } catch (Exception e) {
            Log.e("Classifier", "ai classify failed", e);
            return false;
        }
    }

    /** 处理 [from, to) 这一段邮件的一次 AI 请求 */
    private static boolean classifyChunk(List<MailMessage> mails, List<Integer> idx,
                                         int from, int to, AiSettings ai) {
        try {
            StringBuilder sb = new StringBuilder();
            // 编号对本批从 1 开始，提示词更短、模型更不容易串行
            for (int k = from; k < to; k++) {
                MailMessage m = mails.get(idx.get(k).intValue());
                sb.append(k - from + 1).append(". 发件人: ").append(truncate(m.from, 60))
                  .append(" | 主题: ").append(truncate(m.subject, 80))
                  .append(" | 正文: ").append(truncate(m.body, 300)).append("\n");
            }

            String content = callAi(ai, sb.toString());
            JSONArray cats = parseCats(content);
            if (cats == null) return false;

            int n = Math.min(to - from, cats.length());
            for (int k = 0; k < n; k++) {
                MailMessage m = mails.get(idx.get(from + k).intValue());
                String c = normalizeCategory(cats.optString(k));
                if (c == null) continue;           // 无法识别则保留本地结果
                if (m.id != null) AI_CACHE.put(m.id, c);
                m.category = c;
            }
            return true;
        } catch (Exception e) {
            Log.e("Classifier", "ai chunk failed " + from + "-" + to, e);
            return false;
        }
    }

    /** 真正发起 HTTP 请求，返回模型回复文本（应为 JSON 字符串） */
    private static String callAi(AiSettings ai, String mailsText) throws Exception {
        String sys = "你是邮件智能分类器。请把用户提供的每封邮件分到且仅分到以下四类之一："
                + "重要、推广、社交、其他。规则：交易/订单/验证码/学业/工作/安全通知算「重要」；"
                + "广告/营销/促销/退订类算「推广」；社交平台通知算「社交」；"
                + "其余日常或群发通知算「其他」。请只输出一个 JSON，"
                + "形如 {\"cats\":[\"重要\",\"推广\"]}，数组顺序与邮件编号一一对应，不要任何解释。";
        String user = "以下是邮件列表（编号 1..N）：\n" + mailsText
                + "\n请输出对应编号的 cats 数组。";

        JSONObject m1 = new JSONObject();
        m1.put("role", "system"); m1.put("content", sys);
        JSONObject m2 = new JSONObject();
        m2.put("role", "user"); m2.put("content", user);
        JSONArray messages = new JSONArray();
        messages.put(m1); messages.put(m2);

        JSONObject body = new JSONObject();
        body.put("model", ai.model);
        body.put("messages", messages);
        body.put("temperature", 0);

        byte[] payload = body.toString().getBytes("UTF-8");

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
            throw new java.io.IOException("HTTP " + code + " " + resp.toString());
        }

        JSONObject root = new JSONObject(resp.toString());
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new java.io.IOException("no choices");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        if (msg == null) throw new java.io.IOException("no message");
        return msg.optString("content", "");
    }

    /** 把模型返回的分类字符串规范化为四类之一；无法识别返回 null（调用方兜底） */
    public static String normalizeCategory(String c) {
        if (c == null) return null;
        c = c.trim();
        if (c.contains("重要")) return IMPORTANT;
        if (c.contains("推广")) return PROMO;
        if (c.contains("社交")) return SOCIAL;
        if (c.contains("其他")) return OTHER;
        return null;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").replace("\r", " ").trim();
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 取 JSON 对象里第一个数组（兼容模型返回 {"分类":[...]} 等变体） */
    private static JSONArray firstArray(JSONObject obj) {
        if (obj == null) return null;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            Object v;
            try { v = obj.get(it.next()); } catch (Exception e) { continue; }
            if (v instanceof JSONArray) return (JSONArray) v;
        }
        return null;
    }

    /**
     * 解析模型返回的 JSON：尽可能把「不守规矩」的输出也救回来。
     *
     * 真实模型（尤其轻量版）常见输出形态，逐级兜底：
     *  1) {"cats":[...]} / {"categories":[...]} / 任意首个数组；
     *  2) ```json 代码块包裹、前后带寒暄解释 → 先剥离围栏，再截取首个 JSON 片段；
     *  3) 完全不是 JSON 的自然语言 → 按出现顺序抽取四类词。
     *
     * 宁可多救一层，也别让一次付费请求白费。
     */
    private static JSONArray parseCats(String content) {
        if (content == null) return null;

        // 1) 先原样解析标准 JSON
        JSONArray a = tryJson(content);
        if (a != null) return a;

        // 2) 清洗：去掉 ```json 围栏，截取首个 {...} 或 [...] 片段
        String s = content.trim().replaceAll("(?s)```(?:json|JSON)?", "");
        int iObj = s.indexOf('{');
        int iArr = s.indexOf('[');
        if (iObj >= 0 || iArr >= 0) {
            int start, end;
            if (iArr >= 0 && (iObj < 0 || iArr < iObj)) {
                start = iArr;
                end = s.lastIndexOf(']');
            } else {
                start = iObj;
                end = s.lastIndexOf('}');
            }
            if (end > start) {
                a = tryJson(s.substring(start, end + 1));
                if (a != null) return a;
            }
        }

        // 3) 最后兜底：自然语言里按顺序抽类别词
        return extractCatsLoose(content);
    }

    /** 尝试把字符串解析为「合法且含四类词」的 JSON 数组 */
    private static JSONArray tryJson(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONArray a = o.optJSONArray("cats");
            if (a == null) a = o.optJSONArray("categories");
            if (a == null) a = firstArray(o);
            if (a != null) return a;
        } catch (Exception ignored) {
        }
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 倒数兜底：从任意文本中按出现顺序抽取 重要/推广/社交/其他 */
    private static JSONArray extractCatsLoose(String text) {
        if (text == null || text.isEmpty()) return null;
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("重要|推广|社交|其他").matcher(text);
        JSONArray arr = new JSONArray();
        while (mt.find()) arr.put(mt.group());
        return arr.length() == 0 ? null : arr;
    }

    /**
     * 测试 AI 连通性：发起一次最小调用，成功返回 null，失败返回错误描述。
     * 供设置页「测试连接」使用；注意返回 ModelNotOpen 即「账户未开通该模型」。
     */
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
        for (int i = 0; i < keys.length; i++) {
            if (t.contains(keys[i].toLowerCase())) return true;
        }
        return false;
    }
}
