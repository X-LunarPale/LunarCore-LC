package com.lunarboreal.chat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

/** 仅升级内置旧模板，保留服主自定义格式。 */
final class ChatFormatDefaults {
    static final String NAME = "<hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><white><displayname></white></click></hover>";
    static final String DEFAULT = "<aqua>[{world}]</aqua> {title} " + NAME + "<aqua> » </aqua><white><message>";
    private static final Map<String, String> OLD = Map.of(
            "default", "<aqua>[<white>{world}</white>]</aqua> " + NAME + "<aqua> » </aqua><white><message>",
            "admin", "<aqua>[<red>管理</red>]</aqua> <hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><aqua><displayname></aqua></click></hover><aqua> » </aqua><white><message>",
            "vip", "<aqua>[<gold>VIP</gold>]</aqua> <hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '><yellow><displayname></yellow></click></hover><aqua> » </aqua><white><message>");

    static boolean migrate(FileConfiguration config) {
        boolean changed = false;
        for (var entry : OLD.entrySet()) {
            String path = "format.groups." + entry.getKey() + ".format";
            if (!entry.getValue().equals(config.getString(path))) continue;
            String format = entry.getKey().equals("default") ? DEFAULT
                    : "<aqua>[{world}]</aqua> {title} " + entry.getValue();
            config.set(path, format);
            changed = true;
        }
        return changed;
    }
}
