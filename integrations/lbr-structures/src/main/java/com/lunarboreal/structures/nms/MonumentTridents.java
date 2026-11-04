package com.lunarboreal.structures.nms;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.yungnickyoung.minecraft.betteroceanmonuments.module.TagModule;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Only the upstream structure-owned tridents remain persistent; ordinary player tridents are unaffected. */
public final class MonumentTridents implements Listener {
    private static final UUID OWNER = UUID.fromString("e624cdc1-c238-4dde-9f22-1f76b5123ce8");
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    @EventHandler public void added(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Trident) tracked.add(event.getEntity().getUniqueId());
    }
    public void tick() {
        for (UUID id : tracked) {
            var entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) { tracked.remove(id); continue; }
            var trident = (ThrownTrident) ((CraftEntity) entity).getHandle();
            EntityReference<?> owner = PaperAccess.read(trident, "owner");
            if (owner == null || !OWNER.equals(owner.getUUID())) { tracked.remove(id); continue; }
            if (((net.minecraft.server.level.ServerLevel) trident.level()).structureManager()
                    .getStructureWithPieceAt(trident.blockPosition(), TagModule.BETTER_OCEAN_MONUMENT).isValid()) trident.life = 0;
        }
    }
}
