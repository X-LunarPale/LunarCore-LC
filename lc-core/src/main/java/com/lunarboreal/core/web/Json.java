package com.lunarboreal.core.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.util.LinkedHashMap;
import java.util.Map;

final class Json {

    static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.DOUBLE)
            .create();

    private Json() {}

    static Map<String, Object> map(String body) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = GSON.fromJson(body, new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
            return m == null ? new LinkedHashMap<>() : m;
        } catch (RuntimeException e) {
            throw com.lunarboreal.api.web.HttpException.badRequest("请求体不是有效的 JSON 对象");
        }
    }

    /** 便捷构造有序 Map：of("a", 1, "b", 2)。值可为 null。 */
    static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return String.valueOf(d.longValue());
        return String.valueOf(v);
    }

    static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return s.equalsIgnoreCase("true") || s.equals("1");
        if (v instanceof Number n) return n.intValue() != 0;
        return def;
    }
}
