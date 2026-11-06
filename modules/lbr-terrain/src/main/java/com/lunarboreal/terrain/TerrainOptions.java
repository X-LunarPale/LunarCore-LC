package com.lunarboreal.terrain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record TerrainOptions(int height, double verticalScale, double horizontalScale,
                             boolean rollingHills, boolean undergroundRivers,
                             boolean lavaTunnels, boolean junglePillars,
                             Map<String, Object> advanced) {
    public static final List<Integer> HEIGHTS = List.of(384, 512, 640, 704, 832, 1024);
    private static final Set<String> KEYS = Set.of("height", "verticalScale", "horizontalScale",
            "rollingHills", "undergroundRivers", "lavaTunnels", "junglePillars");

    public TerrainOptions {
        requireScale("verticalScale", verticalScale, 0.1, 15);
        requireScale("horizontalScale", horizontalScale, 0.5, 3);
        advanced = TerrainAdvancedOptions.normalize(advanced);
        new TerrainHeight(((Number) advanced.get("global_terrain.min_y")).intValue(), height);
    }

    public TerrainOptions(int height, double verticalScale, double horizontalScale,
                          boolean rollingHills, boolean undergroundRivers,
                          boolean lavaTunnels, boolean junglePillars) {
        this(height, verticalScale, horizontalScale, rollingHills, undergroundRivers,
                lavaTunnels, junglePillars, Map.of());
    }

    public static TerrainOptions defaults() {
        return new TerrainOptions(640, 1.8, 1.2, true, true, true, true);
    }

    public static TerrainOptions parse(Map<String, Object> values) {
        if (values == null || !values.keySet().containsAll(KEYS)
                || values.keySet().stream().anyMatch(key -> !KEYS.contains(key) && !"advanced".equals(key))) {
            throw new IllegalArgumentException("Terrain configuration must contain the base options and optional advanced settings");
        }
        double height = number(values, "height");
        if (height != Math.rint(height) || height > Integer.MAX_VALUE || height < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Height must be an integer");
        }
        Map<String, Object> advanced = Map.of();
        if (values.containsKey("advanced")) {
            if (!(values.get("advanced") instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("advanced must be an object");
            }
            advanced = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Invalid advanced key");
                advanced.put(key, entry.getValue());
            }
        }
        return new TerrainOptions((int) height, number(values, "verticalScale"),
                number(values, "horizontalScale"), bool(values, "rollingHills"),
                bool(values, "undergroundRivers"), bool(values, "lavaTunnels"),
                bool(values, "junglePillars"), advanced);
    }

    public Map<String, Object> map() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("height", height);
        result.put("verticalScale", verticalScale);
        result.put("horizontalScale", horizontalScale);
        result.put("rollingHills", rollingHills);
        result.put("undergroundRivers", undergroundRivers);
        result.put("lavaTunnels", lavaTunnels);
        result.put("junglePillars", junglePillars);
        result.put("advanced", advanced);
        return result;
    }

    public double number(String key) {
        Object value = advanced.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Unknown numeric advanced key: " + key);
        return number.doubleValue();
    }

    public boolean flag(String key) {
        Object value = advanced.get(key);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Unknown boolean advanced key: " + key);
        return flag;
    }

    public int minY() {
        return (int) number("global_terrain.min_y");
    }

    public int maxY() {
        return minY() + height;
    }

    public TerrainHeight heightRange() {
        return new TerrainHeight(minY(), height);
    }

    public static List<Map<String, Object>> schema() {
        return TerrainAdvancedOptions.schema();
    }

    private static double number(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(key + " must be a finite number");
        }
        return value.doubleValue();
    }

    private static boolean bool(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return value;
    }

    private static void requireScale(String key, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
    }
}
