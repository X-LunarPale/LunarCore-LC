package com.lunarboreal.performance;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceStatsTest {
    @Test void statsUseReadableValuesAndKeepDetailsInHints() {
        var stats = PerformanceWeb.formatStats(Map.of(
                "sampledAt", 1700000000000L, "tps", 19.9876, "mspt", 12.3456,
                "memoryUsedBytes", 1048576L * 256, "memoryMaxBytes", 1048576L * 2048,
                "cleaner", Map.of("enabled", false, "tracked", 32, "removed", 128, "overflow", 4),
                "lagfixer", Map.of("installed", true, "enabled", true, "bridgeAvailable", true, "version", "2.9.4")),
                List.of(Map.of("enabled", true), Map.of("enabled", false)));
        assertEquals("19.99", stats.get(0).get("value"));
        assertEquals("12.35 ms", stats.get(1).get("value"));
        assertEquals("256.0 MiB", stats.get(2).get("value"));
        assertEquals("2,048.0 MiB", stats.get(3).get("value"));
        assertEquals("已关闭", stats.get(5).get("value"));
        assertTrue(stats.get(5).get("hint").toString().contains("已清理 128"));
        assertEquals("桥接可用", stats.get(6).get("value"));
        assertTrue(stats.get(6).get("hint").toString().contains("2.9.4"));
        assertEquals("1 / 2", stats.get(7).get("value"));
        assertTrue(stats.stream().allMatch(s -> s.get("value") instanceof String));
    }
    @Test void missingSamplesAndNonFiniteValuesRemainReadable() {
        var stats = PerformanceWeb.formatStats(Map.of("tps", Double.NaN, "sampledAt", 0), List.of());
        assertEquals("等待采样", stats.get(0).get("value"));
        assertEquals("等待采样", stats.get(4).get("value"));
        assertEquals("等待采样", stats.get(5).get("value"));
        assertEquals("等待采样", stats.get(6).get("value"));
        assertEquals("0 / 0", stats.get(7).get("value"));
    }
}
