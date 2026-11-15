package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ChatFormatDefaultsTest {
    private static final String NAME = "<hover:show_text:'<white>点击私聊 {player}'><click:suggest_command:'/msg {player} '>";
    private static final String TAIL = "</click></hover><aqua> » </aqua><white><message>";
    private static final String PREFIX = "<aqua>[{world}]</aqua> {title} ";
    private static final Map<String, String> LEGACY = Map.of(
            "default", "<aqua>[<white>{world}</white>]</aqua> " + NAME + "<white><displayname></white>" + TAIL,
            "admin", "<aqua>[<red>管理</red>]</aqua> " + NAME + "<aqua><displayname></aqua>" + TAIL,
            "vip", "<aqua>[<gold>VIP</gold>]</aqua> " + NAME + "<yellow><displayname></yellow>" + TAIL);

    @Test void historicalFormatsKeepOneWorldLabelAndRemoveTitleAndManagementLabels() {
        for (boolean brackets : List.of(false, true)) {
            for (var group : LEGACY.entrySet()) {
                for (String previous : List.of(group.getValue(), PREFIX + group.getValue())) {
                    var config = config(brackets, group.getKey(), previous);
                    assertTrue(ChatFormatDefaults.migrate(config));
                    Component result = render(config, group.getKey());
                    assertEquals("[生存] " + (group.getKey().equals("vip") ? "[VIP] " : "") + "Steve » 你好",
                            ChatFormatter.plain(result));
                    String migrated = config.getString(path(group.getKey()));
                    assertNotNull(migrated);
                    assertFalse(migrated.contains("{title}"));
                    if (brackets) assertTrue(migrated.startsWith("{world} "));
                    else assertTrue(migrated.startsWith("<aqua>[{world}]</aqua> "));
                    assertFalse(ChatFormatDefaults.migrate(config));
                }
            }
        }
    }

    @Test void legacyDefaultWithTitleUpgradesWithoutReintroducingTitle() {
        var config = config(true, "default", ChatFormatDefaults.DEFAULT);
        assertTrue(ChatFormatDefaults.migrate(config));
        assertEquals("[生存] Steve » 你好", ChatFormatter.plain(render(config, "default")));
        assertFalse(config.getString(path("default")).contains("{title}"));
        assertFalse(ChatFormatDefaults.migrate(config));
    }

    @Test void migratedNamesAndVipLabelKeepTheirGroupColorsAndPrivateMessageAction() {
        var admin = config(true, "admin", PREFIX + LEGACY.get("admin"));
        var vip = config(true, "vip", LEGACY.get("vip"));
        assertTrue(ChatFormatDefaults.migrate(admin));
        assertTrue(ChatFormatDefaults.migrate(vip));
        Component adminName = text(render(admin, "admin"), "Steve");
        assertEquals(NamedTextColor.AQUA, adminName.color());
        assertEquals(ClickEvent.suggestCommand("/msg Steve "), adminName.clickEvent());
        assertNotNull(adminName.hoverEvent());
        Component vipLine = render(vip, "vip");
        assertEquals(NamedTextColor.GOLD, text(vipLine, "[VIP]").color());
        assertEquals(NamedTextColor.YELLOW, text(vipLine, "Steve").color());
    }

    @Test void worldAliasKeepsItsConfiguredColor() {
        var config = config(true, "default", LEGACY.get("default"));
        assertTrue(ChatFormatDefaults.migrate(config));
        assertEquals(TextColor.fromHexString("#55ff55"), text(render(config, "default"), "[生存]").color());
    }

    @Test void customTemplatesAndGroupSettingsRemainUnchanged() {
        var config = new YamlConfiguration();
        config.set("format.worlds.brackets", true);
        config.set("format.groups.admin.permission", "custom.admin");
        config.set("format.groups.admin.priority", 27);
        List<String> custom = List.of(
                LEGACY.get("admin") + " ",
                PREFIX + LEGACY.get("vip") + "<red>!",
                ChatFormatDefaults.DEFAULT.replace("{title}", "{custom_title}"),
                "<red>[管理员]</red> {world} {title} <displayname>: <message>",
                "{world} <aqua><displayname></aqua>: <message>");
        for (String template : custom) {
            config.set(path("admin"), template);
            assertFalse(ChatFormatDefaults.migrate(config));
            assertEquals(template, config.getString(path("admin")));
        }
        config.set(path("custom"), LEGACY.get("admin"));
        assertFalse(ChatFormatDefaults.migrate(config));
        assertEquals(LEGACY.get("admin"), config.getString(path("custom")));
        assertEquals("custom.admin", config.getString("format.groups.admin.permission"));
        assertEquals(27, config.getInt("format.groups.admin.priority"));
    }

    @Test void missingBracketSettingRetainsExternalWorldBrackets() {
        var config = new YamlConfiguration();
        config.set(path("default"), LEGACY.get("default"));
        assertTrue(ChatFormatDefaults.migrate(config));
        assertTrue(config.getString(path("default")).startsWith("<aqua>[{world}]</aqua> "));
        assertEquals("[生存] Steve » 你好", ChatFormatter.plain(render(config, "default")));
    }

    @Test void customFormatsKeepTheirOldDisplayAfterDefaultMergeAndDiskReload() throws Exception {
        var config = new YamlConfiguration();
        String chat = "<green>[{world}]</green> {title} <displayname>: <message>";
        String tab = "<gold>[{world}]</gold> {title} {player}";
        config.set(path("default"), chat);
        config.set("tablist.name-format", tab);
        config.set("format.worlds.aliases", List.of(Map.of("world", "survival", "name", "生存", "color", "#55ff55")));
        assertTrue(ChatFormatDefaults.preserveLegacySettings(config));
        assertFalse(config.getBoolean("format.worlds.brackets"));
        assertTrue(config.getBoolean("format.title.enabled"));
        assertTrue(config.getBoolean("tablist.show-current-title"));
        config.setDefaults(resourceDefaults());
        config.options().copyDefaults(true);
        var reloaded = new YamlConfiguration();
        reloaded.loadFromString(config.saveToString());
        assertFalse(ChatFormatDefaults.preserveLegacySettings(reloaded));
        assertFalse(ChatFormatDefaults.migrate(reloaded));
        assertEquals(chat, reloaded.getString(path("default")));
        assertEquals(tab, TabList.nameFormat(reloaded));
        var formatter = new ChatFormatter();
        formatter.load(reloaded.getConfigurationSection("format"));
        assertEquals("[生存] [旅行者] Steve: 你好", ChatFormatter.plain(formatter.formatLine(null,
                chat, "survival", "Steve", Component.text("Steve"), Component.text("[旅行者]"), Component.text("你好"))));
        assertEquals("[生存] [旅行者] Steve", ChatFormatter.plain(formatter.formatLine(null,
                TabList.nameFormat(reloaded), "survival", "Steve", Component.text("Steve"),
                Component.text("[旅行者]"), Component.empty())));
    }

    @Test void explicitAppearanceChoicesAndCustomTemplatesAreNotOverridden() {
        for (boolean enabled : List.of(false, true)) {
            var config = config(enabled, "default", "[{world}] {title} <displayname>: <message>");
            config.set("format.title.enabled", enabled);
            config.set("tablist.show-current-title", enabled);
            config.set("tablist.name-format", "{title} {player}");
            config.set("tablist.player-name-format", "<title> <displayname>");
            String before = config.saveToString();
            assertFalse(ChatFormatDefaults.preserveLegacySettings(config));
            assertEquals(before, config.saveToString());
        }
    }

    @Test void onlyActualTitleTokensPreserveMissingTitleSwitches() {
        for (String token : List.of("{title}", "<title>", "%playerTitle_use%", "%lc_playertitle_current%",
                "%lc_titles_title%", "%custom_title%")) {
            var config = new YamlConfiguration();
            config.set("format.title.placeholder", "%custom_title%");
            config.set(path("custom"), token + " <displayname>: <message>");
            config.set("tablist.name-format", token + " {player}");
            assertTrue(ChatFormatDefaults.preserveLegacySettings(config));
            assertTrue(config.getBoolean("format.title.enabled"), token);
            assertTrue(config.getBoolean("tablist.show-current-title"), token);
            assertFalse(ChatFormatDefaults.preserveLegacySettings(config));
        }
        var withoutTitle = new YamlConfiguration();
        withoutTitle.set(path("default"), "[{world}] <displayname>: <message>");
        withoutTitle.set("tablist.name-format", "{player}");
        assertTrue(ChatFormatDefaults.preserveLegacySettings(withoutTitle));
        assertFalse(withoutTitle.contains("format.title.enabled", true));
        assertFalse(withoutTitle.contains("tablist.show-current-title", true));
    }

    @Test void blankLegacyTabNamesRemainBlankAfterNewDefaultsAreSaved() throws Exception {
        for (String blank : List.of("", "   ")) {
            var config = new YamlConfiguration();
            config.set("tablist.name-format", blank);
            assertTrue(ChatFormatDefaults.preserveLegacySettings(config));
            config.setDefaults(resourceDefaults());
            config.options().copyDefaults(true);
            var reloaded = new YamlConfiguration();
            reloaded.loadFromString(config.saveToString());
            assertEquals(blank, TabList.nameFormat(reloaded));
            assertFalse(ChatFormatDefaults.preserveLegacySettings(reloaded));
        }
    }

    @Test void emptyAndFreshConfigurationsRetainTheNewDefaults() {
        var empty = new YamlConfiguration();
        assertFalse(ChatFormatDefaults.preserveLegacySettings(empty));
        assertTrue(empty.getKeys(true).isEmpty());
        var fresh = resourceDefaults();
        String before = fresh.saveToString();
        assertFalse(ChatFormatDefaults.preserveLegacySettings(fresh));
        assertEquals(before, fresh.saveToString());
        assertTrue(fresh.getBoolean("format.worlds.brackets"));
        assertFalse(fresh.getBoolean("format.title.enabled"));
        assertFalse(fresh.getBoolean("tablist.show-current-title"));
    }

    @Test void builtInLegacyFormatsUseNewDefaultsRatherThanPreservingRemovedLabels() {
        for (String template : List.of(LEGACY.get("default"), PREFIX + LEGACY.get("default"), ChatFormatDefaults.DEFAULT)) {
            var config = new YamlConfiguration();
            config.set(path("default"), template);
            assertFalse(ChatFormatDefaults.preserveLegacySettings(config));
            assertFalse(config.contains("format.worlds.brackets", true));
            assertFalse(config.contains("format.title.enabled", true));
            config.setDefaults(resourceDefaults());
            config.options().copyDefaults(true);
            assertTrue(ChatFormatDefaults.migrate(config));
            assertTrue(config.getBoolean("format.worlds.brackets", false));
            assertFalse(config.getBoolean("format.title.enabled", true));
            assertFalse(config.getBoolean("tablist.show-current-title", true));
            assertFalse(config.getString(path("default")).contains("{title}"));
            assertTrue(config.getString(path("default")).startsWith("{world} "));
            var appearance = new ChatAppearance();
            appearance.load(config.getConfigurationSection("format"));
            assertEquals("[survival]", ChatFormatter.plain(appearance.world("survival")));
            assertFalse(ChatFormatDefaults.migrate(config));
        }
    }

    @Test void tabHeadersAndFootersCanPreserveTheirTitleWithoutChangingChat() {
        for (String path : List.of("tablist.header", "tablist.footer")) {
            var config = new YamlConfiguration();
            config.set(path, List.of("<title>"));
            assertTrue(ChatFormatDefaults.preserveLegacySettings(config));
            assertTrue(config.getBoolean("tablist.show-current-title"));
            assertFalse(config.contains("format.title.enabled", true));
            assertFalse(config.contains("format.worlds.brackets", true));
            assertFalse(ChatFormatDefaults.preserveLegacySettings(config));
        }
    }

    private static YamlConfiguration resourceDefaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(ChatFormatDefaultsTest.class.getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8));
    }

    private static YamlConfiguration config(boolean brackets, String group, String template) {
        var config = new YamlConfiguration();
        config.set("format.worlds.brackets", brackets);
        config.set(path(group), template);
        return config;
    }

    private static String path(String group) {
        return "format.groups." + group + ".format";
    }

    private static Component render(YamlConfiguration config, String group) {
        config.set("format.worlds.aliases", List.of(Map.of("world", "survival", "name", "生存", "color", "#55ff55")));
        config.set("format.title.enabled", true);
        var formatter = new ChatFormatter();
        formatter.load(config.getConfigurationSection("format"));
        return formatter.formatLine(null, config.getString(path(group)), "survival", "Steve",
                Component.text("Steve"), Component.text("[旅行者]"), Component.text("你好"));
    }

    private static Component text(Component root, String content) {
        List<Component> pending = new ArrayList<>(List.of(root));
        for (int i = 0; i < pending.size(); i++) {
            Component candidate = pending.get(i);
            if (candidate instanceof TextComponent text && text.content().equals(content)) return candidate;
            pending.addAll(candidate.children());
        }
        fail("Missing text: " + content);
        return Component.empty();
    }
}
