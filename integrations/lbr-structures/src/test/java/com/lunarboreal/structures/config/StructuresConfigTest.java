package com.lunarboreal.structures.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class StructuresConfigTest {
    @Test void defaultsAreVanillaAndWorldsPreserveExplicitFalse() {
        var base = new GenerationPolicy(Map.of(StructureFamily.WITCH_HUTS, true), false);
        var config = new StructuresConfig(0, base, Map.of("minecraft:overworld", Map.of("witch_huts", false, "dungeons", true)));
        assertFalse(StructuresConfig.initial().defaults().anyEnabled());
        assertFalse(config.forWorld("minecraft:overworld").enabled(StructureFamily.WITCH_HUTS));
        assertTrue(config.forWorld("minecraft:overworld").enabled(StructureFamily.DUNGEONS));
        assertTrue(config.forWorld("lbr_terrain:resources").enabled(StructureFamily.WITCH_HUTS));
        assertEquals(config, StructuresConfig.parse(config.map()));
    }

    @Test void saveDoesNotChangeActiveSnapshotAndRejectsStaleRevisions(@TempDir Path dir) throws Exception {
        var store = new ConfigStore(dir.resolve("structures.json"));
        var active = store.saved();
        var request = new StructuresConfig(0, new GenerationPolicy(Map.of(StructureFamily.MINESHAFTS, true), false), Map.of());
        assertEquals(1, store.save(request).revision());
        assertFalse(active.defaults().enabled(StructureFamily.MINESHAFTS));
        assertTrue(store.saved().defaults().enabled(StructureFamily.MINESHAFTS));
        assertThrows(IllegalArgumentException.class, () -> store.save(request));
        assertEquals(store.saved(), new ConfigStore(dir.resolve("structures.json")).saved());
        assertSame(store.saved(), store.save(store.saved()));
    }

    @Test void failedSaveLeavesPublishedConfigurationAndDiskUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("structures.json");
        var store = new ConfigStore(file);
        Files.createDirectory(file); // Force the atomic replacement to fail.
        var request = new StructuresConfig(0, new GenerationPolicy(Map.of(StructureFamily.STRONGHOLDS, true), false), Map.of());
        assertThrows(java.io.IOException.class, () -> store.save(request));
        assertEquals(StructuresConfig.initial(), store.saved());
        assertTrue(Files.isDirectory(file));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test void corruptFilesAndUnknownFieldsAreNotReset(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("structures.json");
        Files.writeString(file, "{broken");
        assertThrows(java.io.IOException.class, () -> new ConfigStore(file));
        assertEquals("{broken", Files.readString(file));
        var bad = new HashMap<>(StructuresConfig.initial().map());
        bad.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> StructuresConfig.parse(bad));
        assertThrows(IllegalArgumentException.class, () -> StructuresConfig.bool("false"));
        assertThrows(IllegalArgumentException.class, () -> StructuresConfig.integer(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new StructuresConfig(0, GenerationPolicy.vanilla(), Map.of("minecraft:../world", Map.of())));
    }

    @Test void inputMapsCannotMutateSnapshots() {
        var input = new HashMap<StructureFamily, Boolean>();
        input.put(StructureFamily.DUNGEONS, true);
        var policy = new GenerationPolicy(input, false);
        input.clear();
        assertTrue(policy.enabled(StructureFamily.DUNGEONS));
        assertThrows(UnsupportedOperationException.class, () -> policy.structures().clear());
    }
}
