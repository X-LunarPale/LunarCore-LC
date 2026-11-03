package com.lunarboreal.performance;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceSafetyTest {
    @Test void defaultsAreOptInAndBounded() {
        var defaults = PerformanceSettings.read(new YamlConfiguration());
        assertFalse(defaults.cleanerEnabled());
        assertTrue(defaults.protectPlayerDrops());
        var c = new YamlConfiguration();
        c.set("sampling.interval-seconds", Integer.MAX_VALUE);
        c.set("cleaner.batch-size", -9);
        c.set("cleaner.tracking-capacity", Integer.MAX_VALUE);
        assertEquals(300, PerformanceSettings.read(c).sampleSeconds());
        assertEquals(1, PerformanceSettings.read(c).batchSize());
        assertEquals(65536, PerformanceSettings.read(c).capacity());
    }
    @Test void trackerDropsOverflowAndVisitsEachEntryAtMostOncePerBatch() {
        var t = new BoundedTracker<Integer>();
        assertTrue(t.add(1, 2));
        assertTrue(t.add(2, 2));
        assertFalse(t.add(3, 2));
        var batch = t.take(32);
        assertEquals(List.of(1, 2), batch);
        batch.forEach(id -> t.add(id, 2));
        assertEquals(2, t.size());
        t.remove(1);
        assertEquals(List.of(2), t.take(32));
        t.clear();
        assertEquals(0, t.size());
    }
    @Test void samplingRejectsNonFiniteFractionalOrOutOfRangeInput() {
        assertEquals(5, PerformanceWeb.sampleSeconds(5.0));
        for (Object bad : List.of(0, 301, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, "5"))
            assertThrows(RuntimeException.class, () -> PerformanceWeb.sampleSeconds(bad));
    }
}
