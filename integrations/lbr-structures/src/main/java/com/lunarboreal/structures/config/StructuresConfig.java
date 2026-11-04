package com.lunarboreal.structures.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** World keys are namespaced dimension keys, so a renamed display name does not alter its policy. */
public record StructuresConfig(long revision, GenerationPolicy defaults, Map<String, Map<String, Boolean>> worlds) {
    public static final int SCHEMA = 1;

    public StructuresConfig {
        if (revision < 0 || revision > 9007199254740991L) throw new IllegalArgumentException("配置修订号无效");
        var copy = new LinkedHashMap<String, Map<String, Boolean>>();
        if (worlds.size() > 1024) throw new IllegalArgumentException("世界配置过多");
        worlds.forEach((key, value) -> {
            if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || key.length() > 256 || key.contains(".."))
                throw new IllegalArgumentException("无效世界标识：" + key);
            value.forEach((field, enabled) -> {
                if (!field.equals("overwriteExisting")) StructureFamily.require(field);
                if (enabled == null) throw new IllegalArgumentException("开关必须是布尔值");
            });
            if (!value.isEmpty()) copy.put(key, Map.copyOf(value));
        });
        worlds = Map.copyOf(copy);
    }

    public static StructuresConfig initial() { return new StructuresConfig(0, GenerationPolicy.vanilla(), Map.of()); }

    public GenerationPolicy forWorld(String key) {
        var override = worlds.getOrDefault(key, Map.of());
        var flags = new java.util.EnumMap<StructureFamily, Boolean>(StructureFamily.class);
        for (var f : StructureFamily.values()) flags.put(f, override.getOrDefault(f.id, defaults.enabled(f)));
        return new GenerationPolicy(flags, override.getOrDefault("overwriteExisting", defaults.overwriteExisting()));
    }

    public Map<String, Object> map() {
        return Map.of("schema", SCHEMA, "revision", revision, "defaults", defaults.map(), "worlds", worlds);
    }

    public boolean sameRules(StructuresConfig other) {
        return defaults.equals(other.defaults) && worlds.equals(other.worlds);
    }

    public static StructuresConfig parse(Map<String, Object> body) {
        exactKeys(body, "schema", "revision", "defaults", "worlds");
        if (integer(body.get("schema")) != SCHEMA) throw new IllegalArgumentException("不支持的配置版本");
        var worlds = new LinkedHashMap<String, Map<String, Boolean>>();
        object(body.get("worlds")).forEach((key, value) -> {
            var flags = new LinkedHashMap<String, Boolean>();
            object(value).forEach((field, enabled) -> flags.put(field, bool(enabled)));
            worlds.put(key, flags);
        });
        return new StructuresConfig(integer(body.get("revision")), GenerationPolicy.parse(object(body.get("defaults"))), worlds);
    }

    public static long integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                || n.longValue() < 0 || n.doubleValue() > 9007199254740991d) throw new IllegalArgumentException("需要非负安全整数");
        return n.longValue();
    }

    public static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException("开关必须是布尔值");
    }

    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("需要配置对象");
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, entry) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException("字段名无效");
            copy.put(text, entry);
        });
        return copy;
    }

    public static void exactKeys(Map<String, Object> value, String... keys) {
        if (!value.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("配置字段缺失或包含未知字段");
    }
}
