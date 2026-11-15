package com.lunarboreal.chat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅升级内置旧模板，保留服主自定义格式。 */
final class ChatFormatDefaults {
    static final String NAME = "<hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><white><displayname></white></click></hover>";
    private static final String LEGACY_PREFIX = "<aqua>[{world}]</aqua> {title} ";
    private static final String SUFFIX = "<aqua> » </aqua><white><message>";
    static final String DEFAULT = LEGACY_PREFIX + NAME + SUFFIX;
    private static final Map<String, String> OLD = Map.of(
            "default", "<aqua>[<white>{world}</white>]</aqua> " + NAME + "<aqua> » </aqua><white><message>",
            "admin", "<aqua>[<red>管理</red>]</aqua> <hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><aqua><displayname></aqua></click></hover><aqua> » </aqua><white><message>",
            "vip", "<aqua>[<gold>VIP</gold>]</aqua> <hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><yellow><displayname></yellow></click></hover><aqua> » </aqua><white><message>");

    /** 在合并新版默认值前保留旧自定义格式的显示设置。 */
    static boolean preserveLegacySettings(FileConfiguration config) {
        boolean changed = false, customChat = false, chatTitle = false;
        var groups = config.getConfigurationSection("format.groups");
        if (groups != null) {
            for (String group : groups.getKeys(false)) {
                if (groups.get(group + ".format") instanceof String template && !builtIn(group, template)) {
                    customChat = true;
                    chatTitle |= usesTitle(config, template);
                }
            }
        }
        Object legacyTab = config.get("tablist.name-format");
        Object tab = config.get("tablist.player-name-format");
        if (legacyTab instanceof String && !config.contains("tablist.player-name-format", true)) {
            config.set("tablist.player-name-format", legacyTab);
            tab = legacyTab;
            changed = true;
        }
        boolean customTab = legacyTab instanceof String || tab instanceof String template && !template.equals("{world} {player}");
        if ((customChat || customTab) && !config.contains("format.worlds.brackets", true)) {
            config.set("format.worlds.brackets", false);
            changed = true;
        }
        if (chatTitle && !config.contains("format.title.enabled", true)) {
            config.set("format.title.enabled", true);
            changed = true;
        }
        boolean tabTitle = tab instanceof String template && usesTitle(config, template);
        for (String path : List.of("tablist.header", "tablist.footer")) {
            for (String line : config.getStringList(path)) tabTitle |= usesTitle(config, line);
        }
        if (tabTitle && !config.contains("tablist.show-current-title", true)) {
            config.set("tablist.show-current-title", true);
            changed = true;
        }
        return changed;
    }

    private static boolean usesTitle(FileConfiguration config, String template) {
        String lower = template.toLowerCase(Locale.ROOT);
        for (String token : List.of("{title}", "<title>", "%playertitle_use%", "%lc_playertitle_current%", "%lc_titles_title%")) {
            if (lower.contains(token)) return true;
        }
        String configured = config.getString("format.title.placeholder", "");
        return configured.matches("%[A-Za-z0-9_.:-]+%") && lower.contains(configured.toLowerCase(Locale.ROOT));
    }

    private static boolean builtIn(String group, String template) {
        String old = OLD.get(group);
        return old != null && (old.equals(template) || (LEGACY_PREFIX + old).equals(template)
                || group.equals("default") && DEFAULT.equals(template));
    }

    static boolean migrate(FileConfiguration config) {
        boolean changed = false;
        // 首次加载时固定显示开关，避免回退值覆盖新版默认值。
        for (String path : List.of("format.worlds.brackets", "format.title.enabled", "tablist.show-current-title")) {
            if (!config.contains(path, true) && config.get(path) instanceof Boolean value) {
                config.set(path, value);
                changed = true;
            }
        }
        String world = config.getBoolean("format.worlds.brackets", false)
                ? "{world} " : "<aqua>[{world}]</aqua> ";
        for (var entry : OLD.entrySet()) {
            String path = "format.groups." + entry.getKey() + ".format";
            String current = config.getString(path);
            if (!builtIn(entry.getKey(), current)) continue;
            String format = world + switch (entry.getKey()) {
                case "admin" -> NAME.replace("<white><displayname></white>", "<aqua><displayname></aqua>");
                case "vip" -> "<gold>[VIP]</gold> " + NAME.replace("<white><displayname></white>", "<yellow><displayname></yellow>");
                default -> NAME;
            } + SUFFIX;
            config.set(path, format);
            changed = true;
        }
        return changed;
    }
}
