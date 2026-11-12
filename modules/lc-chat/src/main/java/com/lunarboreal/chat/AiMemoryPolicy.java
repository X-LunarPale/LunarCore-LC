package com.lunarboreal.chat;

import java.util.regex.Pattern;

/** Conservative opt-in retention: only explicit, short player preferences, never raw dialogue. */
final class AiMemoryPolicy {
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(密码|口令|验证码|密钥|令牌|token|password|api.?key|secret|bearer|身份证|手机号|电话|邮箱|email|地址|住址|银行卡|银行卡号|信用卡|坐标|ip地址|https?://|[0-9]{6,}|\\S+@\\S+\\.[a-z]+)");
    private static final Pattern PREFERENCE = Pattern.compile("(?i)^(?:@[^\\s]+\\s*)?(我(?:喜欢|不喜欢|偏好|更喜欢|希望你|想让你|的游戏目标是)|以后(?:请|叫我|称呼我)|请(?:叫我|称呼我|记住我喜欢|记住我不喜欢)|I (?:like|prefer|dislike) |please call me )[^\\r\\n]*$");

    private AiMemoryPolicy() {}

    static String extract(String message) {
        if (message == null) return null;
        String text = message.strip().replaceAll("\\s+", " ");
        if (text.length() < 5 || text.length() > 120 || text.startsWith("/") || text.contains("<") || text.contains(">")
                || SENSITIVE.matcher(text).find() || !PREFERENCE.matcher(text).matches()) return null;
        text = text.replaceFirst("^@[^\\s]+\\s*", "").replaceAll("[。！!？?\\s]+$", "").strip();
        return text.length() >= 5 ? "玩家偏好：" + text : null;
    }

    static boolean retained(String text) {
        return text != null && text.startsWith("玩家偏好：") && extract(text.substring("玩家偏好：".length())) != null;
    }
}
