package com.lunarboreal.chat;

import com.lunarboreal.api.LBR;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 聊天格式化工具。 */
final class ChatFormatter {

    private record FormatGroup(String id, String permission, int priority, String format) {}

    static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s]+");
    private static final Pattern PERSONAL_PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_.:-]+%");

    private volatile List<FormatGroup> groups = List.of();
    private String itemPlaceholder = "[item]";
    private boolean mentions;
    private String mentionColor = "<gold>";

    void load(ConfigurationSection format) {
        List<FormatGroup> list = new ArrayList<>();
        ConfigurationSection gs = format == null ? null : format.getConfigurationSection("groups");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(id);
                if (g == null) continue;
                list.add(new FormatGroup(id, g.getString("permission", ""), g.getInt("priority", 0),
                        g.getString("format", "<displayname>: <message>")));
            }
        }
        list.sort(Comparator.comparingInt(FormatGroup::priority).reversed());
        if (list.isEmpty()) list.add(new FormatGroup("default", "", 0, "<displayname><gray>: <white><message>"));
        groups = list;
        if (format != null) {
            itemPlaceholder = format.getString("item-placeholder", "[item]");
            mentions = format.getBoolean("mentions.enabled", true);
            mentionColor = format.getString("mentions.color", "<gold>");
        }
    }

    String formatFor(Player player) {
        for (FormatGroup g : groups) {
            if (g.permission().isEmpty() || player.hasPermission(g.permission())) return g.format();
        }
        return groups.getLast().format();
    }

    String defaultFormat() {
        for (FormatGroup group : groups) if (group.id().equals("default")) return group.format();
        return groups.getLast().format();
    }

    /** 替换文本变量与 %lc_..% 变量，再解析 MiniMessage。 */
    static Component render(OfflinePlayer context, String template, Map<String, String> vars, TagResolver... resolvers) {
        String text = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        try {
            text = LBR.get().placeholders().apply(context, text);
        } catch (RuntimeException ignored) {
            // 变量服务不可用时忽略
        }
        return MM.deserialize(text, resolvers);
    }

    /** 把玩家输入的文本转为组件（按权限决定颜色、链接、物品展示）。 */
    Component playerText(Player player, String raw) {
        boolean color = player.hasPermission("lbr.chat.color");
        Component c = color ? LEGACY.deserialize(raw) : Component.text(raw);
        if (player.hasPermission("lbr.chat.url")) {
            c = c.replaceText(TextReplacementConfig.builder().match(URL)
                    .replacement((m, b) -> b.clickEvent(ClickEvent.openUrl(m.group()))
                            .hoverEvent(HoverEvent.showText(Component.text("打开链接"))).decoration(net.kyori.adventure.text.format.TextDecoration.UNDERLINED, true))
                    .build());
        }
        if (!itemPlaceholder.isEmpty() && raw.contains(itemPlaceholder) && player.hasPermission("lbr.chat.item")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!item.getType().isAir()) {
                Component itemComp = Component.text("[").append(item.effectiveName()).append(Component.text(" x" + item.getAmount() + "]"))
                        .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                        .hoverEvent(item.asHoverEvent());
                c = c.replaceText(TextReplacementConfig.builder().matchLiteral(itemPlaceholder).replacement(itemComp).once().build());
            }
        }
        return c;
    }

    /** @玩家 高亮，返回被提及的在线玩家。 */
    Component highlightMentions(Component message, String plain, List<Player> mentioned) {
        if (!mentions || !plain.contains("@")) return message;
        Component result = message;
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            String tag = "@" + p.getName();
            if (plain.toLowerCase().contains(tag.toLowerCase())) {
                mentioned.add(p);
                Component hl = MM.deserialize(mentionColor + MM.escapeTags(tag));
                result = result.replaceText(TextReplacementConfig.builder()
                        .match(Pattern.compile(Pattern.quote(tag), Pattern.CASE_INSENSITIVE)).replacement(hl).build());
            }
        }
        return result;
    }

    Component formatChat(Player player, Component message) {
        return render(player, formatFor(player),
                Map.of("player", player.getName(), "world", player.getWorld().getName()),
                Placeholder.component("displayname", player.displayName()),
                Placeholder.component("message", message));
    }

    Component formatVirtualChat(String worldName, String name, Component message) {
        // A virtual speaker must not inherit the triggering player's placeholder values.
        String template = PERSONAL_PLACEHOLDER.matcher(defaultFormat()).replaceAll("")
                .replace("{player}", MM.escapeTags(name))
                .replace("{world}", MM.escapeTags(worldName));
        return MM.deserialize(template,
                Placeholder.component("displayname", Component.text(name)),
                Placeholder.component("message", message));
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** 仅允许颜色/装饰/渐变/彩虹/重置的 MiniMessage（用于玩家可控文本，如昵称，防止 click/hover/insert/selector 注入）。 */
    static final MiniMessage SAFE_MM = MiniMessage.builder()
            .tags(TagResolver.resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset()))
            .build();

    /** 玩家昵称：& 颜色码或受限 MiniMessage。 */
    static Component nick(String raw) {
        if (raw == null) return Component.empty();
        if (raw.indexOf('<') >= 0 && raw.indexOf('>') > raw.indexOf('<')) return SAFE_MM.deserialize(raw);
        return LEGACY.deserialize(raw);
    }

    static Component legacyOrMini(String raw) {
        if (raw == null) return Component.empty();
        if (raw.indexOf('<') >= 0 && raw.indexOf('>') > raw.indexOf('<')) return MM.deserialize(raw);
        return LEGACY.deserialize(raw);
    }
}
