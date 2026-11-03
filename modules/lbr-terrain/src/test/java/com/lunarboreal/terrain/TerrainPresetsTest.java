package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerrainPresetsTest {
    @Test void preservesConveniencePresetsAndTheFiveOfficialPresets() {
        var presets = TerrainPresets.all();
        assertEquals(List.of("classic", "alpine", "monumental", "default", "large_biomes",
                        "deserted", "frozen_wasteland", "overkill"),
                presets.stream().map(preset -> preset.get("id")).toList());
        assertEquals(TerrainOptions.defaults(), options(find("alpine")));
        assertEquals(1024, options(find("monumental")).height());
        assertEquals(0, options(find("classic")).number("general.snow_start_offset"));
        assertEquals(128, options(find("default")).number("general.snow_start_offset"));

        TerrainOptions largeBiomes = options(find("large_biomes"));
        assertEquals(0.06, largeBiomes.number("biomes.temperature_scale"));
        assertEquals(0.06, largeBiomes.number("biomes.vegetation_scale"));
        assertEquals(1.1, largeBiomes.number("biomes.temperature_multiplier"));
        assertEquals(1.1, largeBiomes.number("biomes.vegetation_multiplier"));
        TerrainOptions deserted = options(find("deserted"));
        assertEquals(0, deserted.number("biomes.temperature_scale"));
        assertEquals(0, deserted.number("biomes.temperature_multiplier"));
        assertEquals(1, deserted.number("biomes.temperature_offset"));

        TerrainOptions frozen = options(find("frozen_wasteland"));
        assertEquals(512, frozen.height());
        assertEquals(1.2, frozen.verticalScale());
        assertEquals(0.5, frozen.number("global_terrain.elevation_boost"));
        assertTrue(frozen.flag("continents.river_ice"));
        assertFalse(frozen.junglePillars());
        assertEquals(-0.69, frozen.number("biomes.temperature_offset"));
        assertEquals(-0.1, frozen.number("biomes.vegetation_offset"));

        TerrainOptions overkill = options(find("overkill"));
        assertEquals(832, overkill.height());
        assertEquals(2.5, overkill.verticalScale());
        assertEquals(1.6, overkill.number("global_terrain.elevation_boost"));
        assertTrue(overkill.flag("global_terrain.ultrasmooth"));
        assertFalse(overkill.flag("continents.river_lanterns"));
        assertFalse(overkill.rollingHills());
        assertFalse(overkill.undergroundRivers());
        assertFalse(overkill.lavaTunnels());
        assertFalse(overkill.junglePillars());
        assertEquals(0.08, overkill.number("continents.erosion_scale"));
        assertEquals(-0.2, overkill.number("biomes.vegetation_offset"));
        assertEquals(512, overkill.number("general.snow_start_offset"));
        assertTrue(overkill.flag("experimental.alternate_erosion_scaling"));
    }

    @Test void officialPresetsDiscloseTheMonumentBehaviorNotYetPorted() {
        for (var preset : TerrainPresets.all()) {
            options(preset);
            if (List.of("classic", "alpine", "monumental").contains(preset.get("id"))) {
                assertFalse(preset.containsKey("unsupported"));
            } else {
                assertEquals(List.of(
                        Map.of("key", "oceans.monument_offset", "label", "海底神殿高度偏移", "value", -30)),
                        preset.get("unsupported"));
            }
        }
    }

    private static Map<String, Object> find(String id) {
        return TerrainPresets.all().stream().filter(preset -> id.equals(preset.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static TerrainOptions options(Map<String, Object> preset) {
        return TerrainOptions.parse((Map<String, Object>) preset.get("config"));
    }
}
