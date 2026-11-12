package com.lunarboreal.api.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 时长解析/格式化：支持 "1y2mo3w4d5h6m7s"，也支持 "perm"/"永久" 表示永久（返回 null）。 */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(y|mo|w|d|h|m|s|年|月|周|天|小时|分钟|分|秒)", Pattern.CASE_INSENSITIVE);

    private Durations() {}

    /** @return 毫秒；null 表示永久；无法解析抛出 IllegalArgumentException */
    public static Long parse(String input) {
        if (input == null) throw new IllegalArgumentException("empty");
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever") || s.equals("永久") || s.equals("-1")) return null;
        if (s.matches("\\d+")) s = s + "s"; // 纯数字视为秒
        Matcher m = PART.matcher(s);
        long total = 0;
        while (m.find()) {
            total += Long.parseLong(m.group(1)) * unit(m.group(2));
        }
        if (!PART.matcher(s).replaceAll("").isBlank() || total <= 0) {
            throw new IllegalArgumentException("无法解析时长: " + input);
        }
        return total;
    }

    public static boolean isDuration(String input) {
        try { parse(input); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static long unit(String u) {
        return switch (u) {
            case "y", "年" -> 365L * 86_400_000L;
            case "mo", "月" -> 30L * 86_400_000L;
            case "w", "周" -> 7L * 86_400_000L;
            case "d", "天" -> 86_400_000L;
            case "h", "小时" -> 3_600_000L;
            case "m", "分钟", "分" -> 60_000L;
            case "s", "秒" -> 1000L;
            default -> throw new IllegalArgumentException(u);
        };
    }

    /** 格式化为中文，如 "1天2小时3分钟"。null 返回 "永久"。 */
    public static String format(Long millis) {
        if (millis == null) return "永久";
        long s = Math.max(0, millis / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天");
        if (h > 0) sb.append(h).append("小时");
        if (m > 0) sb.append(m).append("分钟");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("秒");
        return sb.toString();
    }
}
