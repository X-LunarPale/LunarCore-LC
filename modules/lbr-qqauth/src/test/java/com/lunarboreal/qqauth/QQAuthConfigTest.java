package com.lunarboreal.qqauth;

import com.lunarboreal.api.module.ModuleDescription;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class QQAuthConfigTest {
    @Test void savesMessagesImmediatelyWhileAuthSettingsWaitForReload(@TempDir Path directory) throws Exception {
        QQAuthModule module = new QQAuthModule();
        module.init(null, directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config().set("integration.astrbot.token", "fixture-private-bridge-secret");
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        assertFalse(initial.toString().contains("fixture-private-bridge-secret"));
        Map<String, Object> values = new HashMap<>((Map<String, Object>) initial.get("values"));
        values.put("integration.group-id", "12345678");
        var saved = store.save(Map.of("revision", initial.get("revision"), "values", values,
                "messages", Map.of("login.required", "&a你好 {player}"), "clearSecrets", List.of()));
        assertEquals("REPLACE_WITH_QQ_GROUP_ID", module.config().getString("integration.group-id"));
        assertEquals("你好 Steve", module.plainMsg("login.required", "player", "Steve"));
        assertEquals(true, saved.get("requiresReload"));
        var disk = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals("12345678", disk.getString("integration.group-id"));
        assertEquals("fixture-private-bridge-secret", disk.getString("integration.astrbot.token"));
        assertEquals("&a你好 {player}", disk.getString("messages.login.required"));
        assertFalse(saved.toString().contains("fixture-private-bridge-secret"));
        assertThrows(RuntimeException.class, () -> store.save(Map.of("revision", initial.get("revision"),
                "values", values, "messages", Map.of())));
    }

    @Test void messageOnlySaveAppliesColorsAndNewlinesWithoutReload(@TempDir Path directory) throws Exception {
        QQAuthModule module = new QQAuthModule();
        module.init(null, directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config().set("integration.astrbot.token", "fixture-private-bridge-secret");
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        assertEquals(false, initial.get("requiresReload"));
        var saved = store.save(Map.of("revision", initial.get("revision"), "values", Map.of(),
                "messages", Map.of("login.bind-required", "&e发送 &a/验证 {code}\n完成后重新连接。")));
        assertEquals("发送 /验证 123456\n完成后重新连接。",
                module.plainMsg("login.bind-required", "code", "123456"));
        assertEquals(false, saved.get("requiresReload"));
        assertFalse(module.ready);
        assertNull(module.auth);
    }

    @Test void rejectsUnknownKeysAndInvalidPoliciesWithoutPartialWrites(@TempDir Path directory) throws Exception {
        QQAuthModule module = new QQAuthModule();
        module.init(null, directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config().set("integration.astrbot.token", "fixture-private-bridge-secret");
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("auth.enabled", false),
                Map.<String, Object>of("auth.bcrypt-cost", 100), Map.<String, Object>of("integration.astrbot.port", 65536),
                Map.<String, Object>of("binding.per-qq-limits", Map.of("qq.bad", 2)))) {
            assertThrows(RuntimeException.class, () -> store.save(Map.of("revision", initial.get("revision"),
                    "values", invalid, "messages", Map.of())));
            assertEquals(initial.get("revision"), store.read().get("revision"));
        }
    }
}
