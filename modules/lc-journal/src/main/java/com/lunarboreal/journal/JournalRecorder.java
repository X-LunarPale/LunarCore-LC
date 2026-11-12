package com.lunarboreal.journal;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A failed write stays queued with its original first-seen time. */
final class JournalRecorder {

    @FunctionalInterface
    interface Writer { JournalRepository.Saved save(JournalEntry entry) throws SQLException; }

    private final Map<String, JournalEntry> pending = new LinkedHashMap<>();
    private final Object drainLock = new Object();
    private final Writer writer;
    private final int capacity;

    JournalRecorder(Writer writer, int capacity) {
        this.writer = writer;
        this.capacity = capacity;
    }

    boolean enqueue(JournalEntry entry) {
        synchronized (pending) {
            JournalEntry existing = pending.get(entry.id());
            if (existing != null) {
                if (entry.firstSeen() < existing.firstSeen()) pending.put(entry.id(), entry);
                return true;
            }
            if (pending.size() >= capacity) return false;
            pending.put(entry.id(), entry);
            return true;
        }
    }

    int size() {
        synchronized (pending) { return pending.size(); }
    }

    List<JournalEntry> snapshot() {
        synchronized (pending) { return List.copyOf(pending.values()); }
    }

    void drain(Consumer<JournalRepository.Saved> onSaved) throws SQLException {
        drain(onSaved, () -> true);
    }

    void drain(Consumer<JournalRepository.Saved> onSaved, BooleanSupplier keepGoing) throws SQLException {
        synchronized (drainLock) {
            while (keepGoing.getAsBoolean()) {
                JournalEntry next;
                synchronized (pending) {
                    next = pending.isEmpty() ? null : pending.values().iterator().next();
                }
                if (next == null) return;
                JournalRepository.Saved saved = writer.save(next);
                synchronized (pending) { pending.remove(next.id(), next); }
                onSaved.accept(saved);
            }
        }
    }
}
