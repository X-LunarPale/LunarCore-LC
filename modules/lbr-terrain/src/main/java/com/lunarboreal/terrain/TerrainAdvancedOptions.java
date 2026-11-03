package com.lunarboreal.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated per-world options with upstream paths retained for reproducibility. */
public final class TerrainAdvancedOptions {
    private static final List<Field> FIELDS = fields();
    private static final Map<String, Field> BY_KEY = index();

    private TerrainAdvancedOptions() {}

    public static Map<String, Object> normalize(Map<String, Object> values) {
        if (values == null) throw new IllegalArgumentException("advanced must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : FIELDS) result.put(field.key(), field.defaultValue());
        for (var entry : values.entrySet()) {
            Field field = BY_KEY.get(entry.getKey());
            if (field == null) throw new IllegalArgumentException("Unknown advanced terrain key: " + entry.getKey());
            Object value = entry.getValue();
            if (field.defaultValue() instanceof Boolean) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(field.key() + " must be true or false");
            } else {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() < field.min() || number.doubleValue() > field.max()
                        || field.key().equals("general.snow_start_offset")
                                && number.doubleValue() != Math.rint(number.doubleValue())) {
                    throw new IllegalArgumentException(field.key() + " is outside its allowed range");
                }
                value = ((Number) value).doubleValue();
            }
            result.put(field.key(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static List<Map<String, Object>> schema() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Field field : FIELDS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", field.key());
            row.put("group", field.group());
            row.put("label", field.label());
            row.put("type", field.defaultValue() instanceof Boolean ? "boolean" : "number");
            row.put("defaultValue", field.defaultValue());
            if (field.defaultValue() instanceof Number) {
                row.put("min", field.min());
                row.put("max", field.max());
                row.put("step", field.step());
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(result);
    }

    private static Map<String, Field> index() {
        Map<String, Field> result = new LinkedHashMap<>();
        for (Field field : FIELDS) {
            if (result.put(field.key(), field) != null) throw new IllegalStateException("Duplicate advanced option");
        }
        return Map.copyOf(result);
    }

    private static List<Field> fields() {
        return List.of(
                number("general.snow_start_offset", "整体地形", "积雪起点偏移", 0, 0, 1024, 16),
                number("global_terrain.elevation_boost", "整体地形", "地形抬升", 0, 0, 2, 0.01),
                flag("global_terrain.ultrasmooth", "整体地形", "超平滑地形", false),
                number("continents.ocean_offset", "大陆与河流", "海洋偏移", -0.8, -2, 0, 0.05),
                number("continents.continents_scale", "大陆与河流", "大陆噪声尺度", 0.13, 0.01, 1, 0.01),
                number("continents.erosion_scale", "大陆与河流", "侵蚀噪声尺度", 0.25, 0.01, 1, 0.01),
                number("continents.ridge_scale", "大陆与河流", "山脊噪声尺度", 0.25, 0.01, 2, 0.01),
                number("continents.flat_terrain_skew", "大陆与河流", "平坦地形偏移", 0.1, -1, 1, 0.05),
                flag("continents.river_lanterns", "大陆与河流", "地下河照明", true),
                flag("continents.river_ice", "大陆与河流", "地下河结冰", false),
                flag("islands.enabled", "岛屿", "生成岛屿", true),
                number("islands.noise_scale", "岛屿", "岛屿噪声尺度", 0.11, 0, 1, 0.01),
                number("islands.noise_multiplier", "岛屿", "岛屿噪声倍率", 1, 0, 5, 0.1),
                number("islands.noise_offset", "岛屿", "岛屿噪声偏移", 0, -1, 1, 0.05),
                number("oceans.ocean_depth", "海洋", "海洋深度", -0.22, -10, -0.05, 0.01),
                number("oceans.deep_ocean_depth", "海洋", "深海深度", -0.45, -10, -0.05, 0.01),
                number("biomes.temperature_scale", "生物群系", "温度噪声尺度", 0.25, 0, 1, 0.01),
                number("biomes.temperature_multiplier", "生物群系", "温度噪声倍率", 1, 0, 5, 0.1),
                number("biomes.temperature_offset", "生物群系", "温度噪声偏移", 0, -1, 1, 0.05),
                number("biomes.vegetation_scale", "生物群系", "植被噪声尺度", 0.25, 0, 1, 0.01),
                number("biomes.vegetation_multiplier", "生物群系", "植被噪声倍率", 1, 0, 5, 0.1),
                number("biomes.vegetation_offset", "生物群系", "植被噪声偏移", 0, -1, 1, 0.05),
                number("caves.depth_cutoff_start", "洞穴", "洞穴深度截断起点", 0.1, -0.1, 1, 0.1),
                number("caves.depth_cutoff_size", "洞穴", "洞穴深度截断宽度", 0.1, 0, 1, 0.1),
                flag("caves.cheese_enabled", "洞穴", "大型洞穴", true),
                number("caves.cheese_additive", "洞穴", "大型洞穴密度偏移", 0.27, -0.5, 0.5, 0.01),
                flag("caves.noodle_enabled", "洞穴", "细窄洞穴", true),
                number("caves.noodle_additive", "洞穴", "细窄洞穴密度偏移", -0.075, -0.25, 0.25, 0.025),
                flag("caves.carvers_enabled", "洞穴", "洞穴雕刻器", true),
                flag("caves.ore_fix", "洞穴", "扩展高度矿石分布", false),
                flag("experimental.alternate_erosion_scaling", "实验选项", "替代侵蚀缩放", false)
        );
    }

    private static Field number(String key, String group, String label, double defaultValue,
                                double min, double max, double step) {
        return new Field(key, group, label, defaultValue, min, max, step);
    }

    private static Field flag(String key, String group, String label, boolean defaultValue) {
        return new Field(key, group, label, defaultValue, 0, 0, 0);
    }

    private record Field(String key, String group, String label, Object defaultValue,
                         double min, double max, double step) {}
}
