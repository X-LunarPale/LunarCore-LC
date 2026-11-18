package com.lunarboreal.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackSafetyTest {
    private AutoCloseable materials;

    @BeforeEach void setup() throws Exception { materials = RespawnTestWorld.materials(); }
    @AfterEach void cleanup() throws Exception { materials.close(); }

    @Test void safeDeathLocationKeepsExactCoordinatesAndOrientation() {
        var terrain = new RespawnTestWorld("safe");
        var death = new Location(terrain.world, .4, 80, .6, 125, 30);
        assertEquals(death, SafeLocation.findBack(death, 20));
    }

    @Test void searchesTheWholeCurrentLayerBeforeMovingUp() {
        var terrain = new RespawnTestWorld("cave").ground(null)
                .set(0, 80, 0, Material.STONE).set(20, 79, 0, Material.STONE);
        assertEquals(new Location(terrain.world, 20.5, 80, .5),
                SafeLocation.findBack(new Location(terrain.world, .5, 80, .5), 20));
    }

    @Test void searchesYPlusOneOnlyAfterExhaustingTheCurrentLayer() {
        var terrain = new RespawnTestWorld("cave").ground(null)
                .set(0, 82, 0, Material.STONE).set(20, 80, 0, Material.STONE);
        assertEquals(new Location(terrain.world, 20.5, 81, .5),
                SafeLocation.findBack(new Location(terrain.world, .5, 80, .5), 20));
    }

    @Test void doesNotSearchBelowTheDeathLayer() {
        var terrain = new RespawnTestWorld("below").ground(79);
        assertNull(SafeLocation.findBack(new Location(terrain.world, .5, 85, .5), 20));
    }

    @Test void searchesAtLeastTwentyBlocksEvenWithAnOldSmallRadius() {
        var terrain = new RespawnTestWorld("void").ground(null).set(20, 79, 0, Material.STONE);
        assertEquals(new Location(terrain.world, 20.5, 80, .5),
                SafeLocation.findBack(new Location(terrain.world, .5, -90, .5), 3));
    }

    @Test void canSearchBeyondTwentyWhenConfigured() {
        var terrain = new RespawnTestWorld("void").ground(null).set(24, 79, 0, Material.STONE);
        assertNull(SafeLocation.findBack(new Location(terrain.world, .5, 80, .5), 20));
        assertEquals(24, SafeLocation.findBack(new Location(terrain.world, .5, 80, .5), 24).getBlockX());
    }

    @Test void rejectsLiquidFeetAndUnsafeSupport() {
        for (Material hazard : new Material[]{Material.WATER, Material.LAVA, Material.POWDER_SNOW}) {
            var terrain = new RespawnTestWorld("hazard").set(0, 80, 0, hazard);
            Location result = SafeLocation.findBack(new Location(terrain.world, .5, 80, .5), 20);
            assertNotNull(result);
            assertFalse(result.getBlockX() == 0 && result.getBlockZ() == 0, hazard.name());
        }
    }

    @Test void neverUsesNetherRoofForADeathBelowIt() {
        var terrain = new RespawnTestWorld("nether", World.Environment.NETHER, 0, 256)
                .ground(null).set(0, 127, 0, Material.STONE).set(20, 110, 0, Material.STONE);
        assertEquals(111, SafeLocation.findBack(new Location(terrain.world, .5, 100, .5), 20).getBlockY());
    }

    @Test void emptyWorldReturnsNullWithoutChangingBlocks() {
        var terrain = new RespawnTestWorld("empty").ground(null);
        assertNull(SafeLocation.findBack(new Location(terrain.world, .5, 500, .5), 20));
        assertEquals(0, terrain.writes);
    }
}
