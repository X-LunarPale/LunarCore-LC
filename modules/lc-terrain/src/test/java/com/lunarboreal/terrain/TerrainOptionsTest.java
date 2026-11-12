package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerrainOptionsTest {
    @Test void roundTripsAllValuesAndUsesTheAlpinePreset() {
        TerrainOptions options = TerrainOptions.defaults();
        assertEquals(new TerrainOptions(640, 1.8, 1.2, true, true, true, true), options);
        assertEquals(options, TerrainOptions.parse(options.map()));
        assertEquals(new TerrainOptions(1024, 3, 0.5, false, false, false, false),
                TerrainOptions.parse(new TerrainOptions(1024, 3, 0.5, false, false, false, false).map()));
    }

    @Test void rejectsUnknownMissingNullAndWrongTypes() {
        Map<String, Object> values = new HashMap<>(TerrainOptions.defaults().map());
        values.put("generatorClass", "arbitrary");
        assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(values));
        values.remove("generatorClass");
        values.remove("height");
        assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(values));
        assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(null));
        for (Object invalid : new Object[]{"640", true, 640.5, Double.NaN, Double.POSITIVE_INFINITY, 255, 769, 4096}) {
            Map<String, Object> config = new HashMap<>(TerrainOptions.defaults().map());
            config.put("height", invalid);
            assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(config), invalid.toString());
        }
        for (Object invalid : new Object[]{"true", 1, null}) {
            Map<String, Object> config = new HashMap<>(TerrainOptions.defaults().map());
            config.put("rollingHills", invalid);
            assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(config));
        }
    }

    @Test void rejectsUnsafeScaleValuesInBothConstructorsAndParsing() {
        for (double scale : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0.09, 15.01}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TerrainOptions(640, scale, 1, true, true, true, true));
            assertThrows(IllegalArgumentException.class,
                    () -> new TerrainOptions(640, 1, scale, true, true, true, true));
            Map<String, Object> config = new HashMap<>(TerrainOptions.defaults().map());
            config.put("horizontalScale", scale);
            assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(config));
        }
        for (double scale : new double[]{0.49, 3.01}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TerrainOptions(640, 1, scale, true, true, true, true));
        }
        assertEquals(0.1, new TerrainOptions(640, 0.1, 1, true, true, true, true).verticalScale());
        assertEquals(15, new TerrainOptions(640, 15, 1, true, true, true, true).verticalScale());
    }

    @Test void acceptsOfficialPresetHeightsAndExtendedElevation() {
        for (int height : new int[]{320, 384, 512, 640, 704, 768, 832, 1024}) {
            TerrainOptions options = new TerrainOptions(height, 2.5, 1, false, false, false, false,
                    Map.of("global_terrain.elevation_boost", 1.6));
            assertEquals(options, TerrainOptions.parse(options.map()));
        }
    }

    @Test void normalizesLegacySettingsAndCopiesAdvancedValues() {
        Map<String, Object> legacy = new HashMap<>(TerrainOptions.defaults().map());
        legacy.remove("advanced");
        TerrainOptions options = TerrainOptions.parse(legacy);
        assertEquals(TerrainOptions.defaults(), options);
        assertEquals(TerrainOptions.schema().size(), options.advanced().size());
        assertEquals(0, options.number("general.snow_start_offset"));
        assertEquals(0, options.number("global_terrain.elevation_boost"));
        assertTrue(options.flag("continents.river_lanterns"));
        assertFalse(options.flag("caves.ore_fix"));

        Map<String, Object> values = new HashMap<>();
        values.put("caves.cheese_enabled", false);
        values.put("islands.noise_multiplier", 0);
        TerrainOptions custom = new TerrainOptions(640, 1.8, 1.2, true, true, true, true, values);
        values.put("caves.cheese_enabled", true);
        assertFalse(custom.flag("caves.cheese_enabled"));
        assertEquals(0, custom.number("islands.noise_multiplier"));
        assertThrows(UnsupportedOperationException.class, () -> custom.advanced().put("caves.cheese_enabled", true));
        assertThrows(IllegalArgumentException.class, () -> custom.number("caves.cheese_enabled"));
        assertThrows(IllegalArgumentException.class, () -> custom.flag("islands.noise_multiplier"));
        assertEquals(custom, TerrainOptions.parse(custom.map()));
    }

    @Test void rejectsUnknownMalformedAndOutOfRangeAdvancedSettings() {
        for (Map<String, Object> advanced : java.util.List.<Map<String, Object>>of(
                Map.of("unknown.option", true),
                Map.of("caves.spaghetti_enabled", false),
                Map.of("experimental.alternate_continents_scaling", true),
                Map.of("caves.cheese_enabled", 0),
                Map.of("islands.noise_multiplier", "1"),
                Map.of("islands.noise_multiplier", Double.NaN),
                Map.of("islands.noise_multiplier", Double.POSITIVE_INFINITY),
                Map.of("islands.noise_multiplier", -0.01),
                Map.of("islands.noise_multiplier", 5.01),
                Map.of("general.snow_start_offset", 128.5),
                Map.of("general.snow_start_offset", 1025))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TerrainOptions(640, 1.8, 1.2, true, true, true, true, advanced));
        }
        for (Object advanced : new Object[]{true, "{}", java.util.List.of(), Map.of(42, true)}) {
            Map<String, Object> config = new HashMap<>(TerrainOptions.defaults().map());
            config.put("advanced", advanced);
            assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(config));
        }
        Map<String, Object> config = new HashMap<>(TerrainOptions.defaults().map());
        config.put("advanced", null);
        assertThrows(IllegalArgumentException.class, () -> TerrainOptions.parse(config));
    }

    @Test void schemaDefaultsMatchValidatedValuesAndCannotBeMutated() {
        var schema = TerrainOptions.schema();
        TerrainOptions options = TerrainOptions.defaults();
        assertTrue(schema.stream().anyMatch(field -> "global_terrain.min_y".equals(field.get("key"))));
        assertEquals(schema.size(), schema.stream().map(field -> field.get("key")).distinct().count());
        for (var field : schema) {
            String key = (String) field.get("key");
            assertEquals(field.get("defaultValue"), options.advanced().get(key));
            assertFalse(((String) field.get("label")).isBlank());
            assertFalse(((String) field.get("group")).isBlank());
            if ("number".equals(field.get("type"))) {
                assertInstanceOf(Double.class, field.get("defaultValue"));
                assertTrue((double) field.get("min") <= options.number(key));
                assertTrue((double) field.get("max") >= options.number(key));
                assertTrue((double) field.get("step") > 0);
            } else {
                assertInstanceOf(Boolean.class, field.get("defaultValue"));
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> schema.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> schema.getFirst().put("label", "changed"));
    }
}
