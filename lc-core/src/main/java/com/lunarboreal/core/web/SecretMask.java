package com.lunarboreal.core.web;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 文本中敏感字段（键名含 password / secret / token / api-key）的脱敏与还原。
 * 按行解析（缩进确定层级），保留注释与格式；只处理单行标量值。
 */
final class SecretMask {

    static final String MASK = "******";
    private static final Pattern KV = Pattern.compile("^(\\s*)([^#\\s\\-][^:#]*?)(\\s*:)(\\s*)(.*)$");

    private SecretMask() {}

    static boolean sensitive(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("token")
                || k.contains("api-key") || k.contains("apikey") || k.contains("api_key");
    }

    /** 把敏感字段的值替换为 '******'。 */
    static String mask(String text) {
        return process(text, (path, value) -> isEmptyValue(value) ? null : "'" + MASK + "'");
    }

    /** 新文本中仍为占位符的敏感字段还原为原文本中的原始值。 */
    static String unmask(String submitted, String original) {
        Map<String, String> originals = new HashMap<>();
        process(original, (path, value) -> {
            originals.put(path, value);
            return null;
        });
        return process(submitted, (path, value) -> {
            String v = stripComment(value).trim();
            if (v.equals(MASK) || v.equals("'" + MASK + "'") || v.equals("\"" + MASK + "\"")) {
                String o = originals.get(path);
                return o == null ? "''" : o;
            }
            return null;
        });
    }

    private static boolean isEmptyValue(String value) {
        String v = stripComment(value).trim();
        return v.isEmpty() || v.equals("''") || v.equals("\"\"") || v.equals("|") || v.equals(">");
    }

    private static String stripComment(String value) {
        if (value.startsWith("'") || value.startsWith("\"")) return value;
        int i = value.indexOf(" #");
        return i >= 0 ? value.substring(0, i) : value;
    }

    /**
     * 遍历每个 "key: value" 行；replacer(路径, 原始值) 返回非 null 时替换该行的值。
     */
    private static String process(String text, BiFunction<String, String, String> replacer) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String[] lines = text.split("\n", -1);
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        StringBuilder out = new StringBuilder(text.length());
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String body = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            String cr = line.endsWith("\r") ? "\r" : "";
            Matcher m = KV.matcher(body);
            if (m.matches()) {
                int indent = m.group(1).length();
                while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                    indents.pop();
                    keys.pop();
                }
                String key = unquote(m.group(2).trim());
                indents.push(new int[]{indent});
                keys.push(key);
                String value = m.group(5);
                if (sensitive(key) && !value.isEmpty()) {
                    StringBuilder path = new StringBuilder();
                    var it = keys.descendingIterator();
                    while (it.hasNext()) {
                        if (path.length() > 0) path.append('.');
                        path.append(it.next());
                    }
                    String rep = replacer.apply(path.toString(), value);
                    if (rep != null) body = m.group(1) + m.group(2) + m.group(3) + (m.group(4).isEmpty() ? " " : m.group(4)) + rep;
                }
            }
            out.append(body).append(cr);
            if (li < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
