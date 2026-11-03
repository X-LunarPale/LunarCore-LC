package com.lunarboreal.terrain;

import java.util.List;
import java.util.Map;

/** Preset values pinned to the shipped Tectonic 3.0.29 artifact. */
public final class TerrainPresets {
    private TerrainPresets() {}

    public static List<Map<String, Object>> all() {
        return List.of(
                preset("classic", "经典山脉", officialDefaults()),
                preset("alpine", "宏伟山脉", TerrainOptions.defaults()),
                preset("monumental", "极高山脉", new TerrainOptions(1024, 2.8, 1.5, true, true, true, true)),
                official("default", "官方默认", officialDefaults(), 128),
                official("large_biomes", "大型生物群系", new TerrainOptions(384, 1.125, 1,
                        true, true, true, true, Map.of(
                        "biomes.temperature_scale", 0.06,
                        "biomes.temperature_multiplier", 1.1,
                        "biomes.vegetation_scale", 0.06,
                        "biomes.vegetation_multiplier", 1.1)), 128),
                official("deserted", "荒漠", new TerrainOptions(384, 1.125, 1,
                        true, true, true, true, Map.of(
                        "biomes.temperature_scale", 0,
                        "biomes.temperature_multiplier", 0,
                        "biomes.temperature_offset", 1)), 128),
                official("frozen_wasteland", "冰封荒原", new TerrainOptions(512, 1.2, 1,
                        true, true, true, false, Map.of(
                        "global_terrain.elevation_boost", 0.5,
                        "continents.river_ice", true,
                        "continents.flat_terrain_skew", 0,
                        "biomes.temperature_scale", 0,
                        "biomes.temperature_multiplier", 0,
                        "biomes.temperature_offset", -0.69,
                        "biomes.vegetation_scale", 0.15,
                        "biomes.vegetation_multiplier", 0.1,
                        "biomes.vegetation_offset", -0.1)), 128),
                official("overkill", "极限地形", new TerrainOptions(832, 2.5, 1,
                        false, false, false, false, Map.ofEntries(
                        Map.entry("global_terrain.elevation_boost", 1.6),
                        Map.entry("global_terrain.ultrasmooth", true),
                        Map.entry("continents.ocean_offset", -0.5),
                        Map.entry("continents.continents_scale", 0.1),
                        Map.entry("continents.erosion_scale", 0.08),
                        Map.entry("continents.ridge_scale", 0.2),
                        Map.entry("continents.river_lanterns", false),
                        Map.entry("continents.river_ice", false),
                        Map.entry("continents.flat_terrain_skew", 0.5),
                        Map.entry("biomes.temperature_scale", 0.1),
                        Map.entry("biomes.temperature_multiplier", 1.1),
                        Map.entry("biomes.vegetation_scale", 0.1),
                        Map.entry("biomes.vegetation_multiplier", 1.1),
                        Map.entry("biomes.vegetation_offset", -0.2),
                        Map.entry("experimental.alternate_erosion_scaling", true))), 512)
        );
    }

    private static TerrainOptions officialDefaults() {
        return new TerrainOptions(384, 1.125, 1, true, true, true, true);
    }

    private static Map<String, Object> preset(String id, String name, TerrainOptions options) {
        return Map.of("id", id, "name", name, "config", Map.copyOf(options.map()));
    }

    private static Map<String, Object> official(String id, String name, TerrainOptions options, int snowOffset) {
        Map<String, Object> advanced = new java.util.LinkedHashMap<>(options.advanced());
        advanced.put("general.snow_start_offset", snowOffset);
        TerrainOptions effective = new TerrainOptions(options.height(), options.verticalScale(),
                options.horizontalScale(), options.rollingHills(), options.undergroundRivers(),
                options.lavaTunnels(), options.junglePillars(), advanced);
        return Map.of("id", id, "name", name, "config", Map.copyOf(effective.map()),
                "unsupported", List.of(
                        Map.of("key", "oceans.monument_offset", "label", "海底神殿高度偏移", "value", -30)));
    }
}
