package com.lunarboreal.journal;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record JournalSettings(boolean enabled, Set<String> worlds, int sampleSeconds,
                              boolean recordBiomes, boolean recordAdvancements,
                              boolean recordFishing, boolean notifyDiscoveries) {

    private static final Map<String, String> PATHS = Map.of(
            "enabled", "enabled", "worlds", "worlds", "sample_seconds", "sample-seconds",
            "record_biomes", "record-biomes", "record_advancements", "record-advancements",
            "record_fishing", "record-fishing", "notify_discoveries", "notify-discoveries");

    public JournalSettings {
        worlds = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(worlds));
        if (sampleSeconds < 2 || sampleSeconds > 60) throw new IllegalArgumentException("采样间隔必须为 2–60 秒的整数");
        if (worlds.size() > 64) throw new IllegalArgumentException("世界白名单最多 64 项");
        for (String world : worlds) validateWorld(world);
    }

    public static JournalSettings defaults() {
        return new JournalSettings(true, new LinkedHashSet<>(List.of("world", "world_nether", "world_the_end")),
                5, true, true, true, false);
    }

    public static JournalSettings fromYaml(YamlConfiguration yaml) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (var path : PATHS.entrySet()) {
            if (yaml.contains(path.getValue())) {
                Object value = yaml.get(path.getValue());
                if ("worlds".equals(path.getKey())) {
                    if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
                        throw new IllegalArgumentException("世界白名单必须是文本列表");
                    value = String.join("\n", ((List<?>) value).stream().map(String.class::cast).toList());
                }
                body.put(path.getKey(), value);
            }
        }
        return defaults().updated(body);
    }

    public JournalSettings updated(Map<String, Object> body) {
        if (body == null || body.keySet().stream().anyMatch(key -> !PATHS.containsKey(key)))
            throw new IllegalArgumentException("配置包含未知字段");
        return new JournalSettings(
                bool(body, "enabled", enabled),
                body.containsKey("worlds") ? parseWorlds(body.get("worlds")) : worlds,
                body.containsKey("sample_seconds") ? seconds(body.get("sample_seconds")) : sampleSeconds,
                bool(body, "record_biomes", recordBiomes),
                bool(body, "record_advancements", recordAdvancements),
                bool(body, "record_fishing", recordFishing),
                bool(body, "notify_discoveries", notifyDiscoveries));
    }

    public Map<String, Object> toWeb() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("worlds", String.join("\n", worlds));
        values.put("sample_seconds", sampleSeconds);
        values.put("record_biomes", recordBiomes);
        values.put("record_advancements", recordAdvancements);
        values.put("record_fishing", recordFishing);
        values.put("notify_discoveries", notifyDiscoveries);
        return values;
    }

    public YamlConfiguration toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        toWeb().forEach((key, value) -> yaml.set(PATHS.get(key),
                "worlds".equals(key) ? List.copyOf(worlds) : value));
        return yaml;
    }

    private static boolean bool(Map<String, Object> body, String key, boolean previous) {
        if (!body.containsKey(key)) return previous;
        if (!(body.get(key) instanceof Boolean value))
            throw new IllegalArgumentException(key + " 必须为布尔值");
        return value;
    }

    private static int seconds(Object raw) {
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < 2 || number.doubleValue() > 60)
            throw new IllegalArgumentException("采样间隔必须为 2–60 秒的整数");
        return number.intValue();
    }

    private static Set<String> parseWorlds(Object raw) {
        if (!(raw instanceof String text) || text.length() > 8192)
            throw new IllegalArgumentException("世界白名单必须是逐行文本，最多 8192 字符");
        Set<String> worlds = new LinkedHashSet<>();
        for (String line : text.split("\\R", -1)) {
            if (line.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("世界名称不能包含控制字符");
            String world = line.trim();
            if (world.isEmpty()) continue;
            validateWorld(world);
            worlds.add(world);
        }
        return worlds;
    }

    private static void validateWorld(String world) {
        if (world == null || world.isBlank() || world.length() > 128
                || world.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("世界名称不能为空、包含控制字符或超过 128 字符");
    }
}
