package com.lunarboreal.core.message;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LbrMessageServiceTest {

    @Test
    void migratesSavedCoreHelpAndDefaultBrandingWithoutReplacingCustomText() {
        var bundled = new YamlConfiguration();
        bundled.set("prefix", "<aqua>LC</aqua> ");
        var saved = new YamlConfiguration();
        saved.set("prefix", "<gradient:#7ec8ff:#b98cff><bold>LBR</bold></gradient> <dark_gray>» ");
        saved.set("web.usage", java.util.List.of("自定义说明 /lbr web status", "/LBR web bind"));
        saved.set("custom", "保留 /lbraddon 与网址 https://example.org/lbr/path");

        assertTrue(LbrMessageService.migrateCoreBranding(saved, bundled));
        assertEquals("<aqua>LC</aqua> ", saved.getString("prefix"));
        assertEquals(java.util.List.of("自定义说明 /lc web status", "/lc web bind"),
                saved.getStringList("web.usage"));
        assertEquals("保留 /lbraddon 与网址 https://example.org/lbr/path", saved.getString("custom"));
        assertFalse(LbrMessageService.migrateCoreBranding(saved, bundled));
        saved.set("prefix", "<gold>自定义名称</gold> ");
        assertFalse(LbrMessageService.migrateCoreBranding(saved, bundled));
        assertEquals("<gold>自定义名称</gold> ", saved.getString("prefix"));
    }

    @Test
    void legacyEssentialsMessagesIgnoreConfiguredPrefix() {
        String oldPrefix = "<blue>[基础命令]</blue> ";
        String oldUsage = "<prefix><red>用法：<yellow>/burn";

        assertEquals("", LbrMessageService.resolveNamespacePrefix("essentials", oldPrefix));
        assertEquals("<red>用法：<yellow>/burn",
                oldUsage.replace("<prefix>", LbrMessageService.resolveNamespacePrefix("essentials", oldPrefix)));
        assertEquals("", LbrMessageService.resolveNamespacePrefix("essentials", null));
    }

    @Test
    void otherModulesKeepTheirConfiguredOrDefaultPrefix() {
        assertEquals("<red>[管理]</red> ",
                LbrMessageService.resolveNamespacePrefix("moderation", "<red>[管理]</red> "));
        assertEquals("<gray>[moderation]</gray> ",
                LbrMessageService.resolveNamespacePrefix("moderation", null));
    }
}
