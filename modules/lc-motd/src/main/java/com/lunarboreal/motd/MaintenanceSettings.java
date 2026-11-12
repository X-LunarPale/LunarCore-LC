package com.lunarboreal.motd;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validated local configuration; identity validation never performs profile/network lookups. */
record MaintenanceSettings(boolean enabled, boolean allowJoin, boolean kickOnline, List<String> whitelist,
                           String joinMessage, String kickMessage, String line1, String line2) {
    static final String DEFAULT_JOIN_MESSAGE = "<yellow>服务器正在维护，暂时无法加入。请稍后重试。";
    static final String DEFAULT_KICK_MESSAGE = "<yellow>服务器已进入维护模式，您的连接已断开。请稍后重试。";
    static final String DEFAULT_LINE1 = "<gold><bold>服务器维护中</bold></gold>";
    static final String DEFAULT_LINE2 = "<white>服务器正在维护，请稍后重试。";
    static final int MAX_WHITELIST_ENTRIES = 500;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern COMPACT_UUID = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Set<String> FIELDS = Set.of("enabled", "allowJoin", "kickOnline", "whitelist", "joinMessage", "kickMessage", "line1", "line2");

    MaintenanceSettings {
        if (whitelist == null || whitelist.size() > MAX_WHITELIST_ENTRIES)
            throw new IllegalArgumentException("维护白名单最多允许 " + MAX_WHITELIST_ENTRIES + " 项");
        var normalized = new LinkedHashSet<String>();
        for (String entry : whitelist) normalized.add(normalizeIdentity(entry));
        whitelist = List.copyOf(normalized);
        MotdSettings.validateLine(joinMessage, "维护入服提示");
        MotdSettings.validateLine(kickMessage, "维护踢出提示");
        MotdSettings.validateLine(line1, "维护简介第一行");
        MotdSettings.validateLine(line2, "维护简介第二行");
    }

    static MaintenanceSettings defaults() {
        return new MaintenanceSettings(false, false, false, List.of(), DEFAULT_JOIN_MESSAGE, DEFAULT_KICK_MESSAGE,
                DEFAULT_LINE1, DEFAULT_LINE2);
    }

    static MaintenanceSettings load(ConfigurationSection config) {
        if (config.contains("maintenance") && !config.isConfigurationSection("maintenance"))
            throw new IllegalArgumentException("维护配置须为配置节");
        var values = new LinkedHashMap<String, Object>();
        values.put("enabled", config.get("maintenance.enabled", false));
        values.put("allowJoin", config.get("maintenance.allow-join", false));
        values.put("kickOnline", config.get("maintenance.kick-online", false));
        values.put("whitelist", config.get("maintenance.whitelist", List.of()));
        values.put("joinMessage", config.get("maintenance.join-message", DEFAULT_JOIN_MESSAGE));
        values.put("kickMessage", config.get("maintenance.kick-message", DEFAULT_KICK_MESSAGE));
        values.put("line1", config.get("maintenance.line1", DEFAULT_LINE1));
        values.put("line2", config.get("maintenance.line2", DEFAULT_LINE2));
        return parse(values, false);
    }

    static MaintenanceSettings from(Map<String, Object> body) { return parse(body, true); }

    private static MaintenanceSettings parse(Map<String, Object> body, boolean web) {
        if (body == null || !body.keySet().equals(FIELDS)) throw new IllegalArgumentException("请提交完整的维护设置");
        List<String> entries = new ArrayList<>();
        Object raw = body.get("whitelist");
        if (web) {
            if (!(raw instanceof String text) || text.length() > 20000)
                throw new IllegalArgumentException("维护白名单须为文本，最多允许 20000 个字符");
            for (String entry : text.split("\\r\\n|\\n|\\r", -1)) {
                if (!entry.isBlank()) entries.add(entry);
            }
            if (entries.size() > MAX_WHITELIST_ENTRIES)
                throw new IllegalArgumentException("维护白名单最多允许 " + MAX_WHITELIST_ENTRIES + " 项");
        } else {
            if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("维护白名单须为名称或 UUID 列表");
            for (Object entry : list) {
                if (!(entry instanceof String text)) throw new IllegalArgumentException("维护白名单各项须为文本");
                entries.add(text);
            }
        }
        return new MaintenanceSettings(bool(body, "enabled"), bool(body, "allowJoin"), bool(body, "kickOnline"), entries,
                text(body, "joinMessage"), text(body, "kickMessage"), text(body, "line1"), text(body, "line2"));
    }

    private static boolean bool(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof Boolean value)) throw new IllegalArgumentException("维护开关须为布尔值：" + key);
        return value;
    }

    private static String text(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof String value)) throw new IllegalArgumentException("维护提示及简介须为文本");
        return value;
    }

    static String normalizeIdentity(String value) {
        if (value == null) throw new IllegalArgumentException("维护白名单不得包含空项");
        String entry = value.strip();
        if (NAME.matcher(entry).matches()) return entry.toLowerCase(Locale.ROOT);
        if (COMPACT_UUID.matcher(entry).matches()) {
            entry = entry.substring(0, 8) + "-" + entry.substring(8, 12) + "-" + entry.substring(12, 16) + "-"
                    + entry.substring(16, 20) + "-" + entry.substring(20);
        }
        if (UUID_TEXT.matcher(entry).matches()) return UUID.fromString(entry).toString();
        throw new IllegalArgumentException("维护白名单仅接受 3–16 位英文字母、数字、下划线名称，或完整 UUID");
    }

    Map<String, Object> webValues() {
        return Map.of("enabled", enabled, "allowJoin", allowJoin, "kickOnline", kickOnline,
                "whitelist", String.join("\n", whitelist), "joinMessage", joinMessage, "kickMessage", kickMessage,
                "line1", line1, "line2", line2);
    }

    void apply(ConfigurationSection config) {
        config.set("maintenance.enabled", enabled);
        config.set("maintenance.allow-join", allowJoin);
        config.set("maintenance.kick-online", kickOnline);
        config.set("maintenance.whitelist", whitelist);
        config.set("maintenance.join-message", joinMessage);
        config.set("maintenance.kick-message", kickMessage);
        config.set("maintenance.line1", line1);
        config.set("maintenance.line2", line2);
    }
}
