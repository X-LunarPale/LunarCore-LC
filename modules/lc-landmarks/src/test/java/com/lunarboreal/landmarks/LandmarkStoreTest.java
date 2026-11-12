package com.lunarboreal.landmarks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkStoreTest {
    @TempDir Path temp;
    private LandmarkBook example() {
        return LandmarkBook.empty().submit(UUID.randomUUID(), "Builder", 9, "world", -3, 80, 17,
                "中文地标", "Glass = greenhouse", 3, 100)
                .moderate(1, 1, Landmark.Status.REJECTED, "Moderator", "Add detail", 200);
    }
    @Test void exactSnapshotSurvivesAtomicReplacement() throws Exception {
        Path path = temp.resolve("landmarks.properties");
        LandmarkStore store = new LandmarkStore(path);
        assertEquals(LandmarkBook.empty(), store.load());
        LandmarkBook first = example();
        store.save(first);
        assertEquals(first, store.load());
        LandmarkBook changed = first.edit(1, first.find(1).owner(), 2, "Revised", "", 3, 300);
        store.save(changed);
        assertEquals(changed, store.load());
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
    @Test void corruptInputFailsWithoutResettingTheFile() throws Exception {
        Path path = temp.resolve("landmarks.properties");
        for (String corrupt : new String[]{"format=2\ncount=0\nnextId=1\n", "format=1\ncount=1\nnextId=2\n",
                "format=1\ncount=0\nnextId=0\n", "format=\\uGGGG\n"}) {
            Files.writeString(path, corrupt, StandardCharsets.ISO_8859_1);
            assertThrows(IOException.class, () -> new LandmarkStore(path).load());
            assertEquals(corrupt, Files.readString(path, StandardCharsets.ISO_8859_1));
        }
    }
    @Test void replacementFailurePreservesExistingTarget() throws Exception {
        Path target = Files.createDirectory(temp.resolve("target"));
        Files.writeString(target.resolve("keep"), "original");
        assertThrows(IOException.class, () -> LandmarkStore.atomicWrite(target, new byte[]{1, 2, 3}));
        assertEquals("original", Files.readString(target.resolve("keep")));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
}
