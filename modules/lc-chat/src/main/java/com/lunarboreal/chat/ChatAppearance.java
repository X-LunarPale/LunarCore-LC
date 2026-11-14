package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 世界显示名与称号缓存；不修改世界目录或存档标识。 */
final class ChatAppearance {
    static final String TITLE_PLACEHOLDER = "%playerTitle_use%";
    static final String DEFAULT_COLOR = "#55ffff";
    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_.:-]+%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    record WorldLabel(String world, String name, String color) {
        Map<String, String> view() { return Map.of("world", world, "name", name, "color", color); }
    }
    private record Settings(Map<String, WorldLabel> worlds, String color, boolean title, String placeholder) {}
    private volatile Settings settings = new Settings(Map.of(), DEFAULT_COLOR, true, TITLE_PLACEHOLDER);
    private final Map<UUID, Component> titles = new ConcurrentHashMap<>();

    void load(ConfigurationSection format) {
        if (format == null) return;
        List<WorldLabel> aliases = labels(format.getMapList("worlds.aliases"));
        Map<String, WorldLabel> worlds = new LinkedHashMap<>();
        for (WorldLabel alias : aliases) worlds.put(alias.world(), alias);
        String placeholder = format.getString("title.placeholder", TITLE_PLACEHOLDER);
        if (placeholder == null || !PLACEHOLDER.matcher(placeholder).matches()) placeholder = TITLE_PLACEHOLDER;
        settings = new Settings(Map.copyOf(worlds), format.getString("worlds.default-color", DEFAULT_COLOR),
                format.getBoolean("title.enabled", true), placeholder);
        titles.clear();
    }

    static List<WorldLabel> labels(List<? extends Map<?, ?>> rows) {
        List<WorldLabel> result = new ArrayList<>();
        for (Map<?, ?> row : rows) {
            String world = Objects.toString(row.get("world"), "").strip();
            if (world.isEmpty()) continue;
            String name = Objects.toString(row.get("name"), world).strip();
            String color = Objects.toString(row.get("color"), DEFAULT_COLOR).strip();
            result.add(new WorldLabel(world, name.isEmpty() ? world : name, color));
        }
        return List.copyOf(result);
    }

    static TextColor color(String value) {
        if (value == null) return null;
        return value.startsWith("#") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value);
    }

    Component world(String world) {
        Settings current = settings;
        WorldLabel label = current.worlds().get(world);
        Component result = Component.text(label == null ? world : label.name());
        TextColor color = color(label == null ? current.color() : label.color());
        return color == null ? result : result.color(color);
    }

    List<Map<String, String>> aliases() {
        return settings.worlds().values().stream().sorted(java.util.Comparator.comparing(WorldLabel::world))
                .map(WorldLabel::view).toList();
    }

    boolean showChatTitle() { return settings.title(); }
    String titlePlaceholder() { return settings.placeholder(); }
    Component cachedTitle(Player player) { return titles.getOrDefault(player.getUniqueId(), Component.empty()); }
    void forget(UUID player) { titles.remove(player); }
    void clear() { titles.clear(); }

    void refresh(Player player, Function<String, String> resolve) {
        titles.put(player.getUniqueId(), resolveTitle(settings.placeholder(), resolve));
    }

    static Component resolveTitle(String configured, Function<String, String> resolve) {
        for (String token : List.of(Objects.toString(configured, TITLE_PLACEHOLDER), "%lc_playertitle_current%", "%lc_titles_title%")) {
            if (token.isBlank()) continue;
            String raw;
            try { raw = resolve.apply(token); } catch (RuntimeException ex) { continue; }
            if (raw == null || raw.equals(token) || PLACEHOLDER.matcher(raw).find()) continue;
            // A resolved empty title means the player has no equipped title.
            return token.equals("%lc_titles_title%") ? TabList.renderTitle(raw) : vendorTitle(raw);
        }
        return Component.empty();
    }

    static Component vendorTitle(String raw) {
        if (raw == null || raw.isBlank() || PLACEHOLDER.matcher(raw).find()) return Component.empty();
        Component parsed = LEGACY.deserialize(raw.replace('§', '&'));
        String plain = ChatFormatter.plain(parsed).strip();
        return List.of("未佩戴", "未装备", "无称号", "none").contains(plain) || plain.isBlank()
                ? Component.empty() : parsed;
    }
}
