package com.lunarboreal.terrain;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerrainOptionsPersistenceTest {
    @Test void retainsAdvancedPathsThroughTheRealRegistrationYaml() throws Exception {
        TerrainOptions expected = new TerrainOptions(1024, 2.8, 1.5, true, true, true, true,
                Map.of("continents.river_lanterns", false, "caves.ore_fix", true,
                        "biomes.temperature_offset", -0.69));
        YamlConfiguration source = new YamlConfiguration();
        source.set("worlds.tectonic.config", expected.map());
        YamlConfiguration restored = new YamlConfiguration();
        restored.loadFromString(source.saveToString());

        assertEquals(expected, TerrainModule.readOptions(
                restored.getConfigurationSection("worlds.tectonic.config")));
    }

    @Test void acceptsRegistrationsFromBeforeAdvancedSettingsExisted() throws Exception {
        YamlConfiguration source = new YamlConfiguration();
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(TerrainOptions.defaults().map());
        legacy.remove("advanced");
        source.set("config", legacy);
        YamlConfiguration restored = new YamlConfiguration();
        restored.loadFromString(source.saveToString());

        assertEquals(TerrainOptions.defaults(), TerrainModule.readOptions(restored.getConfigurationSection("config")));
    }
}
