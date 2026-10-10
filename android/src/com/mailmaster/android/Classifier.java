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
        return IMPORTANT;
    }

    /** AI 配置是否已可用（启用 + 有 Key） */
    public static boolean aiAvailable(AiSettings ai) {
        return ai != null && ai.enabled && ai.apiKey != null && !ai.apiKey.isEmpty();
    }

    /**
     * 用大模型对一批邮件分类，覆盖其 category 字段。
     * 失败（网络/鉴权/解析）时返回 false，调用方保留本地规则结果；已缓存的邮件直接复用旧结果。
     */
    public static boolean aiClassifyBatch(List<MailMessage> mails, AiSettings ai) {
        if (!aiAvailable(ai) || mails == null || mails.isEmpty()) return false;
        try {
            // 1) 先填充命中缓存的，收集需要请求 AI 的
            StringBuilder sb = new StringBuilder();
            List<Integer> idx = new ArrayList<Integer>();
            for (int i = 0; i < mails.size(); i++) {
                MailMessage m = mails.get(i);
                if (m.id != null && AI_CACHE.containsKey(m.id)) {
                    m.category = AI_CACHE.get(m.id);
                    continue;
                }
                idx.add(i);
                sb.append(i + 1).append(". 发件人: ").append(truncate(m.from, 60))
                  .append(" | 主题: ").append(truncate(m.subject, 80))
                  .append(" | 正文: ").append(truncate(m.body, 300)).append("\n");
            }
            if (idx.isEmpty()) return true; // 全部命中缓存

            // 2) 调 AI
            String content = callAi(ai, sb.toString());
            JSONObject root = new JSONObject(content);
            JSONArray cats = root.optJSONArray("cats");
            if (cats == null) cats = root.optJSONArray("categories");
            if (cats == null) cats = firstArray(root);
            if (cats == null) return false;

            int n = Math.min(idx.size(), cats.length());
            for (int k = 0; k < n; k++) {
                int mailIdx = idx.get(k);
                MailMessage m = mails.get(mailIdx);
                String c = normalizeCategory(cats.optString(k));
                if (c == null) c = m.category; // 无法识别则保留本地结果
                if (m.id != null) AI_CACHE.put(m.id, c);
                m.category = c;
            }
            return true;
        } catch (Exception e) {
            Log.e("Classifier", "ai classify failed", e);
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

    private static boolean containsAny(String text, String[] keys) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase();
        for (int i = 0; i < keys.length; i++) {
            if (t.contains(keys[i].toLowerCase())) return true;
        }
        return false;
    }
}
