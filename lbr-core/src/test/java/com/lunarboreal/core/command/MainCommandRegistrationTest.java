package com.lunarboreal.core.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class MainCommandRegistrationTest {
    @Test void exposesLcAndKeepsLegacyCommandAndPermissionNodes() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/plugin.yml")), StandardCharsets.UTF_8)) {
            var descriptor = YamlConfiguration.loadConfiguration(reader);
            assertTrue(descriptor.isConfigurationSection("commands.lc"));
            assertEquals("/lc help", descriptor.getString("commands.lc.usage"));
            assertTrue(descriptor.getStringList("commands.lc.aliases").contains("lbr"));
            for (String permission : java.util.List.of("info", "reload", "module", "web", "debug")) {
                assertTrue(descriptor.isConfigurationSection("permissions.lbr." + permission));
            }
        }
    }
}
