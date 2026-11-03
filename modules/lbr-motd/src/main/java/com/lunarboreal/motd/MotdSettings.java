package com.lunarboreal.motd;

import org.bukkit.configuration.ConfigurationSection;
import java.util.Map;
import java.util.Set;

record MotdSettings(boolean enabled, String line1, String line2, MaintenanceSettings maintenance) {
    MotdSettings(boolean enabled, String line1, String line2) {
        this(enabled, line1, line2, MaintenanceSettings.defaults());
    }
    static final int MAX_LINE_LENGTH = 512;
    static final String DEFAULT_LINE1 = "<gradient:#9cecff:#8ea8ff><bold>Lunazure 琉月·冰澜</bold></gradient>";
    static final String DEFAULT_LINE2 = "<gray>共赴冰澜之境 <dark_gray>｜ <aqua>{online}<gray>/{max} 人在线 <dark_gray>｜ <white>{version}";
    private static final Set<String> FIELDS = Set.of("enabled", "line1", "line2");

    MotdSettings {
        validateLine(line1, "第一行");
        validateLine(line2, "第二行");
        if (maintenance == null) throw new IllegalArgumentException("维护设置不得为空");
    }

    static MotdSettings load(ConfigurationSection config) {
        MotdSettings normal = from(Map.of("enabled", config.get("enabled", true),
                "line1", config.get("line1", DEFAULT_LINE1), "line2", config.get("line2", DEFAULT_LINE2)));
        return new MotdSettings(normal.enabled(), normal.line1(), normal.line2(), MaintenanceSettings.load(config));
    }

    MotdSettings withMaintenance(MaintenanceSettings replacement) {
        return new MotdSettings(enabled, line1, line2, replacement);
    }

    MotdSettings withNormal(MotdSettings replacement) {
        return new MotdSettings(replacement.enabled(), replacement.line1(), replacement.line2(), maintenance);
    }

    static MotdSettings from(Map<String, Object> body) {
        if (!body.keySet().equals(FIELDS)) throw new IllegalArgumentException("请提交完整的启用状态、第一行和第二行设置");
        if (!(body.get("enabled") instanceof Boolean enabled)) throw new IllegalArgumentException("启用状态须为布尔值");
        if (!(body.get("line1") instanceof String line1) || !(body.get("line2") instanceof String line2))
            throw new IllegalArgumentException("展示内容须为文本");
        return new MotdSettings(enabled, line1, line2);
    }

    static void validateLine(String value, String label) {
        if (value == null || value.length() > MAX_LINE_LENGTH)
            throw new IllegalArgumentException(label + "最多允许 " + MAX_LINE_LENGTH + " 个字符");
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || (c >= 0xE000 && c <= 0xE002)))
            throw new IllegalArgumentException(label + "不得包含换行或控制字符");
    }

    Map<String, Object> webValues() { return Map.of("enabled", enabled, "line1", line1, "line2", line2); }

    void apply(ConfigurationSection config) {
        config.set("enabled", enabled);
        config.set("line1", line1);
        config.set("line2", line2);
        maintenance.apply(config);
    }
}
