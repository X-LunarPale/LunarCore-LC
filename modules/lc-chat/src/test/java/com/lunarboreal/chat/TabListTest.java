package com.lunarboreal.chat;

import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TabListTest {
    @Test void clampsRefreshWithoutOverflow() {
        assertEquals(20, TabList.period(Long.MIN_VALUE));
        assertEquals(100, TabList.period(5));
        assertEquals(72000, TabList.period(Long.MAX_VALUE));
    }
    @Test void titleRendersOnlyThroughExplicitTabTokens() {
        Component title = TabList.renderTitle("<gold>[先锋]</gold>");
        Component displayName = Component.text("Player");
        assertEquals("[先锋] Player", PlainTextComponentSerializer.plainText().serialize(
                TabList.renderName("<title> <displayname>", displayName, title)));
        assertEquals("Player", PlainTextComponentSerializer.plainText().serialize(
                TabList.renderName("<displayname>", displayName, title)));
        assertEquals("Player", PlainTextComponentSerializer.plainText().serialize(displayName));
        assertNull(displayName.clickEvent());
        assertNull(displayName.hoverEvent());
    }
    @Test void titleMiniMessageCannotInjectInteractiveTags() {
        Component title = TabList.renderTitle("<click:run_command:'/op Player'><red>[Admin]</red></click>");
        assertNull(title.clickEvent());
        assertNull(MiniMessage.miniMessage().deserialize(MiniMessage.miniMessage().serialize(title)).clickEvent());
    }
    @Test void vendorLegacyKeepsColorsWithoutInterpretingMiniMessage() {
        Component title=TabList.vendorTitle("&6[先锋]<click:run_command:'/op Player'>");
        String plain=PlainTextComponentSerializer.plainText().serialize(title);
        assertTrue(plain.startsWith("[先锋]<click:"));
        assertNull(title.clickEvent());
        assertTrue(MiniMessage.miniMessage().serialize(title).contains("gold"));
        assertEquals(Component.empty(),TabList.vendorTitle("§7未佩戴"));
        assertEquals(Component.empty(),TabList.vendorTitle("%lc_playertitle_current%"));
        assertEquals("Player",TabList.withoutEmptyTitle("{title} Player",Component.empty()));
    }
    @Test void explicitOldFormatSurvivesNewDefaultsUntilSaved() {
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.addDefault("tablist.player-name-format","{title} {player}");
        config.set("tablist.name-format","<displayname>");
        assertEquals("<displayname>",TabList.nameFormat(config));
        config.set("tablist.player-name-format","{title} {player}");
        assertEquals("{title} {player}",TabList.nameFormat(config));
    }
    @Test void newTabDefaultsDoNotRestoreTitlesAndUnusedTitlesNeedNoPolling() {
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.addDefault("tablist.player-name-format","{world} {player}");
        assertEquals("{world} {player}",TabList.nameFormat(config));
        config.set("format.title.enabled",false);
        config.set("tablist.show-current-title",false);
        assertFalse(TabList.needsTitles(config));
        for(String setting:java.util.List.of("nameplate.enabled","format.title.enabled","tablist.show-current-title")){
            config.set(setting,true);
            assertTrue(TabList.needsTitles(config),setting);
            config.set(setting,false);
        }
        config.set("format.enabled",false);
        config.set("format.title.enabled",true);
        config.set("tablist.enabled",false);
        config.set("tablist.show-current-title",true);
        assertFalse(TabList.needsTitles(config));
    }
    @Test void removesEmptyLcTitlePlaceholders() {
        for (String token : java.util.List.of("titles_title", "playertitle_current")) {
            String template = "%lc_" + token + "% Player";
            assertEquals("Player", TabList.withoutEmptyTitle(template, Component.empty()));
            assertEquals(template, TabList.withoutEmptyTitle(template, Component.text("Title")));
        }
    }
    @Test void restoresOnlyLastOwnedValueAndNeverExternalReplacement() {
        Component ours=Component.text("[Title] Player"),foreign=Component.text("External Player");
        assertTrue(TabList.stillOwns(ours,ours,true));
        assertFalse(TabList.stillOwns(foreign,ours,true));
        assertFalse(TabList.stillOwns(ours,ours,false));
        assertTrue(TabList.stillOwns(null,null,true));
    }
    @Test void permissionNameColorsSelectHighestPriorityWithoutAddingPrefixes() {
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.set("vip.permission","lc.chat.format.vip");
        config.set("vip.priority",50);
        config.set("vip.color","gold");
        config.set("admin.permission","lc.chat.format.admin");
        config.set("admin.priority",100);
        config.set("admin.color","#aaddff");
        config.set("invalid.color","not-a-color");
        var rules=TabList.nameColors(config);
        Component original=Component.text("Steve");
        Component admin=TabList.colorName(original,rules,node->true);
        assertEquals("Steve",PlainTextComponentSerializer.plainText().serialize(admin));
        assertEquals(TextColor.fromHexString("#aaddff"),admin.color());
        assertEquals(NamedTextColor.GOLD,TabList.colorName(original,rules,node->node.endsWith(".vip")).color());
        assertEquals(original,TabList.colorName(original,rules,node->false));
    }
    @Test void rejectsMalformedWebTabInputs() {
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("tabRefresh", 0)));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("tabRefresh", 1.5)));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("tabRefresh", Double.NaN)));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("tabEnabled", "true")));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("tabHeader", 5)));
        assertDoesNotThrow(() -> ChatModule.validateTabConfig(Map.of("tabEnabled", true, "tabRefresh", 5.0, "tabName", "<title> <displayname>")));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("defaultChatFormat", "<message>")));
        assertThrows(RuntimeException.class, () -> ChatModule.validateTabConfig(Map.of("defaultChatFormat", "x".repeat(1025))));
        assertDoesNotThrow(() -> ChatModule.validateTabConfig(Map.of("defaultChatFormat", "<displayname>: <message>")));
    }
}
