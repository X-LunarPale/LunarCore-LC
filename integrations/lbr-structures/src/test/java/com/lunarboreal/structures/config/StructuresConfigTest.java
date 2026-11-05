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
        var base = new GenerationPolicy(Map.of(StructureFamily.WITCH_HUTS, true));
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
        var request = new StructuresConfig(0, new GenerationPolicy(Map.of(StructureFamily.MINESHAFTS, true)), Map.of());
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
        var request = new StructuresConfig(0, new GenerationPolicy(Map.of(StructureFamily.STRONGHOLDS, true)), Map.of());
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
        var policy = new GenerationPolicy(input);
        input.clear();
        assertTrue(policy.enabled(StructureFamily.DUNGEONS));
        assertThrows(UnsupportedOperationException.class, () -> policy.structures().clear());
    }

    @Test void settingsSavedWithTheRemovedOverwriteSwitchStillLoad(@TempDir Path directory) throws Exception {
        var file = directory.resolve("structures.json");
        var structures = new HashMap<String, Boolean>();
        for (var family : StructureFamily.values()) structures.put(family.id, family == StructureFamily.WITCH_HUTS);
        var gson = new com.google.gson.Gson();
        Files.writeString(file, gson.toJson(Map.of("schema", 1, "revision", 4,
                "defaults", Map.of("structures", structures, "overwriteExisting", true),
                "worlds", Map.of("minecraft:overworld", Map.of("dungeons", true, "overwriteExisting", true), "minecraft:the_end", Map.of("overwriteExisting", false)))));
        var saved = new ConfigStore(file).saved();
        assertEquals(4, saved.revision());
        assertTrue(saved.defaults().enabled(StructureFamily.WITCH_HUTS));
        assertTrue(saved.forWorld("minecraft:overworld").enabled(StructureFamily.DUNGEONS));
        assertEquals(java.util.Set.of("minecraft:overworld"), saved.worlds().keySet());
    }

    @Test void settingsSavedBeforeLaterFamiliesExistedStillLoadWithThemOff(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("structures.json");
        var flags = new java.util.LinkedHashMap<String, Boolean>();
        for (var family : StructureFamily.values())
            if (family != StructureFamily.EXTRAS && family != StructureFamily.BRIDGES) flags.put(family.id, family == StructureFamily.DUNGEONS);
        Files.writeString(file, new com.google.gson.Gson().toJson(Map.of("schema", 1, "revision", 3,
                "defaults", Map.of("structures", flags), "worlds", Map.of())));
        var loaded = new ConfigStore(file).saved();
        assertEquals(3, loaded.revision());
        assertTrue(loaded.defaults().enabled(StructureFamily.DUNGEONS));
        assertFalse(loaded.defaults().enabled(StructureFamily.EXTRAS));
        assertFalse(loaded.defaults().enabled(StructureFamily.BRIDGES));
    }
}
