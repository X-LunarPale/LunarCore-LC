package com.lunarboreal.world;

import org.junit.jupiter.api.Test;
import org.bukkit.World;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldOptionsTest {
    @TempDir Path root;
    @Test void rejectsTraversalAndDeviceNames() {
        for(String n:new String[]{"../world","a/b","a\\b","C:world",".","CON","LPT1","trailing.",""})
            assertThrows(IllegalArgumentException.class,()->WorldOptions.validateName(n),n);
        assertEquals("world-2",WorldOptions.validateName("world-2"));
    }
    @Test void safePathsStayWithinRoot() throws Exception {
        assertEquals(root.toRealPath().resolve("extra"),WorldOptions.safePath(root,"extra"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.safePath(root,"../extra"));
    }
    @Test void acceptsLegacyAndExactPaperDimensionPaths() throws Exception {
        Path save = Files.createDirectory(root.resolve("world"));
        Files.createFile(save.resolve("level.dat"));
        assertEquals(save.toRealPath(),WorldOptions.loadedSaveRoot(root,"world",save,"minecraft","overworld"));
        for(String dim:new String[]{"overworld","the_nether","the_end"}) {
            Path folder=Files.createDirectories(save.resolve("dimensions/minecraft/"+dim));
            assertEquals(save.toRealPath(),WorldOptions.loadedSaveRoot(root,"world",folder,"minecraft",dim));
        }
        Path custom=Files.createDirectories(save.resolve("dimensions/minecraft/testflat"));
        assertEquals(save.toRealPath(),WorldOptions.loadedSaveRoot(root,"testflat",custom,"minecraft","testflat"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.loadedSaveRoot(root,"testflat",custom,"minecraft","other"));
        Path child=Files.createDirectory(save.resolve("arbitrary"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.loadedSaveRoot(root,"world",child,"minecraft","overworld"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.loadedSaveRoot(root,"world",save.resolve("dimensions/minecraft/the_end"),"minecraft","overworld"));
    }
    @Test void rejectsMissingLevelAndOutsideContainer() throws Exception {
        Path folder=Files.createDirectories(root.resolve("extra/dimensions/minecraft/overworld"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.loadedSaveRoot(root,"extra",folder,"minecraft","overworld"));
        Path container=Files.createDirectory(root.resolve("container"));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.loadedSaveRoot(container,"extra",folder,"minecraft","overworld"));
    }
    @Test void rejectsFractionalTimeAndInvalidBoolean() {
        assertThrows(IllegalArgumentException.class,()->WorldOptions.parse("extra",Map.of("time",2.5),null));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.parse("extra",Map.of("pvp","yes"),null));
        assertThrows(IllegalArgumentException.class,()->WorldOptions.parse("extra",Map.of("time",24000),null));
    }
    @Test void crossDimensionAccessDefaultsToAllowedAndCanBeDisabled() {
        WorldOptions allowed = WorldOptions.parse("extra",Map.of(),null);
        WorldOptions blocked = WorldOptions.parse("extra",Map.of("allowCrossDimension",false),null);
        assertTrue(WorldOptions.allowsCrossDimension(allowed,World.Environment.NORMAL,World.Environment.NETHER));
        assertFalse(WorldOptions.allowsCrossDimension(blocked,World.Environment.NORMAL,World.Environment.NETHER));
        assertTrue(WorldOptions.allowsCrossDimension(blocked,World.Environment.NORMAL,World.Environment.NORMAL));
        assertFalse(blocked.map().get("allowCrossDimension") instanceof String);
    }

    @Test void exactSeedAndFixedDaySurviveRoundTrip() {
        WorldOptions o=WorldOptions.parse("extra",Map.of("seed","9223372036854775807","daylightCycle",false,"time",1000),null);
        WorldOptions reloaded=WorldOptions.parse("extra",o.map(),null);
        assertEquals(Long.MAX_VALUE,reloaded.seed());
        assertFalse(reloaded.daylightCycle());assertEquals(1000,reloaded.time());
    }
}
