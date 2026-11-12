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
        assertEquals(root.toRealPath().resolve("dimensions/lc_terrain/alpine"), target);
        Files.createDirectories(target);
        Path existing = target.resolve("existing.dat");
        Files.writeString(existing, "preserve");
        assertEquals(target, TerrainWorldSpec.dimensionPath(root, "alpine"));
        assertEquals("preserve", Files.readString(existing));
    }

    @Test void keepsExistingWorldDirectoriesAndPersistentDimensionKeys() throws Exception {
        Path existing = root.resolve("dimensions").resolve(TerrainNamespace.LEGACY).resolve("alpine");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("level.dat"), "existing-world");
        TerrainWorldSpec spec = new TerrainWorldSpec("alpine", 123, TerrainOptions.defaults(), true);

        assertEquals(existing.toRealPath(), TerrainWorldSpec.dimensionPath(root, "alpine"));
        assertEquals(TerrainNamespace.LEGACY + ":alpine", spec.key(root));
        assertEquals("existing-world", Files.readString(existing.resolve("level.dat")));
        assertFalse(Files.exists(root.resolve("dimensions").resolve(TerrainNamespace.CURRENT)));
    }

    @Test void refusesToSelectBetweenTwoDifferentWorldsWithTheSameName() throws Exception {
        for (String namespace : new String[]{TerrainNamespace.CURRENT, TerrainNamespace.LEGACY}) {
            Path world = root.resolve("dimensions").resolve(namespace).resolve("alpine");
            Files.createDirectories(world);
            Files.writeString(world.resolve("level.dat"), namespace);
        }

        assertThrows(java.io.IOException.class, () -> TerrainWorldSpec.dimensionPath(root, "alpine"));
        for (String namespace : new String[]{TerrainNamespace.CURRENT, TerrainNamespace.LEGACY}) {
            assertEquals(namespace, Files.readString(root.resolve("dimensions").resolve(namespace)
                    .resolve("alpine/level.dat")));
        }
    }
}
