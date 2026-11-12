package com.lunarboreal.playertitleweb;

import java.util.Map;
import java.util.UUID;

final class Inputs {
    static int integer(Map<String, Object> body, String key, int min, int max) {
        Object value = body.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                || n.longValue() < min || n.longValue() > max) throw new IllegalArgumentException(key + " 必须为 " + min + "–" + max + " 的整数");
        return n.intValue();
    }
    static String text(Map<String, Object> body, String key, int max, boolean optional) {
        Object value = body.get(key);
        if (value == null && optional) return "";
        if (!(value instanceof String s) || s.length() > max || (!optional && s.isBlank())
                || s.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n')) throw new IllegalArgumentException(key + " 文本类型或长度无效");
        return s;
    }
    static String choice(Map<String, Object> body, String key, java.util.Set<String> allowed) {
        Object value = body.get(key);
        if (!(value instanceof String choice) || !allowed.contains(choice)) throw new IllegalArgumentException(key + " 选项无效");
        return choice;
    }
    static boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return false;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " 必须为布尔值");
        return result;
    }
    static UUID uuid(String input) {
        if (!input.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("请填写完整 UUID");
        return UUID.fromString(input);
    }
}
