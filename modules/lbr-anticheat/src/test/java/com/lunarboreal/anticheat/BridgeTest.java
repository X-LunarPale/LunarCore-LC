package com.lunarboreal.anticheat;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {
    private static RecentFlags.Flag flag(int n) {
        return new RecentFlags.Flag(n, "id", "Player" + n, "Simulation", n, "detail", 25, "world", false);
    }
    @Test void evictsOldestAndShrinksImmediately() {
        RecentFlags ring = new RecentFlags(); ring.capacity(3);
        for (int i = 0; i < 10; i++) ring.add(flag(i));
        assertEquals(10, ring.captured());
        assertEquals(java.util.List.of(9L, 8L, 7L), ring.snapshot("").stream().map(RecentFlags.Flag::time).toList());
        var old = ring.snapshot(""); ring.capacity(1);
        assertEquals(1, ring.size()); assertEquals(3, old.size());
        assertThrows(UnsupportedOperationException.class, () -> old.clear());
        assertEquals(1, ring.snapshot("PLAYER9").size());
        ring.capacity(Integer.MAX_VALUE); assertEquals(10_000, ring.capacity());
        ring.capacity(-1); assertEquals(1, ring.capacity());
    }
    @Test void concurrentWritersRemainBoundedAndDoNotLoseCount() throws Exception {
        RecentFlags ring = new RecentFlags(); ring.capacity(31);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> {
                for (int n = 0; n < 1000; n++) { ring.add(flag(n)); assertTrue(ring.snapshot("").size() <= 31); }
            }));
            for (var task : tasks) task.get();
        }
        assertEquals(8000, ring.captured()); assertEquals(31, ring.size());
    }
    public static class ReloadApi {
        int calls;
        final java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        public java.util.concurrent.CompletableFuture<Boolean> reloadAsync() { calls++; return result; }
        public void unregister(Object owner, Object listener) {}
    }
    private static AtomicBinding attach(GrimBridge bridge, ReloadApi api) throws Exception {
        var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        var value = new GrimBridge.Binding(api, ReloadApi.class, api, null, null,
                ReloadApi.class.getMethod("unregister", Object.class, Object.class), active);
        var field = GrimBridge.class.getDeclaredField("binding");
        field.setAccessible(true); field.set(bridge, value);
        return new AtomicBinding(active);
    }
    private record AtomicBinding(java.util.concurrent.atomic.AtomicBoolean active) {}

    @Test void reloadInvocationIsQueuedAndDuplicateRequestsAreRejected() throws Exception {
        var queued = new java.util.ArrayList<Runnable>();
        var bridge = new GrimBridge(new AntiCheatModule(), queued::add);
        var api = new ReloadApi(); attach(bridge, api);
        var results = new java.util.ArrayList<String>();
        bridge.reload(results::add);
        assertEquals(0, api.calls); assertTrue(bridge.reloading());
        assertThrows(IllegalStateException.class, () -> bridge.reload(results::add));
        assertEquals(1, queued.size());
        queued.removeFirst().run(); assertEquals(1, api.calls);
        api.result.complete(true);
        assertFalse(bridge.reloading()); assertEquals("重载成功", bridge.reloadStatus());
        assertEquals(java.util.List.of("重载成功"), results);
    }

    @Test void closeInvalidatesQueuedAndInFlightReloadsWithoutOverwritingNewOperation() throws Exception {
        var queued = new java.util.ArrayList<Runnable>();
        var bridge = new GrimBridge(new AntiCheatModule(), queued::add);
        var results = new java.util.ArrayList<String>();
        var abandoned = new ReloadApi(); attach(bridge, abandoned);
        bridge.reload(results::add); bridge.close(); queued.removeFirst().run();
        assertEquals(0, abandoned.calls); assertFalse(bridge.reloading());
        assertEquals("尚未重载", bridge.reloadStatus());
        var old = new ReloadApi(); attach(bridge, old);
        bridge.reload(results::add); queued.removeFirst().run(); bridge.close();
        var fresh = new ReloadApi(); attach(bridge, fresh);
        bridge.reload(results::add); queued.removeFirst().run();
        old.result.complete(false);
        assertTrue(bridge.reloading()); assertEquals("正在重载", bridge.reloadStatus()); assertTrue(results.isEmpty());
        fresh.result.complete(true);
        assertFalse(bridge.reloading()); assertEquals("重载成功", bridge.reloadStatus());
        assertEquals(java.util.List.of("重载成功"), results);
    }

    @Test void schedulingAndInvocationFailuresReleaseBusyState() throws Exception {
        var bridge = new GrimBridge(new AntiCheatModule(), task -> { throw new IllegalStateException("stopped"); });
        attach(bridge, new ReloadApi());
        assertThrows(IllegalStateException.class, () -> bridge.reload(result -> {}));
        assertFalse(bridge.reloading()); assertEquals("重载失败", bridge.reloadStatus());
        var queued = new java.util.ArrayList<Runnable>();
        var invocationBridge = new GrimBridge(new AntiCheatModule(), queued::add);
        var api = new ReloadApi() {
            @Override public java.util.concurrent.CompletableFuture<Boolean> reloadAsync() { throw new IllegalStateException("failed"); }
        };
        attach(invocationBridge, api); var results = new java.util.ArrayList<String>();
        invocationBridge.reload(results::add); queued.removeFirst().run();
        assertFalse(invocationBridge.reloading()); assertEquals(java.util.List.of("重载失败"), results);
    }

    public static class BlockingEvent extends Event {
        final java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        @Override public User getUser() {
            reading.countDown();
            try {
                if (!resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("read timeout");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return super.getUser();
        }
    }
    @Test void closeDoesNotWaitForEventReadAndPreventsPostClearAppend() throws Exception {
        var module = new AntiCheatModule();
        var bridge = new GrimBridge(module, task -> {});
        var active = attach(bridge, new ReloadApi()).active();
        var event = new BlockingEvent();
        var reader = new GrimBridge.SnapshotReader(BlockingEvent.class, User.class, Check.class);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var capture = pool.submit(() -> bridge.capture(active, reader, event));
            assertTrue(event.reading.await(5, java.util.concurrent.TimeUnit.SECONDS));
            try {
                pool.submit(() -> { bridge.close(); module.recent().clear(); }).get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally { event.resume.countDown(); }
            capture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(0, module.recent().size()); assertEquals(0, module.recent().captured());
    }

    public static class User {
        String name = "First";
        public UUID getUniqueId() { return new UUID(0, 1); }
        public String getName() { return name; }
        public int getTransactionPing() { return 30; }
        public String getWorldName() { return null; }
    }
    public static class Check {
        String name = "Simulation";
        public String getCheckName() { return name; }
    }
    public static class Event {
        User user = new User(); Check check = new Check(); String verbose = "a".repeat(5000); double vl = 3;
        public User getUser() { return user; }
        public Check getCheck() { return check; }
        public String getVerbose() { return verbose; }
        public double getViolations() { return vl; }
        public boolean isCancelled() { return false; }
    }
    @Test void reusedEventCannotMutateSnapshotAndInputsAreBounded() throws Exception {
        var reader = new GrimBridge.SnapshotReader(Event.class, User.class, Check.class);
        Event event = new Event(); var first = reader.read(event);
        event.user.name = "Second"; event.check.name = "Timer"; event.verbose = null; event.vl = Double.NaN;
        var second = reader.read(event);
        assertEquals("First", first.player()); assertEquals("Simulation", first.check());
        assertEquals(3, first.vl()); assertEquals(2048, first.detail().length());
        assertEquals("Second", second.player()); assertEquals("Timer", second.check());
        assertEquals("", second.detail()); assertEquals("", second.world()); assertEquals(0, second.vl());
        assertThrows(UnsupportedOperationException.class, () -> first.toMap().put("player", "changed"));
    }
}
