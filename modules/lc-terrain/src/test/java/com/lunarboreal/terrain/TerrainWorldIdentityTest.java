package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainWorldIdentityTest {
    @Test void resolvesRegisteredDimensionsBeforeTheyHaveLoaded() {
        for (String reference : List.of("jixian", "lc_terrain:jixian", "lc_terrain_jixian",
                TerrainNamespace.LEGACY + ":jixian", TerrainNamespace.LEGACY + "_jixian",
                "minecraft:lc_terrain_jixian", "minecraft:" + TerrainNamespace.LEGACY + "_jixian")) {
            assertEquals("jixian", TerrainNamespace.registeredName(reference, List.of("jixian")), reference);
        }
    }

    @Test void leavesUnregisteredAndUnrelatedMinecraftWorldsAlone() {
        assertNull(TerrainNamespace.registeredName("minecraft:jixian", List.of("jixian")));
        assertNull(TerrainNamespace.registeredName("lc_terrain:missing", List.of("jixian")));
        assertNull(TerrainNamespace.registeredName("other:jixian", List.of("jixian")));
        assertNull(TerrainNamespace.registeredName(null, List.of("jixian")));
    }

    @Test void requiresCanonicalKeyWhenOneRegistrationLooksLikeAnotherDisplayName() {
        List<String> names = List.of("jixian", "lc_terrain_jixian");
        assertThrows(IllegalArgumentException.class,
                () -> TerrainNamespace.registeredName("lc_terrain_jixian", names));
        assertEquals("jixian", TerrainNamespace.registeredName("lc_terrain:jixian", names));
        assertEquals("lc_terrain_jixian",
                TerrainNamespace.registeredName("lc_terrain:lc_terrain_jixian", names));
    }
}
