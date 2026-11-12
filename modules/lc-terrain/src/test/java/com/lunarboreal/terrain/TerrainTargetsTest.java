package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainTargetsTest {
    @Test void allEightOfficialTargetsAreAvailableAndHonorFeatureSwitches() {
        assertEquals(List.of("mountain_range", "underground_river", "jungle_pillars", "rolling_hills",
                "badlands_canyon", "badlands_plateaus", "desert_dunes", "cherry_valley"), TerrainTargets.names());
        TerrainOptions disabled = new TerrainOptions(384, 1.125, 1, false, false, true, false);
        for (String id : TerrainTargets.names()) {
            assertEquals(!List.of("underground_river", "jungle_pillars", "rolling_hills").contains(id),
                    TerrainTargets.find(id).enabled(disabled), id);
        }
    }

    @Test void boundaryComparisonUsesTheUpstreamThreeDecimalRounding() {
        var target = TerrainTargets.find("underground_river");
        assertTrue(target.matches(key -> switch (key) {
            case "noise/continent/ridges_folded" -> 0.02549;
            case "noise/raw_continents" -> -0.10049;
            default -> 0.22549;
        }));
        assertFalse(target.matches(key -> key.equals("noise/continent/ridges_folded") ? 0.02551 : 0));
        assertFalse(target.matches(key -> Double.NaN));
    }
}
