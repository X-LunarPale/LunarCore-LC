package com.lunarboreal.structures.config;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable world-generation snapshot. No mutation from the web/request thread. */
public record GenerationPolicy(Map<StructureFamily, Boolean> structures, boolean overwriteExisting) {
    public GenerationPolicy {
        var complete = new EnumMap<StructureFamily, Boolean>(StructureFamily.class);
        for (var family : StructureFamily.values()) complete.put(family, Boolean.TRUE.equals(structures.get(family)));
        structures = Map.copyOf(complete);
    }

    public static GenerationPolicy vanilla() { return new GenerationPolicy(Map.of(), false); }
    public boolean enabled(StructureFamily family) { return structures.get(family); }
    public boolean anyEnabled() { return structures.containsValue(true); }

    public Map<String, Object> map() {
        var values = new LinkedHashMap<String, Boolean>();
        for (var family : StructureFamily.values()) values.put(family.id, enabled(family));
        return Map.of("structures", values, "overwriteExisting", overwriteExisting);
    }

    public static GenerationPolicy parse(Map<String, Object> input) {
        StructuresConfig.exactKeys(input, "structures", "overwriteExisting");
        var values = StructuresConfig.object(input.get("structures"));
        if (values.size() != StructureFamily.values().length) throw new IllegalArgumentException("缺少结构开关");
        var result = new EnumMap<StructureFamily, Boolean>(StructureFamily.class);
        values.forEach((key, value) -> result.put(StructureFamily.require(key), StructuresConfig.bool(value)));
        return new GenerationPolicy(result, StructuresConfig.bool(input.get("overwriteExisting")));
    }
}
