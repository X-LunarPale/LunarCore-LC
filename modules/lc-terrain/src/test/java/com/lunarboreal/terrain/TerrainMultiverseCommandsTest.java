package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMultiverseCommandsTest {
    @Test void acceptsPlayerConsoleAndNamespacedMultiverseCommands() {
        for (String command : List.of("/mv load lc_terrain_jixian",
                "mv load lc_terrain:jixian", "/multiverse-core:mv load lc_terrain_jixian")) {
            var request = TerrainMultiverseCommands.request(command);
            assertNotNull(request, command);
            assertEquals("load", request.operation());
            assertEquals("jixian", TerrainNamespace.registeredName(request.world(), List.of("jixian")));
            assertDoesNotThrow(request::validate);
        }
    }

    @Test void keepsNativeImportsOnTheirSavedGenerationSettings() {
        var request = TerrainMultiverseCommands.request(
                "/mv import minecraft:lc_terrain_jixian normal --skip-folder-check");
        assertNotNull(request);
        assertEquals("import", request.operation());
        assertEquals("jixian", TerrainNamespace.registeredName(request.world(), List.of("jixian")));
        assertDoesNotThrow(request::validate);
        assertThrows(IllegalArgumentException.class,
                TerrainMultiverseCommands.request("/mv import lc_terrain_jixian nether")::validate);
    }

    @Test void ignoresOtherCommandsAndLeavesOrdinaryWorldsForMultiverse() {
        assertNull(TerrainMultiverseCommands.request("/mv list"));
        assertNull(TerrainMultiverseCommands.request("/mv delete lc_terrain_jixian"));
        assertNull(TerrainMultiverseCommands.request("/other load lc_terrain_jixian"));
        var ordinary = TerrainMultiverseCommands.request("/mv load survival");
        assertNotNull(ordinary);
        assertNull(TerrainNamespace.registeredName(ordinary.world(), List.of("jixian")));
    }
}
