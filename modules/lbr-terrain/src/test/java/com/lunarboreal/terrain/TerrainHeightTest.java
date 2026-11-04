package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerrainHeightTest {
    @Test void customBoundsRoundTripWithoutChangingLegacyDimensionKeys() {
        TerrainOptions options = new TerrainOptions(1024, 1.8, 1.2, true, true, true, true,
                Map.of("global_terrain.min_y", -128));
        assertEquals(-128, options.minY());
        assertEquals(896, options.maxY());
        assertEquals(options, TerrainOptions.parse(options.map()));
        assertEquals("overworld_128_1024", options.heightRange().dimensionName());
        assertEquals("overworld_640", TerrainOptions.defaults().heightRange().dimensionName());
        assertEquals(options.heightRange(), TerrainHeight.fromConfig(Map.of("min-y", -128, "max-y", 896)));
    }

    @Test void rejectsBoundsTheNativeDimensionCannotRepresent() {
        for (int[] bounds : new int[][]{{-65, 704}, {-2048, 2816}, {16, 640}, {-64, 641},
                {-64, 256}, {-64, 4064}, {-2032, 2048}}) {
            assertThrows(IllegalArgumentException.class, () -> new TerrainHeight(bounds[0], bounds[1]));
        }
        assertThrows(IllegalArgumentException.class,
                () -> TerrainHeight.fromConfig(Map.of("min-y", -64.5, "max-y", 640)));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainHeight.fromConfig(Map.of("min-y", "-64", "max-y", 640)));
    }

    @Test void noiseModelUsesBothConfiguredBounds() {
        TerrainOptions options = new TerrainOptions(1024, 1.8, 1.2, true, true, true, true,
                Map.of("global_terrain.min_y", -128));
        var settings = TectonicModel.noiseSettings(options);
        assertEquals(-128, settings.getAsJsonObject("noise").get("min_y").getAsInt());
        assertEquals(1024, settings.getAsJsonObject("noise").get("height").getAsInt());
    }
}
