package com.lunarboreal.performance;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedSwitchesTest {
    @Test void loadingDoesNotChangeStoredValues() {
        var config = new YamlConfiguration();
        config.set("embedded.redstone.enabled", true);
        assertEquals(true, EmbeddedSwitches.values(config).get("redstone.enabled"));
        assertNull(config.get("embedded.hopper.enabled"));
    }
    @Test void childCanEnableBothParentLevelsAndModuleCanStillBeDisabled() {
        var config = new YamlConfiguration();
        var changes = EmbeddedSwitches.changes(Map.of("world-cleaner.items.abyss.enabled", true), config);
        assertEquals(true, changes.get("embedded.world-cleaner.enabled"));
        assertEquals(true, changes.get("embedded.world-cleaner.items.enabled"));
        config.set("embedded.world-cleaner.items.enabled", true);
        changes = EmbeddedSwitches.changes(Map.of("world-cleaner.enabled", false, "world-cleaner.items.enabled", true), config);
        assertEquals(false, changes.get("embedded.world-cleaner.enabled"));
    }
    @Test void batchTouchesOnlyDeclaredBooleansAndCoversEveryModule() {
        for (String id : com.lunarboreal.performance.lagfixer.EmbeddedLagFixer.ids()) {
            assertEquals(true, EmbeddedSwitches.batch(true).get("embedded." + id + ".enabled"));
            assertEquals(false, EmbeddedSwitches.batch(false).get("embedded." + id + ".enabled"));
        }
        assertFalse(EmbeddedSwitches.batch(true).containsKey("embedded.hopper.worlds"));
        assertFalse(EmbeddedSwitches.batch(true).containsKey("embedded.mob-ai.entities.tameable"));
        assertEquals(EmbeddedSwitches.ALL.size(), EmbeddedSwitches.ALL.stream().map(EmbeddedSwitches.Switch::key).distinct().count());
    }
    @Test void rejectsUnknownFieldsAndNonBooleanValues() {
        var config = new YamlConfiguration();
        assertThrows(RuntimeException.class, () -> EmbeddedSwitches.changes(Map.of("redstone.enabled", "true"), config));
        assertThrows(RuntimeException.class, () -> EmbeddedSwitches.changes(Map.of("hopper.worlds", true), config));
        assertThrows(RuntimeException.class, () -> EmbeddedSwitches.changes(Map.of(), config));
    }
}
