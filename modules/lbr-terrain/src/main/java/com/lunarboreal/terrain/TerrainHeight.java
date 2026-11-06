package com.lunarboreal.terrain;

import java.util.List;
import java.util.Map;

/** Dimension bounds are registered before clients log in. */
public record TerrainHeight(int minY, int height) {
    public static final List<TerrainHeight> DEFAULTS = TerrainOptions.HEIGHTS.stream()
            .map(height -> new TerrainHeight(-64, height)).toList();

    public TerrainHeight {
        if (minY < -2032 || minY > 0 || minY % 16 != 0
                || height < 256 || height > 4064 || height % 16 != 0
                || minY + height < 256 || minY + height > 2032) {
            throw new IllegalArgumentException("Height bounds must be multiples of 16 within -2032..2032, with maxY >= 256");
        }
    }

    public int maxY() {
        return minY + height;
    }

    public String dimensionName() {
        return minY == -64 ? "overworld_" + height : "overworld_" + (-minY) + "_" + height;
    }

    public Map<String, Object> map() {
        return Map.of("minY", minY, "maxY", maxY(), "height", height);
    }

    public static TerrainHeight fromConfig(Map<?, ?> values) {
        Object lower = values.get("min-y"), upper = values.get("max-y");
        if (!(lower instanceof Number min) || !(upper instanceof Number max)
                || min.doubleValue() != min.intValue() || max.doubleValue() != max.intValue()) {
            throw new IllegalArgumentException("height-ranges entries require integer min-y and max-y");
        }
        return new TerrainHeight(min.intValue(), max.intValue() - min.intValue());
    }
}
