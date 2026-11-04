package com.lunarboreal.structures.retrofit;

import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.loot.Lootable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Decides which world entities an overwrite may replace; anything a player could own keeps the structure as it is. */
final class EntityPolicy {
    private static final double FIXED_ENTITY_DRIFT = 3;
    private static final Set<SpawnReason> PLAYER_MADE = EnumSet.of(SpawnReason.BREEDING, SpawnReason.EGG, SpawnReason.SPAWNER_EGG, SpawnReason.DISPENSE_EGG,
            SpawnReason.BUCKET, SpawnReason.CURED, SpawnReason.BUILD_SNOWMAN, SpawnReason.BUILD_IRONGOLEM, SpawnReason.BUILD_COPPERGOLEM, SpawnReason.BUILD_WITHER);
    private EntityPolicy() {}

    /** A non-mob entity the original generation placed, taken from the vanilla replay. */
    record Fixed(String type, double x, double y, double z) {
        static Fixed of(Entity entity) {
            var at = entity.getLocation();
            return new Fixed(entity.getType().name(), at.getX(), at.getY(), at.getZ());
        }
        boolean matches(String type, double x, double y, double z) {
            return this.type.equals(type) && Math.abs(this.x - x) <= FIXED_ENTITY_DRIFT
                    && Math.abs(this.y - y) <= FIXED_ENTITY_DRIFT && Math.abs(this.z - z) <= FIXED_ENTITY_DRIFT;
        }
    }

    /** Short-lived entities are left to expire; they say nothing about who changed the area. */
    static boolean transientEntity(Entity entity) {
        return entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof FallingBlock || entity instanceof AbstractArrow;
    }

    static boolean replaceable(Entity entity, List<Fixed> generated) {
        if (entity.customName() != null || !entity.getScoreboardTags().isEmpty() || !entity.getPersistentDataContainer().isEmpty()) return false;
        if (entity instanceof Mob mob) return wild(mob);
        var at = entity.getLocation();
        String type = entity.getType().name();
        if (generated.stream().noneMatch(fixed -> fixed.matches(type, at.getX(), at.getY(), at.getZ()))) return false;
        return !(entity instanceof Lootable loot) || loot.hasLootTable(); // An emptied chest minecart is a player change.
    }

    private static boolean wild(Mob mob) {
        if (mob instanceof AbstractVillager || mob instanceof Golem || mob instanceof Allay || mob instanceof Sniffer || mob instanceof HappyGhast
                || mob.isLeashed() || PLAYER_MADE.contains(mob.getEntitySpawnReason())) return false;
        if (mob instanceof Fox fox && fox.getFirstTrustedPlayer() != null || mob instanceof Ocelot ocelot && ocelot.isTrusting()) return false;
        if (mob instanceof Tameable tame && tame.isTamed()) return false;
        if (mob instanceof Steerable mount && mount.hasSaddle()) return false;
        return !(mob instanceof io.papermc.paper.entity.Bucketable bucket) || !bucket.isFromBucket();
    }
}
