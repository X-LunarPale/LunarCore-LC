package com.lunarboreal.core.message;

import com.lunarboreal.core.module.ModuleClassLoader;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcMessageServiceTest {

    @Test
    void missingModuleTranslationDoesNotInheritTheCoreBundle(@TempDir Path temp) throws Exception {
        var core = Files.createDirectories(temp.resolve("core/lang"));
        var module = Files.createDirectories(temp.resolve("module/lang"));
        var coreHk = Files.writeString(core.resolve("zh_HK.yml"), "core-message: core\n");
        var moduleDefault = Files.writeString(module.resolve("zh_CN.yml"), "module-message: module\n");
        try (var parent = new URLClassLoader(new URL[]{core.getParent().toUri().toURL()}, null);
             var loader = new ModuleClassLoader("test", module.getParent().toUri().toURL(), parent)) {
            assertEquals(coreHk.toUri().toURL(), loader.getResource("lang/zh_HK.yml"));
            assertNull(LcMessageService.findResource(loader, "lang/zh_HK.yml"));
            assertEquals(moduleDefault.toUri().toURL(), LcMessageService.findResource(loader, "lang/zh_CN.yml"));
        }
    }

    @Test
    void migratesSavedCoreHelpAndDefaultBrandingWithoutReplacingCustomText() {
        var bundled = new YamlConfiguration();
        bundled.set("prefix", "<aqua>LC</aqua> ");
        var saved = new YamlConfiguration();
        saved.set("prefix", "<gradient:#7ec8ff:#b98cff><bold>L" + "BR</bold></gradient> <dark_gray>» ");
        saved.set("web.usage", java.util.List.of("自定义说明 /l" + "br web status", "/LC web bind"));
        saved.set("custom", "保留 /lcaddon 与网址 https://example.org/lc/path");

        assertTrue(LcMessageService.migrateCoreBranding(saved, bundled));
        assertEquals("<aqua>LC</aqua> ", saved.getString("prefix"));
        assertEquals(java.util.List.of("自定义说明 /lc web status", "/lc web bind"),
                saved.getStringList("web.usage"));
        assertEquals("保留 /lcaddon 与网址 https://example.org/lc/path", saved.getString("custom"));
        assertFalse(LcMessageService.migrateCoreBranding(saved, bundled));
        saved.set("prefix", "<gold>自定义名称</gold> ");
        assertFalse(LcMessageService.migrateCoreBranding(saved, bundled));
        assertEquals("<gold>自定义名称</gold> ", saved.getString("prefix"));
    }

    @Test
    void legacyEssentialsMessagesIgnoreConfiguredPrefix() {
        String oldPrefix = "<blue>[基础命令]</blue> ";
        String oldUsage = "<prefix><red>用法：<yellow>/burn";

        assertEquals("", LcMessageService.resolveNamespacePrefix("essentials", oldPrefix));
        assertEquals("<red>用法：<yellow>/burn",
                oldUsage.replace("<prefix>", LcMessageService.resolveNamespacePrefix("essentials", oldPrefix)));
        assertEquals("", LcMessageService.resolveNamespacePrefix("essentials", null));
    }

    @Test
    void otherModulesKeepTheirConfiguredOrDefaultPrefix() {
        assertEquals("<red>[管理]</red> ",
                LcMessageService.resolveNamespacePrefix("moderation", "<red>[管理]</red> "));
        assertEquals("<aqua>[moderation]</aqua> ",
                LcMessageService.resolveNamespacePrefix("moderation", null));
    }
}
