package com.lunarboreal.terrain.nms;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeWorldCreatorTest {
    @Test void createsANamespacedDimensionWithoutTheLegacyWorldName() {
        NamespacedKey key = new NamespacedKey("lc_terrain", "tectonic");
        WorldCreator creator = WorldCreator.ofKey(key)
                .environment(World.Environment.NORMAL)
                .seed(123456789L)
                .generateStructures(true);

        assertEquals(key, creator.key());
        assertEquals("lc_terrain_tectonic", creator.name());
        assertEquals(123456789L, creator.seed());
        assertEquals(World.Environment.NORMAL, creator.environment());
        assertTrue(creator.generateStructures());
    }
}
