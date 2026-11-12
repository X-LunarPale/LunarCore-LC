package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerrainSpawnCoordinatesTest {
    @Test void acceptsIntegralJsonNumbersAtHorizontalAndVerticalBounds() {
        for (int height : TerrainOptions.HEIGHTS) {
            assertEquals(new TerrainModule.SpawnCoordinates(-29999984, -63, 29999984),
                    TerrainModule.spawnCoordinates(Map.of("x", -29999984.0, "y", -63.0, "z", 29999984.0),
                            -64, height - 64));
            assertEquals(new TerrainModule.SpawnCoordinates(29999984, height - 66, -29999984),
                    TerrainModule.spawnCoordinates(Map.of("x", 29999984, "y", height - 66, "z", -29999984),
                            -64, height - 64));
        }
    }

    @Test void rejectsCoordinatesOutsideTheWorldLimitsWithoutTruncatingThem() {
        for (String key : new String[]{"x", "z"}) {
            for (Number value : new Number[]{-29999985, 29999985, Long.MAX_VALUE}) {
                Map<String, Object> body = coordinates();
                body.put(key, value);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> TerrainModule.spawnCoordinates(body, -64, 960));
                assertTrue(error.getMessage().contains(key));
            }
        }
        for (int height : TerrainOptions.HEIGHTS) {
            for (int y : new int[]{-64, height - 65}) {
                assertThrows(IllegalArgumentException.class, () -> TerrainModule.spawnCoordinates(
                        Map.of("x", 0, "y", y, "z", 0), -64, height - 64));
            }
        }
    }

    @Test void rejectsMissingNonNumericFractionalAndNonFiniteCoordinates() {
        for (String key : new String[]{"x", "y", "z"}) {
            Map<String, Object> missing = coordinates();
            missing.remove(key);
            assertThrows(IllegalArgumentException.class,
                    () -> TerrainModule.spawnCoordinates(missing, -64, 960));
            for (Object invalid : new Object[]{null, "64", true, 0.5, Double.NaN,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                Map<String, Object> body = coordinates();
                body.put(key, invalid);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> TerrainModule.spawnCoordinates(body, -64, 960));
                assertTrue(error.getMessage().contains(key));
            }
        }
    }

    @Test void rejectsUnknownFieldsRatherThanIgnoringThem() {
        Map<String, Object> body = coordinates();
        body.put("world", "another_world");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TerrainModule.spawnCoordinates(body, -64, 960));
        assertTrue(error.getMessage().contains("world"));
    }

    private static Map<String, Object> coordinates() {
        return new LinkedHashMap<>(Map.of("x", 0, "y", 64, "z", 0));
    }
}
