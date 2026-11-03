package com.lunarboreal.scoreboard;

import org.bukkit.configuration.ConfigurationSection;
import net.kyori.adventure.bossbar.BossBar;
import java.util.*;

record ScoreboardSettings(boolean enabled, Mode defaultMode, String title, List<String> lines,
                          List<String> frames, int updateTicks, int rotationTicks, boolean rotate,
                          BossBar.Color color, float progress, boolean adminEnabled, String adminTitle, List<String> adminLines) {
    enum Mode { SIDEBAR, ISLAND, OFF, DEFAULT;
        static Mode parse(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("模式必须为 sidebar、island、off 或 default"); }
        }
    }
    ScoreboardSettings {
        lines = List.copyOf(lines); frames = List.copyOf(frames); adminLines = List.copyOf(adminLines);
        validateText(adminTitle, 256, "管理员标题");
        if (adminLines.size() > 15) throw new IllegalArgumentException("管理员侧边栏最多15行");
        adminLines.forEach(line -> validateText(line, 512, "管理员侧边栏"));
        if (defaultMode == Mode.DEFAULT) throw new IllegalArgumentException("默认模式不能为 default");
        validateText(title, 256, "标题");
        if (lines.size() > 15) throw new IllegalArgumentException("侧边栏最多15行");
        if (frames.isEmpty() || frames.size() > 100) throw new IllegalArgumentException("顶部信息条需1–100帧");
        lines.forEach(line -> validateText(line, 512, "侧边栏")); frames.forEach(frame -> validateText(frame, 512, "信息条"));
        if (updateTicks < 10 || updateTicks > 1200) throw new IllegalArgumentException("刷新间隔须为10–1200 tick");
        if (rotationTicks < 20 || rotationTicks > 72000) throw new IllegalArgumentException("轮播间隔须为20–72000 tick");
        if (!Float.isFinite(progress) || progress < 0 || progress > 1) throw new IllegalArgumentException("进度须为0–1");
    }
    static void validateText(String text, int max, String field) {
        if (text == null || text.length() > max || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + "文本长度或控制字符无效");
    }
    static ScoreboardSettings load(ConfigurationSection cfg) {
        return from(Map.ofEntries(Map.entry("enabled", cfg.getBoolean("display-enabled", true)), Map.entry("defaultMode", cfg.getString("default-mode", "sidebar")),
                Map.entry("title", cfg.getString("title", "<aqua>LunarCore")), Map.entry("lines", cfg.getStringList("lines")), Map.entry("frames", cfg.getStringList("island-frames")),
                Map.entry("updateTicks", cfg.getInt("update-ticks", 20)), Map.entry("rotationTicks", cfg.getInt("rotation-ticks", 100)),
                Map.entry("rotate", cfg.getBoolean("rotate", true)), Map.entry("color", cfg.getString("boss-color", "BLUE")), Map.entry("progress", cfg.getDouble("boss-progress", 1)),
                Map.entry("adminEnabled", cfg.getBoolean("admin.enabled", true)), Map.entry("adminTitle", cfg.getString("admin.title", "<gold>服务器管理")), Map.entry("adminLines", cfg.getStringList("admin.lines"))));
    }
    static ScoreboardSettings from(Map<String, Object> map) {
        if (!(map.get("enabled") instanceof Boolean enabled) || !(map.get("rotate") instanceof Boolean rotate)) throw new IllegalArgumentException("开关须为布尔值");
        Object rawProgress = map.get("progress");
        if (!(rawProgress instanceof Number progress) || !Double.isFinite(progress.doubleValue()) || progress.doubleValue() < 0 || progress.doubleValue() > 1) throw new IllegalArgumentException("进度须为0–1");
        BossBar.Color color;
        try { color = BossBar.Color.valueOf(text(map, "color").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("信息条颜色无效"); }
        return new ScoreboardSettings(enabled, Mode.parse(text(map, "defaultMode")), text(map, "title"), list(map.get("lines")), list(map.get("frames")),
                integer(map.get("updateTicks")), integer(map.get("rotationTicks")), rotate, color, progress.floatValue(),
                map.getOrDefault("adminEnabled", true) instanceof Boolean enabledAdmin ? enabledAdmin : invalidAdmin(),
                (String) map.getOrDefault("adminTitle", "<gold>服务器管理"), list(map.getOrDefault("adminLines", defaultAdminLines())));
    }
    private static boolean invalidAdmin() { throw new IllegalArgumentException("管理员计分板开关须为布尔值"); }
    static List<String> defaultAdminLines() { return List.of("<gray>管理员 <white>{player}", "<gray>称号 <white>{title}", "<gray>余额 <gold>{balance}", "", "<gray>在线 <aqua>{online} <gray>/ {max_players}", "<gray>TPS <green>{tps} <gray>MSPT <white>{mspt}", "<gray>实体 <white>{entities} <gray>区块 <white>{chunks}", "<gray>内存 <white>{memory_used} / {memory_max} MB", "<gray>世界 <white>{world}", "<gray>延迟 <white>{ping}ms"); }
    static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue() || n.longValue() < Integer.MIN_VALUE || n.longValue() > Integer.MAX_VALUE) throw new IllegalArgumentException("间隔须为整数");
        return n.intValue();
    }
    private static String text(Map<String, Object> map, String key) { if (!(map.get(key) instanceof String s)) throw new IllegalArgumentException(key + "须为文本"); return s; }
    private static List<String> list(Object raw) {
        if (raw instanceof String text) return text.isEmpty() ? List.of() : Arrays.asList(text.split("\\r?\\n", -1));
        if (raw instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) return list.stream().map(String.class::cast).toList();
        throw new IllegalArgumentException("行内容须为文本列表");
    }
    Map<String, Object> webValues() {
        Map<String,Object> map = new LinkedHashMap<>(); map.put("enabled", enabled); map.put("defaultMode", defaultMode.name().toLowerCase(Locale.ROOT)); map.put("title", title);
        map.put("lines", String.join("\n", lines)); map.put("frames", String.join("\n", frames)); map.put("updateTicks", updateTicks); map.put("rotationTicks", rotationTicks); map.put("rotate", rotate); map.put("color", color.name()); map.put("progress", progress); map.put("adminEnabled", adminEnabled); map.put("adminTitle", adminTitle); map.put("adminLines", String.join("\n", adminLines)); return map;
    }
    void apply(ConfigurationSection cfg) {
        cfg.set("display-enabled", enabled); cfg.set("default-mode", defaultMode.name().toLowerCase(Locale.ROOT)); cfg.set("title", title); cfg.set("lines", lines); cfg.set("island-frames", frames);
        cfg.set("admin.enabled", adminEnabled); cfg.set("admin.title", adminTitle); cfg.set("admin.lines", adminLines);
        cfg.set("update-ticks", updateTicks); cfg.set("rotation-ticks", rotationTicks); cfg.set("rotate", rotate); cfg.set("boss-color", color.name()); cfg.set("boss-progress", progress);
    }
}
