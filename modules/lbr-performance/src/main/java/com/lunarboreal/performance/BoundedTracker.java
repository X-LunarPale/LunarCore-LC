package com.lunarboreal.performance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Main-thread-only round-robin queue. Overflow is deliberately left untouched. */
final class BoundedTracker<T> {
    private final Set<T> entries = new LinkedHashSet<>();
    boolean add(T entry, int capacity) {
        if (entries.contains(entry)) return true;
        return entries.size() < capacity && entries.add(entry);
    }
    void remove(T entry) { entries.remove(entry); }
    List<T> take(int limit) {
        List<T> result = new ArrayList<>();
        var it = entries.iterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
            it.remove();
        }
        return result;
    }
    int size() { return entries.size(); }
    void clear() { entries.clear(); }
}
