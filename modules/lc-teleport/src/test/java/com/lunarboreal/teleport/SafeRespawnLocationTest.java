package com.lunarboreal.teleport;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafeRespawnLocationTest {
    private AutoCloseable materials;

    @BeforeEach void installMaterials() throws Exception {
        materials = RespawnTestWorld.materials();
    }

    @AfterEach void restoreMaterials() throws Exception {
        materials.close();
    }

    @Test void safeSpawnIsCenteredWithoutChangingTheInputOrOrientation() {
        var fixture = new RespawnTestWorld("flat");
        var spawn = new Location(fixture.world, 4.1, 80, -3.9, 135, 20);
        var original = spawn.clone();
        Location result = SafeLocation.findRespawn(spawn, 0);
        assertEquals(new Location(fixture.world, 4.5, 80, -3.5, 135, 20), result);
        assertEquals(original, spawn);
        assertEquals(0, fixture.writes);
    }

    @Test void spawnBuriedAtY80UsesTheActualMountainSurfaceAtY251() {
        var fixture = new RespawnTestWorld("mountain").ground(250);
        Location result = SafeLocation.findRespawn(new Location(fixture.world, 0, 80, 0), 0);
        assertEquals(new Location(fixture.world, .5, 251, .5), result);
        assertTrue(fixture.heightMaps.contains(HeightMap.MOTION_BLOCKING_NO_LEAVES));
        assertEquals(0, fixture.writes);
    }

    @Test void floatingSpawnFindsTheGroundInsteadOfKeepingTheAirPosition() {
        var fixture = new RespawnTestWorld("floating");
        assertEquals(new Location(fixture.world, .5, 80, .5),
                SafeLocation.findRespawn(new Location(fixture.world, 0, 230, 0), 0));
    }

    @Test void liquidOrDangerousFeetAndDangerousFloorsAreNeverSelected() {
        for (Material hazard : List.of(Material.WATER, Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
                Material.COBWEB, Material.POWDER_SNOW)) {
            var fixture = new RespawnTestWorld("feet-" + hazard).set(0, 80, 0, hazard);
            assertNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0), hazard.name());
            assertEquals(0, fixture.writes);
        }
        for (Material hazard : List.of(Material.WATER, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS)) {
            var fixture = new RespawnTestWorld("floor-" + hazard).set(0, 79, 0, hazard);
            assertNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0), hazard.name());
        }
    }

    @Test void oneBlockOfHeadroomIsNotEnough() {
        var fixture = new RespawnTestWorld("low-ceiling", World.Environment.NORMAL, -64, 82)
                .set(0, 81, 0, Material.STONE);
        assertNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0));
        assertEquals(0, fixture.writes);
    }

    @Test void supportMustCoverThePlayerAndMustNotExtendAboveTheirFeet() {
        for (BoundingBox support : List.of(
                new BoundingBox(.375, 79, .375, .625, 80.5, .625),
                new BoundingBox(0, 79, 0, .19, 80, 1),
                new BoundingBox(0, 79, 0, 1, 80.25, 1))) {
            var fixture = new RespawnTestWorld("bad-support").bounds(0, 79, 0, support);
            assertNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0));
        }
    }

    @Test void theFullPlayerWidthMustFitInsideTheWorldBorder() {
        var fixture = new RespawnTestWorld("border").border(0, 0, 1.4);
        assertTrue(fixture.world.getWorldBorder().isInside(fixture.world.getSpawnLocation()));
        assertNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0));
        fixture.border(.5, .5, 2);
        assertNotNull(SafeLocation.findRespawn(fixture.world.getSpawnLocation(), 0));
    }

    @Test void surfaceAtTheHeightLimitCannotProvideTwoBlocksOfSpace() {
        var fixture = new RespawnTestWorld("height-limit", World.Environment.NORMAL, -64, 96).ground(94);
        assertNull(SafeLocation.findRespawn(new Location(fixture.world, 0, 80, 0), 0));
        assertEquals(0, fixture.writes);
    }

    @Test void nearbyDryGroundCanBeFoundButAnEmptyWorldReturnsNullWithoutBuilding() {
        var nearby = new RespawnTestWorld("nearby").set(0, 80, 0, Material.LAVA);
        Location result = SafeLocation.findRespawn(nearby.world.getSpawnLocation(), 1);
        assertNotNull(result);
        assertEquals(80, result.getBlockY());
        assertFalse(result.getBlockX() == 0 && result.getBlockZ() == 0);
        assertTrue(Math.abs(result.getBlockX()) <= 1 && Math.abs(result.getBlockZ()) <= 1);
        var empty = new RespawnTestWorld("void").ground(null);
        assertNull(SafeLocation.findRespawn(empty.world.getSpawnLocation(), 2));
        assertEquals(0, empty.writes);
    }
}
