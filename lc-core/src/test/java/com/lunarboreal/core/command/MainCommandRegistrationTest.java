package com.lunarboreal.core.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.command.CommandSender;

import static org.junit.jupiter.api.Assertions.*;

class MainCommandRegistrationTest {
    @Test void exposesOnlyLcAndKeepsPermissionNodes() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/plugin.yml")), StandardCharsets.UTF_8)) {
            var descriptor = YamlConfiguration.loadConfiguration(reader);
            assertEquals(Set.of("lc"), descriptor.getConfigurationSection("commands").getKeys(false));
            assertEquals("/lc help", descriptor.getString("commands.lc.usage"));
            assertFalse(descriptor.getStringList("commands.lc.aliases").contains("lc"));
            for (String permission : java.util.List.of("info", "reload", "module", "web", "debug")) {
                assertTrue(descriptor.isConfigurationSection("permissions.lc." + permission));
            }
        }
    }

    @Test void completesModuleStatusShortcutWithTheSamePermissionAsModule() {
        var command = new LcMainCommand(null);
        assertTrue(command.onTabComplete(sender(true), null, "lc", new String[]{"m"}).contains("m"));
        assertEquals(List.of("list"), command.onTabComplete(sender(true), null, "lc", new String[]{"m", "l"}));
        assertEquals(List.of("list"), command.onTabComplete(sender(true), null, "lc", new String[]{"module", "l"}));
        assertTrue(command.onTabComplete(sender(false), null, "lc", new String[]{"m"}).isEmpty());
        assertTrue(command.onTabComplete(sender(false), null, "lc", new String[]{"m", "l"}).isEmpty());
    }

    private CommandSender sender(boolean allowed) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) ->
                        method.getName().equals("hasPermission") ? allowed : null);
    }
}
