package com.lunarboreal.structures.nms;

import com.yungnickyoung.minecraft.betterdeserttemples.BetterDesertTemplesCommon;
import com.yungnickyoung.minecraft.betterdeserttemples.module.TagModule;
import com.yungnickyoung.minecraft.betterdeserttemples.util.PharaohUtil;
import com.yungnickyoung.minecraft.betterdeserttemples.world.state.TempleStateCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Paper event/scheduler equivalents of the upstream Pharaoh and player-tick mixins. */
public final class PharaohRuntime implements Listener {
    private static final NamespacedKey ORIGIN = new NamespacedKey("lunarcorestructures", "pharaoh_origin");
    private static final Map<UUID, TempleStateCache> CACHES = new HashMap<>();

    public static TempleStateCache cache(ServerLevel level) {
        return CACHES.computeIfAbsent(level.getWorld().getUID(), key -> new TempleStateCache(level.getWorld().getWorldFolder().toPath()));
    }
    public static Vec3 originalSpawn(Entity entity) {
        int[] position = entity.getBukkitEntity().getPersistentDataContainer().get(ORIGIN, PersistentDataType.INTEGER_ARRAY);
        return position == null || position.length != 3 ? null : new Vec3(position[0], position[1], position[2]);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void died(EntityDeathEvent event) {
        var entity = ((CraftEntity) event.getEntity()).getHandle();
        if (PharaohUtil.isPharaoh(entity) && entity.level() instanceof ServerLevel level)
            PharaohUtil.onKillOrDiscardPharaoh(entity, level, ((net.minecraft.world.entity.LivingEntity) entity).getLastDamageSource());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void discarded(EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD || event.getCause() == EntityRemoveEvent.Cause.DEATH
                || event.getCause() == EntityRemoveEvent.Cause.TRANSFORMATION || event.getCause() == EntityRemoveEvent.Cause.PLAYER_QUIT) return;
        var entity = ((CraftEntity) event.getEntity()).getHandle();
        if (PharaohUtil.isPharaoh(entity) && entity.level() instanceof ServerLevel level)
            PharaohUtil.onKillOrDiscardPharaoh(entity, level, null);
    }

    @EventHandler public void unloaded(WorldUnloadEvent event) { CACHES.remove(event.getWorld().getUID()); }
    public static void clear() { CACHES.clear(); }

    public void tickPlayers() {
        if (!BetterDesertTemplesCommon.CONFIG.general.applyMiningFatigue) return;
        for (var bukkitPlayer : Bukkit.getOnlinePlayers()) {
            var player = ((CraftPlayer) bukkitPlayer).getHandle();
            if (!player.gameMode.isSurvival() || !player.level().isLoaded(player.blockPosition())) continue;
            var structure = player.level().structureManager().getStructureWithPieceAt(player.blockPosition(), TagModule.APPLIES_MINING_FATIGUE);
            if (!structure.isValid() || cache(player.level()).isTempleCleared(structure.getChunkPos().getWorldPosition())) continue;
            var existing = player.getEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE);
            if (existing == null || existing.getAmplifier() < 2 || existing.getDuration() < 120) {
                if (existing == null) bukkitPlayer.playSound(bukkitPlayer.getLocation(), org.bukkit.Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1, 1);
                player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MINING_FATIGUE, 600, 2), player);
            }
        }
    }
}
