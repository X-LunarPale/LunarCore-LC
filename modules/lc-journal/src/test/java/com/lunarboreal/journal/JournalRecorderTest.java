package com.lunarboreal.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JournalRecorderTest {

    @TempDir Path directory;
    private final UUID uuid = UUID.randomUUID();

    private JournalEntry entry(String key, long time) {
        return new JournalEntry(uuid, "Alice", JournalEntry.Category.BIOMES, key,
                JournalEntry.readableKey(key), "world", time);
    }

    @Test void failureRetainsTimestampAndRetryWritesExactlyOnce() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        List<JournalEntry> written = new ArrayList<>();
        JournalRecorder recorder = new JournalRecorder(value -> {
            if (broken.get()) throw new SQLException("Database unavailable");
            written.add(value);
            return new JournalRepository.Saved(value, true);
        }, 10);
        var original = entry("minecraft:plains", 100);
        assertTrue(recorder.enqueue(original));
        assertThrows(SQLException.class, () -> recorder.drain(saved -> fail("Not persisted")));
        assertEquals(List.of(original), recorder.snapshot());
        assertTrue(recorder.enqueue(entry("minecraft:plains", 800)));
        broken.set(false);
        recorder.drain(saved -> assertTrue(saved.inserted()));
        recorder.drain(saved -> fail("Already drained"));
        assertEquals(List.of(original), written);
        assertEquals(0, recorder.size());
    }

    @Test void queueLimitDoesNotEvictPendingRecords() {
        JournalRecorder recorder = new JournalRecorder(value -> new JournalRepository.Saved(value, true), 1);
        var first = entry("minecraft:plains", 10);
        assertTrue(recorder.enqueue(first));
        assertFalse(recorder.enqueue(entry("minecraft:forest", 20)));
        assertTrue(recorder.enqueue(entry("minecraft:plains", 99)));
        assertEquals(List.of(first), recorder.snapshot());
    }

    @Test void snapshotRecoveryMergesEarlierRecordAndPreservesPartialProgress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JournalRecorder recorder = new JournalRecorder(value -> {
            if (calls.incrementAndGet() == 2) throw new SQLException("Second write failed");
            return new JournalRepository.Saved(value, true);
        }, 10);
        recorder.enqueue(entry("minecraft:plains", 100));
        recorder.enqueue(entry("minecraft:plains", 10));
        recorder.enqueue(entry("minecraft:forest", 20));
        assertEquals(10, recorder.snapshot().getFirst().firstSeen());
        assertThrows(SQLException.class, () -> recorder.drain(saved -> {}));
        assertEquals(List.of(entry("minecraft:forest", 20)), recorder.snapshot());
    }

    @Test void pendingSnapshotRoundTripsAndClearsAcknowledgedEntries() throws Exception {
        var store = new JournalPendingStore(directory.resolve("pending.dat"));
        assertTrue(store.load().isEmpty());
        var original = entry("minecraft:plains", 10);
        store.save(List.of(original));
        assertEquals(List.of(original), new JournalPendingStore(directory.resolve("pending.dat")).load());
        store.save(List.of());
        assertTrue(store.load().isEmpty());
    }

    @Test void corruptPendingSnapshotFailsWithoutOverwritingIt() throws Exception {
        Path file = directory.resolve("pending.dat");
        byte[] malformed = {1, 2, 3};
        Files.write(file, malformed);
        assertThrows(java.io.IOException.class, () -> new JournalPendingStore(file).load());
        assertArrayEquals(malformed, Files.readAllBytes(file));
    }

    @Test void shutdownAbortLeavesUnwrittenEntriesQueued() throws Exception {
        AtomicBoolean keepGoing = new AtomicBoolean(true);
        JournalRecorder recorder = new JournalRecorder(value -> {
            keepGoing.set(false);
            return new JournalRepository.Saved(value, true);
        }, 10);
        recorder.enqueue(entry("minecraft:plains", 1));
        recorder.enqueue(entry("minecraft:forest", 2));
        recorder.drain(saved -> {}, keepGoing::get);
        assertEquals(List.of(entry("minecraft:forest", 2)), recorder.snapshot());
    }
}
