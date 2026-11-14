package com.lunarboreal.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TerrainWorldFilesTest {
    @TempDir Path root;

    @Test void removesOnlyTheRegisteredDimensionAndKeepsOtherWorlds() throws Exception {
        Path target = TerrainWorldSpec.dimensionPath(root, "alpine");
        Files.createDirectories(target.resolve("region"));
        Files.writeString(target.resolve("region/r.0.0.mca"), "chunks");
        Path other = Files.createDirectories(root.resolve("dimensions/lc_terrain/other"));
        Files.writeString(other.resolve("level.dat"), "keep");
        Files.writeString(root.resolve("level.dat"), "primary");

        TerrainWorldFiles.delete(root, "alpine", TerrainWorldFiles.deletionTarget(root, "alpine", target));

        assertFalse(Files.exists(target));
        assertEquals("keep", Files.readString(other.resolve("level.dat")));
        assertEquals("primary", Files.readString(root.resolve("level.dat")));
    }

    @Test void refusesMismatchedFoldersAndTargetsOutsideTheDimension() throws Exception {
        Path target = Files.createDirectories(TerrainWorldSpec.dimensionPath(root, "alpine"));
        Path other = Files.createDirectories(root.resolve("other"));
        assertThrows(java.io.IOException.class, () -> TerrainWorldFiles.deletionTarget(root, "alpine", other));
        assertThrows(java.io.IOException.class, () -> TerrainWorldFiles.delete(root, "alpine", root));
        assertThrows(IllegalArgumentException.class, () -> TerrainWorldFiles.deletionTarget(root, "../world", null));
        assertTrue(Files.exists(target));
        assertTrue(Files.exists(other));
    }

    @Test void deletesLegacyDimensionWithoutRenamingOrTouchingCurrentSiblings() throws Exception {
        Path target = Files.createDirectories(root.resolve("dimensions").resolve(TerrainNamespace.LEGACY).resolve("alpine"));
        Files.writeString(target.resolve("level.dat"), "legacy");
        Path current = Files.createDirectories(root.resolve("dimensions/lc_terrain/other"));
        Files.writeString(current.resolve("level.dat"), "current");
        TerrainWorldFiles.delete(root, "alpine", TerrainWorldFiles.deletionTarget(root, "alpine", target));
        assertFalse(Files.exists(target));
        assertEquals("current", Files.readString(current.resolve("level.dat")));
    }
}
