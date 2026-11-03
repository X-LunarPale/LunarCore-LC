package com.lunarboreal.performance;

import org.bukkit.configuration.ConfigurationSection;

record PerformanceSettings(int sampleSeconds, boolean cleanerEnabled, int batchSize, int capacity,
                           int minAgeTicks, int batchTicks, boolean protectPlayerDrops) {
    static PerformanceSettings read(ConfigurationSection c) {
        return new PerformanceSettings(clamp(c.getInt("sampling.interval-seconds", 5), 1, 300),
                c.getBoolean("cleaner.enabled", false), clamp(c.getInt("cleaner.batch-size", 32), 1, 256),
                clamp(c.getInt("cleaner.tracking-capacity", 4096), 32, 65536),
                clamp(c.getInt("cleaner.minimum-age-seconds", 240), 30, 3600) * 20,
                clamp(c.getInt("cleaner.batch-ticks", 20), 1, 1200),
                c.getBoolean("cleaner.protect-player-drops", true));
    }
    static int clamp(int n, int min, int max) { return Math.max(min, Math.min(max, n)); }
}
