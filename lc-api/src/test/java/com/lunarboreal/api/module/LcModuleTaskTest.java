package com.lunarboreal.api.module;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LcModuleTaskTest {
    private static final class Module extends LcModule {
        @Override public void onEnable() {}
    }

    private static final class Task implements BukkitTask {
        private boolean cancelled;
        @Override public int getTaskId() { return 1; }
        @Override public Plugin getOwner() { return null; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { cancelled = true; }
    }

    @Test
    void completedCallbacksAreReleasedWhilePendingTasksStillCancel() {
        Module module = new Module();
        Task completed = new Task();
        Task pending = new Task();
        AtomicReference<Runnable> callback = new AtomicReference<>();
        module.scheduleOnce(() -> {}, action -> { callback.set(action); return completed; });
        module.scheduleOnce(() -> fail("Pending callback must not run"), action -> pending);

        callback.get().run();
        module.cleanupInternal();

        assertFalse(completed.isCancelled(), "Completed tasks must no longer be retained for cleanup");
        assertTrue(pending.isCancelled(), "Pending callbacks must remain owned by their module");
    }

    @Test
    void failedCallbackIsReleasedWithoutSwallowingItsFailure() {
        Module module = new Module();
        Task task = new Task();
        AtomicReference<Runnable> callback = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("callback failure");
        module.scheduleOnce(() -> { throw failure; }, action -> { callback.set(action); return task; });

        assertSame(failure, assertThrows(IllegalStateException.class, callback.get()::run));
        module.cleanupInternal();

        assertFalse(task.isCancelled());
    }

    @Test
    void callbackCompletingBeforeSchedulingReturnsIsNotRetained() {
        Module module = new Module();
        Task task = new Task();
        assertSame(task, module.scheduleOnce(() -> {}, action -> {
            action.run();
            return task;
        }));

        module.cleanupInternal();

        assertFalse(task.isCancelled());
    }
}
