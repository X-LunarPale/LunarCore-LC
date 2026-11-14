package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatAppearanceTest {
    @Test void worldAliasChangesOnlyDisplayAndRetainsColor() {
        var config = new YamlConfiguration();
        config.set("worlds.aliases", List.of(Map.of("world", "survival", "name", "生存世界", "color", "#12ab34")));
        var appearance = new ChatAppearance();
        appearance.load(config);
        assertEquals("生存世界", ChatFormatter.plain(appearance.world("survival")));
        assertEquals(TextColor.fromHexString("#12ab34"), appearance.world("survival").color());
        assertEquals("survival_nether", ChatFormatter.plain(appearance.world("survival_nether")));
        assertEquals("survival", appearance.aliases().getFirst().get("world"));
    }

    @Test void titleUsesConfiguredPapiThenBridgeWithoutLeakingTokens() {
        assertEquals("[探险者]", ChatFormatter.plain(ChatAppearance.resolveTitle("%custom_title%",
                token -> token.equals("%custom_title%") ? "&6[探险者]" : token)));
        assertEquals("[旅行者]", ChatFormatter.plain(ChatAppearance.resolveTitle(ChatAppearance.TITLE_PLACEHOLDER,
                token -> token.equals("%lc_playertitle_current%") ? "§b[旅行者]" : token)));
        assertEquals(Component.empty(), ChatAppearance.resolveTitle(ChatAppearance.TITLE_PLACEHOLDER, token -> token));
        assertEquals(Component.empty(), ChatAppearance.resolveTitle(ChatAppearance.TITLE_PLACEHOLDER,
                token -> token.equals(ChatAppearance.TITLE_PLACEHOLDER) ? "" : "&6[旧称号]"));
    }

    @Test void malformedManualTitleSettingUsesOfficialDefault() {
        for (String value : List.of("", " ", "not-a-placeholder", "prefix%playerTitle_use%")) {
            var config = new YamlConfiguration();
            config.set("title.placeholder", value);
            var formatter = new ChatFormatter();
            formatter.load(config);
            assertEquals(ChatAppearance.TITLE_PLACEHOLDER, formatter.appearance.titlePlaceholder());
            assertEquals("<displayname> <message>", formatter.titleTokens("<displayname> <message>"));
        }
        var config = new YamlConfiguration();
        config.set("title.placeholder", null);
        var appearance = new ChatAppearance();
        appearance.load(config);
        assertEquals(ChatAppearance.TITLE_PLACEHOLDER, appearance.titlePlaceholder());
    }

    @Test void disabledChatTitleCannotAppearViaConfiguredPlaceholder() {
        var config = new YamlConfiguration();
        config.set("title.enabled", false);
        var formatter = new ChatFormatter();
        formatter.load(config);
        assertEquals("Steve: 你好", ChatFormatter.plain(formatter.formatLine(null,
                "%playerTitle_use% <displayname>: <message>", "world", "Steve",
                Component.text("Steve"), ChatAppearance.vendorTitle("&6[旅行者]"), Component.text("你好"))));
    }

    @Test void chatPlacesWorldThenTitleThenNameAndKeepsMessageLiteral() {
        var format = new YamlConfiguration();
        format.set("worlds.aliases", List.of(Map.of("world", "survival", "name", "梦幻生存", "color", "#55ff55")));
        var formatter = new ChatFormatter();
        formatter.load(format);
        Component output = formatter.formatLine(null, ChatFormatDefaults.DEFAULT, "survival", "Steve",
                Component.text("Steve"), ChatAppearance.vendorTitle("&6[旅行者]"), Component.text("<red>你好"));
        assertEquals("[梦幻生存] [旅行者] Steve » <red>你好", ChatFormatter.plain(output));
        assertEquals("[梦幻生存] Steve » 你好", ChatFormatter.plain(formatter.formatLine(null, ChatFormatDefaults.DEFAULT,
                "survival", "Steve", Component.text("Steve"), Component.empty(), Component.text("你好"))));
        assertEquals("survival 梦幻生存", ChatFormatter.plain(formatter.formatLine(null, "{world_name} {world}",
                "survival", "Steve", Component.text("Steve"), Component.empty(), Component.empty())));
    }

    @Test void upgradePreservesCustomTemplates() {
        var config = new YamlConfiguration();
        String oldDefault = "<aqua>[<white>{world}</white>]</aqua> " + ChatFormatDefaults.NAME + "<aqua> » </aqua><white><message>";
        config.set("format.groups.default.format", oldDefault);
        config.set("format.groups.admin.format", "<red>管理员 <displayname>: <message>");
        assertTrue(ChatFormatDefaults.migrate(config));
        assertEquals(ChatFormatDefaults.DEFAULT, config.getString("format.groups.default.format"));
        assertEquals("<red>管理员 <displayname>: <message>", config.getString("format.groups.admin.format"));
        assertFalse(ChatFormatDefaults.migrate(config));
    }

    @Test void appearanceApiRejectsMalformedRowsAndAcceptsChineseAliases() {
        assertDoesNotThrow(() -> ChatModule.validateAppearanceConfig(Map.of("worldAliases",
                List.of(Map.of("world", "survival", "name", "生存世界", "color", "#55ff55")),
                "titlePlaceholder", "%playerTitle_use%", "chatShowTitle", true)));
        assertThrows(RuntimeException.class, () -> ChatModule.validateAppearanceConfig(Map.of("worldDefaultColor", "rgb(0,0,0)")));
        assertThrows(RuntimeException.class, () -> ChatModule.validateAppearanceConfig(Map.of("titlePlaceholder", "plain text")));
        assertThrows(RuntimeException.class, () -> ChatModule.validateAppearanceConfig(Map.of("worldAliases",
                List.of(Map.of("world", "world", "name", "", "color", "#55ff55")))));
        var duplicate = Map.of("world", "world", "name", "世界", "color", "aqua");
        assertThrows(RuntimeException.class, () -> ChatModule.validateAppearanceConfig(Map.of("worldAliases", List.of(duplicate, duplicate))));
    }
}
