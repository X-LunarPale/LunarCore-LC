package com.lunarboreal.anticheat;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SurvivalPresetTest {
    private YamlConfiguration preset() {
        var resource = getClass().getResourceAsStream("/presets/paper-26.3.yml");
        assertNotNull(resource);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
    }

    @Test void simulationHasNoActionsOrSetbacksAndIsExcludedFromEveryActiveGroup() {
        var yaml = preset();
        assertTrue(yaml.getBoolean("permissions.grim.exempt.simulation"));
        assertEquals(-1, yaml.getInt("config.Simulation.immediate-setback-threshold"));
        assertEquals(-1, yaml.getInt("config.Simulation.max-advantage"));
        assertTrue(yaml.getStringList("punishments.Simulation.checks").isEmpty());
        assertTrue(yaml.getStringList("punishments.Simulation.commands").isEmpty());
        for (String group : yaml.getConfigurationSection("punishments").getKeys(false)) {
            if (group.equals("Simulation")) continue;
            var checks = yaml.getStringList("punishments." + group + ".checks");
            assertTrue(checks.contains("!Simulation"), group);
            assertFalse(checks.contains("Simulation"), group);
        }
    }

    @Test void versionSensitiveChecksOnlyLogAndAlert() {
        var yaml = preset();
        for (String group : List.of("Movement", "Post", "PacketOrder", "Hitboxes", "Misc", "Combat", "Autoclicker")) {
            assertEquals(60, yaml.getInt("punishments." + group + ".remove-violations-after"));
            assertTrue(yaml.getStringList("punishments." + group + ".commands").stream()
                    .allMatch(rule -> rule.endsWith("[log]") || rule.endsWith("[alert]")), group);
        }
    }

    @Test void accountPunishmentsRequireRepeatedDetectionsAndNameTheCheck() {
        var yaml = preset();
        for (String group : List.of("Knockback", "BadPackets", "Reach")) {
            var rules = yaml.getStringList("punishments." + group + ".commands");
            String punishment = rules.stream().filter(rule -> rule.contains("lc:tempban")).findFirst().orElseThrow();
            assertTrue(Integer.parseInt(punishment.substring(0, punishment.indexOf(':'))) >= 50);
            assertTrue(punishment.endsWith("--check=%check_name% %check_name%"));
            assertFalse(punishment.contains("mute"));
        }
    }
}
