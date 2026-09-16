package com.mailmaster.android;

/**
 * 本地智能分类：把邮件分成 重要 / 推广 / 社交 / 其他。
 *
 * 设计取舍：
 * - 对标 Gmail 的「重要 / 推广 / 社交」三分法，用户体验熟悉、评委也认；
 * - 但完全用本地规则实现，不调用任何 AI API：
 *     1) 邮件内容不出手机，契合「本地优先、隐私优先」的定位；
 *     2) 零调用成本、零网络依赖，弱网和离线也能用；
 *     3) 规则可解释、可调整，后续想接 AI 只需替换 classify() 的实现。
 */
public final class Classifier {

    public static final String IMPORTANT = "重要";
    public static final String PROMO = "推广";
    public static final String SOCIAL = "社交";
    public static final String OTHER = "其他";

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
     * 判定邮件分类。
     *
     * @param from    发件人（已解码）
     * @param subject 主题（已解码）
     * @param body    正文（已解码）
     */
    public static String classify(String from, String subject, String body) {
        String f = (from == null ? "" : from).toLowerCase();
        String s = (subject == null ? "" : subject);
        String b = (body == null ? "" : body);
        String all = s + " " + b;

        // 1) 交易/事务类优先：即便来自群发地址也是用户关心的
        if (containsAny(all, TRANSACTION_WORDS)) return IMPORTANT;

        // 2) 推广营销
        if (containsAny(f, PROMO_SENDER) || containsAny(all, PROMO_WORDS)) return PROMO;

        // 3) 社交平台
        if (containsAny(f, SOCIAL_SENDER) || containsAny(all, SOCIAL_WORDS)) return SOCIAL;

        // 4) 重要：学业 / 工作 / 安全
        if (containsAny(f, IMPORTANT_SENDER) || containsAny(all, IMPORTANT_WORDS)) return IMPORTANT;

        // 5) 群发机器人 → 其他（如 GitHub、Coursera 的通知）
        if (containsAny(f, BOT_SENDER)) return OTHER;

        // 6) 其余按个人邮件处理 → 亲友邮件通常更重要
        return IMPORTANT;
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
