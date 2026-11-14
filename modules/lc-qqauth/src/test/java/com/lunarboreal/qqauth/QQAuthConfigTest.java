package com.lunarboreal.qqauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import com.lunarboreal.api.web.HttpException;
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
    private static final Gson JSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.DOUBLE).create();

    @Test void fullBrowserSaveAcceptsNumbersAndUpdatesPasswordLimits(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        var body = browserBody(initial, Map.of(
                "auth.min-password-length", 6,
                "auth.max-password-length", 40,
                "auth.bcrypt-cost", 4,
                "auth.max-attempt-entries", 2,
                "integration.astrbot.port", 12313,
                "binding.code-length", 8,
                "binding.per-qq-limits", Map.of("12345678", 2)));
        assertInstanceOf(Double.class, ((Map<?, ?>) body.get("values")).get("auth.min-password-length"));

        var saved = store.save(body);
        var disk = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(6, disk.getInt("auth.min-password-length"));
        assertEquals(40, disk.getInt("auth.max-password-length"));
        assertEquals(2, disk.getInt("auth.max-attempt-entries"));
        assertEquals(2, disk.getInt("binding.per-qq-limits.12345678"));
        assertEquals(12313, disk.getInt("integration.astrbot.port"));
        assertEquals(true, saved.get("requiresReload"));
        assertEquals("fixture-private-bridge-secret", disk.getString("integration.astrbot.token"));
    }

    @Test void yamlScalarTypesAreNormalizedForEditingAndSaving(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("auth.min-password-length", "10");
        module.config().set("auth.max-password-length", "64");
        module.config().set("auth.sessions.enabled", "false");
        module.config().set("integration.group-id", 123456789012L);
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        var values = (Map<?, ?>) initial.get("values");
        assertEquals(10, values.get("auth.min-password-length"));
        assertEquals(false, values.get("auth.sessions.enabled"));
        assertEquals("123456789012", values.get("integration.group-id"));

        store.save(browserBody(initial, Map.of(
                "auth.min-password-length", "6",
                "auth.max-password-length", "40",
                "binding.per-qq-limits", Map.of("12345678", "2"))));
        var disk = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(6, disk.getInt("auth.min-password-length"));
        assertEquals(40, disk.getInt("auth.max-password-length"));
        assertEquals(2, disk.getInt("binding.per-qq-limits.12345678"));
        assertEquals("123456789012", disk.getString("integration.group-id"));
    }

    @Test void equivalentNumericRepresentationsDoNotRequestReload(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("auth.min-password-length", 10.0);
        module.config().set("auth.max-password-length", 64L);
        module.config().set("auth.sessions.enabled", "false");
        module.config().set("integration.group-id", 123456789012L);
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        assertEquals(false, initial.get("requiresReload"));
        var saved = store.save(browserBody(initial, Map.of()));
        assertEquals(false, saved.get("requiresReload"));
    }

    @Test void quotedQuotasRoundTripWithTheFullBrowserForm(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("binding.per-qq-limits.12345678", "2");
        module.config().set("binding.per-qq-limits.23456789", 3L);
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        var quotas = (Map<?, ?>) ((Map<?, ?>) initial.get("values")).get("binding.per-qq-limits");
        assertEquals(2, quotas.get("12345678"));
        assertEquals(3, quotas.get("23456789"));
        store.save(browserBody(initial, Map.of("auth.min-password-length", 6)));
        var disk = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(2, disk.getInt("binding.per-qq-limits.12345678"));
        assertEquals(3, disk.getInt("binding.per-qq-limits.23456789"));
        assertEquals(6, disk.getInt("auth.min-password-length"));
    }

    @Test void invalidQuotaTextRemainsVisibleAndFailsWithoutWriting(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("binding.per-qq-limits.12345678", "two");
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        var quotas = (Map<?, ?>) ((Map<?, ?>) initial.get("values")).get("binding.per-qq-limits");
        assertEquals("two", quotas.get("12345678"));
        var failure = assertThrows(HttpException.class, () -> store.save(browserBody(initial,
                Map.of("auth.min-password-length", 6))));
        assertTrue(failure.getMessage().contains("QQ 绑定上限"));
        assertEquals(initial.get("revision"), store.read().get("revision"));
    }

    @Test void fixingAQuotedNumberMarksChangedRuntimePolicyForReload(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("auth.min-password-length", "6");
        module.saveConfig();
        assertEquals(10, module.policy().minPasswordLength());
        QQAuthConfig store = new QQAuthConfig(module);
        var saved = store.save(browserBody(store.read(), Map.of()));
        assertEquals(true, saved.get("requiresReload"));
        assertEquals(6, YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()).getInt("auth.min-password-length"));
        assertEquals(10, module.policy().minPasswordLength());
    }

    @Test void inactiveBridgeSettingsDoNotBlockUnrelatedChanges(@TempDir Path directory) throws Exception {
        for (String mode : List.of("astrbot", "onebot", "disabled")) {
            QQAuthModule module = configuredModule(directory.resolve(mode));
            module.config().set("integration.mode", mode);
            module.config().set("integration.astrbot.token", mode.equals("astrbot") ? "short-token" : "");
            module.config().set("integration.astrbot.port", mode.equals("astrbot") ? 12312 : 0);
            module.config().set("integration.onebot.endpoint", mode.equals("onebot") ? "ws://127.0.0.1:3001" : "");
            module.config().set("integration.onebot.reconnect-seconds", mode.equals("onebot") ? 5 : 100000);
            module.saveConfig();
            QQAuthConfig store = new QQAuthConfig(module);
            var saved = store.save(browserBody(store.read(), Map.of("auth.min-password-length", 6)));
            assertEquals(6, ((Map<?, ?>) saved.get("values")).get("auth.min-password-length"), mode);
            assertEquals(true, saved.get("requiresReload"), mode);
        }
    }

    @Test void activeBridgeIsValidatedBeforeSavingModeChanges(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("integration.onebot.endpoint", "");
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        var failure = assertThrows(HttpException.class, () -> store.save(browserBody(initial,
                Map.of("integration.mode", "onebot", "auth.min-password-length", 6))));
        assertTrue(failure.getMessage().contains("OneBot 地址无效"));
        assertEquals(initial.get("revision"), store.read().get("revision"));
    }

    @Test void policyErrorsNameTheFieldAndDoNotPartiallyWrite(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        Map<Map<String, Object>, String> invalid = Map.of(
                Map.of("auth.min-password-length", 0), "最短密码长度",
                Map.of("auth.min-password-length", 65), "最短密码长度",
                Map.of("auth.min-password-length", 12, "auth.max-password-length", 10), "最短密码长度不能大于最长密码长度",
                Map.of("auth.max-password-length", 9), "最短密码长度不能大于最长密码长度",
                Map.of("auth.max-attempt-entries", 1), "限流记录上限",
                Map.of("binding.code-length", 5), "验证码长度",
                Map.of("auth.bcrypt-cost", 3), "密码哈希强度",
                Map.of("auth.sessions.ttl-seconds", 1.5), "免密会话有效期");
        for (var entry : invalid.entrySet()) {
            var failure = assertThrows(HttpException.class, () -> store.save(browserBody(initial, entry.getKey())));
            assertTrue(failure.getMessage().contains(entry.getValue()), failure.getMessage());
            assertEquals(initial.get("revision"), store.read().get("revision"));
        }
    }

    @Test void existingInvalidFieldCanBeCorrectedInOneSave(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("auth.min-password-length", 65);
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var saved = store.save(browserBody(store.read(), Map.of("auth.min-password-length", 6)));
        assertEquals(6, ((Map<?, ?>) saved.get("values")).get("auth.min-password-length"));
    }

    @Test void existingConfigurationUsesAutoIdentityUntilSavedModeIsReloaded(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        module.config().set("auth.identity-mode", null);
        module.saveConfig();
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        assertEquals("auto", ((Map<?, ?>) initial.get("values")).get("auth.identity-mode"));
        assertEquals(false, initial.get("requiresReload"));

        var saved = store.save(browserBody(initial, Map.of("auth.identity-mode", "name")));
        assertEquals("name", ((Map<?, ?>) saved.get("values")).get("auth.identity-mode"));
        assertEquals("auto", module.config().getString("auth.identity-mode", "auto"));
        assertEquals(true, saved.get("requiresReload"));
        assertEquals("name", YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile())
                .getString("auth.identity-mode"));
        module.config().set("auth.identity-mode", "name");
        assertEquals(false, store.read().get("requiresReload"));
    }

    @Test void identityModesRoundTripThroughBrowserSave(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        QQAuthConfig store = new QQAuthConfig(module);
        for (String mode : List.of("uuid", "name", "auto")) {
            var saved = store.save(browserBody(store.read(), Map.of("auth.identity-mode", mode)));
            assertEquals(mode, ((Map<?, ?>) saved.get("values")).get("auth.identity-mode"));
            assertEquals(!mode.equals("auto"), saved.get("requiresReload"));
        }
    }

    @Test void invalidIdentityModesDoNotChangeSavedConfiguration(@TempDir Path directory) throws Exception {
        QQAuthModule module = configuredModule(directory);
        QQAuthConfig store = new QQAuthConfig(module);
        var initial = store.read();
        for (Object mode : List.of("", "offline", "AUTO", true, 1, List.of("auto"))) {
            var failure = assertThrows(HttpException.class,
                    () -> store.save(browserBody(initial, Map.of("auth.identity-mode", mode))));
            assertTrue(failure.getMessage().contains("账号身份模式"), failure.getMessage());
            assertEquals(initial.get("revision"), store.read().get("revision"));
        }
    }

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

    private QQAuthModule configuredModule(Path directory) {
        QQAuthModule module = new QQAuthModule();
        module.init(null, directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config().set("integration.astrbot.token", "fixture-private-bridge-secret");
        module.saveConfig();
        return module;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> browserBody(Map<String, Object> initial, Map<String, Object> changes) {
        Map<String, Object> values = new HashMap<>((Map<String, Object>) initial.get("values"));
        values.putAll(changes);
        return JSON.fromJson(JSON.toJson(Map.of("revision", initial.get("revision"), "values", values,
                        "messages", initial.get("messages"), "clearSecrets", List.of())),
                new TypeToken<Map<String, Object>>() {}.getType());
    }
}
