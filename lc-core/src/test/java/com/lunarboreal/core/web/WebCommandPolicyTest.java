package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebCommandPolicyTest {
    private static final WebUserImpl MODERATOR = new WebUserImpl("moderator", "mod",
            Set.of("core.console.execute", "core.config.edit"), "mod-session", Long.MAX_VALUE, 0);
    private static final WebUserImpl ADMIN = new WebUserImpl("admin", "admin",
            Set.of("*"), "admin-session", Long.MAX_VALUE, 0);

    @Test void shippedDefaultsAllowDailyCommandsAndRegisteredAliases() throws Exception {
        YamlConfiguration config = defaults();
        Command list = command("list");
        Command kick = command("kick");
        Command lc = command("lc");
        Map<String, Command> commands = Map.of("minecraft:list", list, "list", list, "online", list,
                "minecraft:kick", kick, "kick", kick, "lunarcore:lc", lc, "lc", lc);

        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "list", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "online", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "kick Alex", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "lc m list", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "lc web status", config, () -> commands::get));
    }

    @Test void restrictedCommandsStayAdminOnly() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("commands.web-allow", List.of("lp", "lc web resetadmin", "stop"));
        config.set("commands.web-deny", List.of("stop"));
        Map<String, Command> commands = Map.of("lp", command("luckperms"), "lc", command("lc"), "stop", command("stop"));

        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "lp", config, () -> commands::get));
        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "lc web resetadmin", config, () -> commands::get));
        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "stop", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(ADMIN, "lc web resetadmin", config, () -> commands::get));
        assertDoesNotThrow(() -> WebCommandPolicy.check(ADMIN, "stop", config, () -> commands::get));
    }

    @Test void customRulesMatchFullWordsAndDenialsWin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("commands.web-allow", List.of("minecraft:time set"));
        config.set("commands.web-deny", List.of("minecraft:time set midnight"));
        Command time = command("time");
        Map<String, Command> commands = Map.of("minecraft:time", time, "time", time);

        assertDoesNotThrow(() -> WebCommandPolicy.check(MODERATOR, "time set day", config, () -> commands::get));
        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "time set midnight", config, () -> commands::get));
        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "time query daytime", config, () -> commands::get));
        assertForbidden(() -> WebCommandPolicy.check(MODERATOR, "time setting", config, () -> commands::get));
    }

    @Test void moderatorCanSaveUnrelatedConfigButCannotEditCommandRules() throws Exception {
        YamlConfiguration original = defaults();
        YamlConfiguration updated = defaults();
        updated.set("debug", true);
        assertDoesNotThrow(() -> WebCommandPolicy.checkConfigEdit(MODERATOR, original, updated));

        updated.set("commands.web-deny", List.of("minecraft:say"));
        assertForbidden(() -> WebCommandPolicy.checkConfigEdit(MODERATOR, original, updated));
        assertDoesNotThrow(() -> WebCommandPolicy.checkConfigEdit(ADMIN, original, updated));
        updated.set("commands.web-deny", original.get("commands.web-deny"));

        updated.set("commands.web-allow", List.of("minecraft:list"));
        assertForbidden(() -> WebCommandPolicy.checkConfigEdit(MODERATOR, original, updated));
        assertDoesNotThrow(() -> WebCommandPolicy.checkConfigEdit(ADMIN, original, updated));
        updated.set("commands.web-allow", original.get("commands.web-allow"));

        updated.set("web.jwt-secret", "replacement");
        assertForbidden(() -> WebCommandPolicy.checkConfigEdit(MODERATOR, original, updated));
    }

    private static YamlConfiguration defaults() throws Exception {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(WebCommandPolicyTest.class.getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8)) {
            YamlConfiguration config = new YamlConfiguration();
            config.load(reader);
            return config;
        }
    }

    private static Command command(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                throw new AssertionError("Policy checks must not execute commands");
            }
        };
    }

    private static void assertForbidden(Executable action) {
        assertEquals(403, assertThrows(HttpException.class, action).status());
    }
}
