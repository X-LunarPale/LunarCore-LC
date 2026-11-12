package com.lunarboreal.qqauth;

import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Immutable message snapshot; replacements are literal and never recursively expanded. */
final class Messages {
    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final Map<String,String> DEFAULTS = defaults();
    private static final MiniMessage MM = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow(),
            StandardTags.font(), StandardTags.reset(), StandardTags.newline())).build();
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-orA-FK-OR])|&#([0-9a-fA-F]{6})");
    private static final String[] COLORS = {"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white"};
    private final Map<String,String> values;
    Messages() { values = DEFAULTS; }
    Messages(YamlConfiguration config) {
        Map<String,String> next = new HashMap<>(DEFAULTS);
        var section = config.getConfigurationSection("messages");
        if (section != null) for (String key : section.getKeys(true)) {
            if (section.isString(key)) next.put(key, section.getString(key));
            else if (section.isList(key)) next.put(key, String.join("\n", section.getStringList(key)));
        }
        values = Map.copyOf(next);
    }
    String text(String key, Object... arguments) {
        if (arguments.length % 2 != 0) throw new IllegalArgumentException("Message arguments must be key/value pairs");
        Map<String,String> replacements = new HashMap<>();
        for (int i=0; i<arguments.length; i+=2) replacements.put(String.valueOf(arguments[i]), String.valueOf(arguments[i+1]));
        Matcher matcher = TOKEN.matcher(values.getOrDefault(key, key));
        return matcher.replaceAll(match -> Matcher.quoteReplacement(replacements.getOrDefault(match.group(1), match.group())));
    }
    String formatted(String key, Object... arguments) {
        if (arguments.length % 2 != 0) throw new IllegalArgumentException("Message arguments must be key/value pairs");
        Map<String,String> replacements = new HashMap<>();
        for (int i=0; i<arguments.length; i+=2) {
            Object value = arguments[i + 1];
            replacements.put(String.valueOf(arguments[i]), value instanceof Component component
                    ? MM.serialize(component) : escape(String.valueOf(value)));
        }
        Matcher matcher = TOKEN.matcher(legacy(values.getOrDefault(key, key)));
        return matcher.replaceAll(match -> Matcher.quoteReplacement(replacements.getOrDefault(match.group(1), match.group())));
    }
    static Component render(String text) { return MM.deserialize(legacy(text)); }
    static String plain(String text) { return PlainTextComponentSerializer.plainText().serialize(render(text)); }
    private static String escape(String value) { return value.replace("<", "\\<").replace(">", "\\>"); }
    private static String legacy(String text) {
        Matcher matcher = LEGACY.matcher(text);
        return matcher.replaceAll(match -> {
            if (match.group(2) != null) return "<#" + match.group(2) + ">";
            char code = Character.toLowerCase(match.group(1).charAt(0));
            if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') return "<" + COLORS[Character.digit(code, 16)] + ">";
            return switch (code) { case 'k' -> "<obfuscated>"; case 'l' -> "<bold>"; case 'm' -> "<strikethrough>"; case 'n' -> "<underlined>"; case 'o' -> "<italic>"; case 'r' -> "<reset>"; default -> ""; };
        });
    }
    private static Map<String,String> defaults() {
        try (var stream = Messages.class.getResourceAsStream("/config.yml")) {
            if (stream == null) throw new IllegalStateException("Missing QQAuth config.yml");
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var section = config.getConfigurationSection("messages");
            Map<String,String> result = new HashMap<>();
            if (section != null) for (String key : section.getKeys(true)) if (section.isString(key)) result.put(key, section.getString(key));
            return Map.copyOf(result);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load QQAuth messages", ex); }
    }
}
