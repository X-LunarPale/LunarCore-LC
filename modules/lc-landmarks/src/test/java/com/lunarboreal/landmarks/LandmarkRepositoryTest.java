package com.lunarboreal.landmarks;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkRepositoryTest {
    private LandmarkBook add(LandmarkBook state) {
        return state.submit(UUID.randomUUID(), "Builder", 1, "world", 0, 64, 0, "Garden", "", 3, 100);
    }
    @Test void diskFailureKeepsOldSnapshotAndRetryIsPossible() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LandmarkRepository repo = new LandmarkRepository(state -> {
            if (calls.incrementAndGet() == 1) throw new IOException("disk full");
        }, LandmarkBook.empty())) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> repo.update(this::add).get(2, TimeUnit.SECONDS));
            assertEquals(0, repo.snapshot().entries().size());
            assertFalse(repo.healthy());
            assertEquals(1, repo.update(this::add).get(2, TimeUnit.SECONDS).entries().size());
            assertTrue(repo.healthy());
        }
    }
    @Test void parallelSubmissionsAreSerializedWithUniqueIds() throws Exception {
        AtomicReference<LandmarkBook> persisted = new AtomicReference<>();
        try (LandmarkRepository repo = new LandmarkRepository(persisted::set, LandmarkBook.empty())) {
            var futures = new ArrayList<CompletableFuture<LandmarkBook>>();
            for (int i = 0; i < 64; i++) futures.add(repo.update(this::add));
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            assertEquals(64, repo.snapshot().entries().size());
            assertEquals(65, repo.snapshot().nextId());
            assertEquals(repo.snapshot(), persisted.get());
            assertEquals(64, repo.snapshot().entries().stream().map(Landmark::id).distinct().count());
        }
    }
    @Test void shutdownDrainsAlreadyAcceptedWritesWithoutMainThreadCallbacks() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger saved = new AtomicInteger();
        LandmarkRepository repo = new LandmarkRepository(state -> {
            if (saved.incrementAndGet() == 1) {
                writing.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        }, LandmarkBook.empty());
        var first = repo.update(this::add);
        assertTrue(writing.await(2, TimeUnit.SECONDS));
        var second = repo.update(this::add);
        var third = repo.update(this::add);
        var closing = CompletableFuture.runAsync(repo::close);
        release.countDown();
        closing.get(3, TimeUnit.SECONDS);
        assertEquals(3, third.get(1, TimeUnit.SECONDS).entries().size());
        assertEquals(2, second.get(1, TimeUnit.SECONDS).entries().size());
        assertEquals(1, first.get(1, TimeUnit.SECONDS).entries().size());
        assertEquals(3, saved.get());
        assertFalse(repo.ready());
        assertTrue(repo.update(this::add).isCompletedExceptionally());
    }
}
