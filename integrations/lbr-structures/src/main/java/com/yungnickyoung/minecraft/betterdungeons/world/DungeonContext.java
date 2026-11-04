// Paper adaptation of YUNG's Better Dungeons (LGPL-3.0); see third-party/yungs/dungeons.
package com.yungnickyoung.minecraft.betterdungeons.world;

/** Per-template, per-worker counters, restored after nested placements. */
public final class DungeonContext {
    private static final ThreadLocal<DungeonContext> CONTEXT = new ThreadLocal<>();
    private int bannerCount, chestCount;
    public int getBannerCount() { return bannerCount; }
    public int getChestCount() { return chestCount; }
    public void incrementBannerCount() { bannerCount++; }
    public void incrementChestCount() { chestCount++; }
    public static DungeonContext pop() { var old = CONTEXT.get(); CONTEXT.remove(); return old; }
    public static DungeonContext peek() { return CONTEXT.get(); }
    public static void initialize() { CONTEXT.set(new DungeonContext()); }
    public static void restore(DungeonContext previous) {
        if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous);
    }
}
