package com.lunarboreal.anticheat;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, concurrent snapshots. No Grim event/player/check objects escape a callback. */
public final class RecentFlags {
    public static final int MAX_CAPACITY = 10_000;
    public record Flag(long time, String uuid, String player, String check, double vl,
                       String detail, int ping, String world, boolean cancelled) {
        public Map<String, Object> toMap() {
            return Map.of("time", time, "uuid", uuid, "player", player, "check", check,
                    "vl", vl, "detail", detail, "ping", ping, "world", world, "cancelled", cancelled);
        }
        boolean matches(String q) {
            return (player + " " + uuid + " " + check).toLowerCase(Locale.ROOT).contains(q);
        }
    }
    private final ArrayDeque<Flag> flags = new ArrayDeque<>();
    private int capacity = 500;
    private long captured;
    public synchronized void capacity(int capacity) {
        this.capacity = Math.max(1, Math.min(MAX_CAPACITY, capacity));
        while (flags.size() > this.capacity) flags.removeLast();
    }
    public synchronized void add(Flag flag) {
        flags.addFirst(java.util.Objects.requireNonNull(flag));
        captured++;
        while (flags.size() > capacity) flags.removeLast();
    }
    public synchronized List<Flag> snapshot(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return flags.stream().filter(f -> f.matches(q)).toList();
    }
    public synchronized int size() { return flags.size(); }
    public synchronized int capacity() { return capacity; }
    public synchronized long captured() { return captured; }
    public synchronized void clear() { flags.clear(); }
}
