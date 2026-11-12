package com.lunarboreal.performance;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Tracks only new drops: never enumerates a world's entities or loads a chunk. */
final class DropCleaner implements Listener {
    private final Supplier<PerformanceSettings> settings;
    private final BoundedTracker<UUID> tracked = new BoundedTracker<>();
    private long removed;
    private long overflow;

    DropCleaner(Supplier<PerformanceSettings> settings) { this.settings = settings; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(ItemSpawnEvent event) {
        var s = settings.get();
        if (s.cleanerEnabled() && !tracked.add(event.getEntity().getUniqueId(), s.capacity())) overflow++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerDrop(PlayerDropItemEvent event) {
        if (settings.get().protectPlayerDrops()) tracked.remove(event.getItemDrop().getUniqueId());
    }

    void batch() {
        var s = settings.get();
        if (!s.cleanerEnabled()) { tracked.clear(); return; }
        // Pull before requeueing so a small queue never processes the same drop twice in one batch.
        for (UUID id : tracked.take(s.batchSize())) {
            if (!(Bukkit.getEntity(id) instanceof Item item) || !item.isValid() || item.isDead()) continue;
            if (item.customName() != null || item.getItemStack().hasItemMeta()
                    || !item.getPersistentDataContainer().isEmpty() || !item.getScoreboardTags().isEmpty()
                    || item.getOwner() != null || (s.protectPlayerDrops() && item.getThrower() != null)) continue;
            if (item.getTicksLived() < s.minAgeTicks()) {
                tracked.add(id, s.capacity());
                continue;
            }
            item.remove();
            removed++;
        }
    }

    long removed() { return removed; }
    long overflow() { return overflow; }
    int tracked() { return tracked.size(); }
    void clear() { tracked.clear(); }
}
