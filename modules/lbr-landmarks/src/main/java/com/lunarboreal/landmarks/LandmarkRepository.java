package com.lunarboreal.landmarks;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

final class LandmarkRepository implements AutoCloseable {
    @FunctionalInterface interface Writer { void save(LandmarkBook state) throws IOException; }
    @FunctionalInterface interface Task<T> { T run() throws Exception; }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "LC-landmarks-storage");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Writer writer;
    private volatile LandmarkBook snapshot = LandmarkBook.empty();
    private volatile boolean ready;
    private volatile boolean healthy = true;
    private final java.util.Set<CompletableFuture<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    LandmarkRepository(LandmarkStore store) { this.writer = store::save; }
    LandmarkRepository(Writer writer, LandmarkBook initial) { this.writer = writer; snapshot = initial; ready = true; }
    CompletableFuture<Void> load(LandmarkStore store) {
        return task(() -> { snapshot = store.load(); ready = true; return null; });
    }
    LandmarkBook snapshot() { requireReady(); return snapshot; }
    boolean ready() { return ready; }
    boolean healthy() { return healthy; }
    CompletableFuture<LandmarkBook> update(UnaryOperator<LandmarkBook> operation) {
        return task(() -> {
            requireReady();
            LandmarkBook next = operation.apply(snapshot);
            writer.save(next);
            snapshot = next;
            return next;
        });
    }
    <T> CompletableFuture<T> task(Task<T> task) {
        var result = new CompletableFuture<T>();
        pending.add(result);
        try {
            worker.execute(() -> {
                try { T value = task.run(); healthy = true; result.complete(value); }
                catch (Exception error) { if (!(error instanceof IllegalArgumentException)) healthy = false; result.completeExceptionally(error); }
                finally { pending.remove(result); }
            });
        } catch (RuntimeException error) { pending.remove(result); result.completeExceptionally(error); }
        return result;
    }
    private void requireReady() { if (!ready) throw new IllegalStateException("loading"); }
    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                pending.forEach(future -> future.completeExceptionally(new IllegalStateException("closed")));
                if (!worker.awaitTermination(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Landmark storage did not stop");
            }
        } catch (InterruptedException error) {
            worker.shutdownNow();
            pending.forEach(future -> future.completeExceptionally(new IllegalStateException("closed")));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping landmark storage", error);
        } finally { ready = false; }
    }
}
