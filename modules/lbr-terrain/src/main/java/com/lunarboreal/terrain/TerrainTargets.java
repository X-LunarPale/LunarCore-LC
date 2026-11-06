package com.lunarboreal.terrain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Tectonic 3.0.29's inclusive, three-decimal locate conditions. */
public final class TerrainTargets {
    public record Bound(String key, double minimum, double maximum) {
        boolean contains(double sample) {
            double rounded = Math.round(sample * 1000) / 1000d;
            return Double.isFinite(sample) && rounded >= minimum && rounded <= maximum;
        }
    }

    public record Target(String id, List<Bound> conditions) {
        public boolean enabled(TerrainOptions options) {
            return switch (id) {
                case "underground_river" -> options.undergroundRivers();
                case "jungle_pillars" -> options.junglePillars();
                case "rolling_hills" -> options.rollingHills();
                default -> true;
            };
        }

        public boolean matches(ToDoubleFunction<String> samples) {
            return conditions.stream().allMatch(bound -> bound.contains(samples.applyAsDouble(bound.key())));
        }
    }

    private static final Map<String, Target> TARGETS = targets();

    private TerrainTargets() {}

    public static List<String> names() {
        return List.copyOf(TARGETS.keySet());
    }

    public static Target find(String id) {
        Target target = TARGETS.get(id);
        if (target == null) throw new IllegalArgumentException("Unknown terrain target: " + id);
        return target;
    }

    private static Map<String, Target> targets() {
        Map<String, Target> targets = new LinkedHashMap<>();
        add(targets, "mountain_range",
                b("noise/continent/erosion_folded", 0, 0.05),
                b("noise/raw_continents", 0.1, 64));
        add(targets, "underground_river",
                b("noise/continent/ridges_folded", 0, 0.025),
                b("noise/raw_continents", -0.1, 64),
                b("noise/continent/erosion_folded", 0, 0.225));
        add(targets, "jungle_pillars",
                b("noise/continent/ridges", -0.75, -0.2),
                b("noise/vegetation_index", 4, 5),
                b("noise/temperature_index", 4, 4),
                b("noise/region_selector", 0.1, 64),
                b("noise/continent/erosion", -0.8, -0.45),
                b("noise/raw_continents", 0.1, 64));
        add(targets, "rolling_hills",
                b("noise/continent/ridges_folded", 0.2, 64),
                b("noise/vegetation_index", 0, 2),
                b("noise/temperature_index", 0, 3),
                b("noise/region_selector", 0.1, 64),
                b("noise/continent/erosion", -0.8, -0.45),
                b("noise/raw_continents", 0.1, 64));
        add(targets, "badlands_canyon",
                b("noise/continent/ridges_folded", 0.2, 64),
                b("noise/temperature_index", 5, 5),
                b("noise/region_selector", -64, 0),
                b("noise/continent/erosion", 0.45, 0.8),
                b("noise/raw_continents", 0.35, 64));
        add(targets, "badlands_plateaus",
                b("noise/continent/ridges_folded", 0.2, 64),
                b("noise/temperature_index", 5, 5),
                b("noise/region_selector", -64, 0),
                b("noise/continent/erosion", -8, -0.45),
                b("noise/raw_continents", 0.35, 64));
        add(targets, "desert_dunes",
                b("noise/continent/ridges_folded", 0.2, 64),
                b("noise/temperature_index", 5, 5),
                b("noise/region_selector", 0.1, 64),
                b("noise/continent/erosion", 0.45, 0.8),
                b("noise/raw_continents", 0.1, 64));
        add(targets, "cherry_valley",
                b("noise/continent/ridges_folded", 0.4, 64),
                b("noise/vegetation_index", 0, 2),
                b("noise/temperature_index", 3, 3),
                b("noise/region_selector", -64, 0),
                b("noise/continent/erosion", -0.8, -0.45),
                b("noise/raw_continents", 0.35, 64));
        return java.util.Collections.unmodifiableMap(targets);
    }

    private static Bound b(String key, double minimum, double maximum) {
        return new Bound(key, minimum, maximum);
    }

    private static void add(Map<String, Target> targets, String id, Bound... bounds) {
        targets.put(id, new Target(id, List.of(bounds)));
    }
}
