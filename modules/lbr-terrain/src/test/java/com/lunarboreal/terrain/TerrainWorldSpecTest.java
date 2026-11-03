package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TerrainWorldSpecTest {
    @TempDir Path root;

    @Test void preservesSeedPrecisionAndRejectsLossyNumericInput() {
        assertEquals(Long.MAX_VALUE, TerrainWorldSpec.parseSeed("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, TerrainWorldSpec.parseSeed("-9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> TerrainWorldSpec.parseSeed("9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> TerrainWorldSpec.parseSeed(123.0));
        assertThrows(IllegalArgumentException.class, () -> TerrainWorldSpec.parseSeed("1e6"));
    }

    @Test void refusesTraversalAndReservedDeviceNames() {
        for (String name : new String[]{"../world", "world/../other", "CON", "con", "aux", "World", "x.y", "", "a".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> TerrainWorldSpec.validateName(name), name);
        }
        assertEquals("alpine_1", TerrainWorldSpec.validateName("alpine_1"));
    }

    @Test void confinesNewWorldToItsNamespaceAndKeepsExistingFiles() throws Exception {
        Path target = TerrainWorldSpec.dimensionPath(root, "alpine");
        assertEquals(root.toRealPath().resolve("dimensions/lbr_terrain/alpine"), target);
        Files.createDirectories(target);
        Path existing = target.resolve("existing.dat");
        Files.writeString(existing, "preserve");
        assertEquals(target, TerrainWorldSpec.dimensionPath(root, "alpine"));
        assertEquals("preserve", Files.readString(existing));
    }
}
