package com.lunarboreal.structures.end;

import com.lunarboreal.structures.nms.PaperAccess;
import com.yungnickyoung.minecraft.betterendisland.BetterEndIslandCommon;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndSpawnPlatformFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldSaveEvent;
import java.util.concurrent.TimeUnit;

public final class EndIslandController implements Listener {
    /** Previously converted islands retain their gameplay support after a generation switch is disabled. */
    public static boolean install(ServerLevel level, boolean enabled) {
        if (level.getDragonFight() == null) return false;
        var data = level.getDataStorage().get(EndFightData.TYPE);
        try {
            var path = (java.nio.file.Path) PaperAccess.invoke(level.getDataStorage(), "getDataFile",
                    new Class<?>[]{net.minecraft.resources.Identifier.class}, EndFightData.TYPE.id());
            if (data == null && java.nio.file.Files.exists(path))
                throw new IllegalStateException("Existing Better End Island state is unreadable; restore it before loading this world");
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot verify End fight state storage", e); }
        if (data == null) {
            if (!enabled) return false;
            try {
                var existing = level.getChunkSource().chunkMap.read(new ChunkPos(0, 0)).get(10, TimeUnit.SECONDS);
                if (existing.isPresent() && existing.get().getStringOr("Status", "").equals("minecraft:full")) return false;
            } catch (Exception e) { throw new IllegalStateException("Cannot verify existing End island", e); }
            data = level.getDataStorage().computeIfAbsent(EndFightData.TYPE);
            data.setDirty();
        }
        var original = level.getDragonFight();
        var fight = new NativeEndFight(original, data);
        BlockPos origin = PaperAccess.read(original, "origin");
        fight.init(level, level.getSeed(), origin);
        fight.dragonEvent.setVisible(false);
        level.setDragonFight(fight);
        level.getDataStorage().set(EnderDragonFight.TYPE, fight);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void platform(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.END_PLATFORM || BetterEndIslandCommon.CONFIG.useVanillaSpawnPlatform) return;
        var level = ((CraftWorld) event.getWorld()).getHandle();
        if (!(level.getDragonFight() instanceof NativeEndFight)) return;
        event.setCancelled(true);
        if (!BetterEndSpawnPlatformFeature.place(level, ServerLevel.END_SPAWN_POINT.below(), false))
            throw new IllegalStateException("Unable to place Better End Island arrival platform");
    }

    @EventHandler public void save(WorldSaveEvent event) {
        var fight = ((CraftWorld) event.getWorld()).getHandle().getDragonFight();
        if (fight instanceof NativeEndFight custom) custom.saveExtra();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void gateway(io.papermc.paper.event.entity.EntityInsideBlockEvent event) {
        if (event.getBlock().getType() != org.bukkit.Material.END_GATEWAY) return;
        var level = ((CraftWorld) event.getBlock().getWorld()).getHandle();
        if (!(level.getDragonFight() instanceof NativeEndFight)) return;
        var block = event.getBlock();
        var entity = level.getBlockEntity(new BlockPos(block.getX(), block.getY(), block.getZ()));
        if (entity instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity gateway)
            PortedEndGateway.install(level, gateway);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void dragonBlocks(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.EnderDragon)) return;
        var level = ((CraftWorld) event.getLocation().getWorld()).getHandle();
        if (!(level.getDragonFight() instanceof NativeEndFight)) return;
        // The upstream global dragon_immune tag would change vanilla worlds as well.
        var protectedBlocks = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                net.minecraft.resources.Identifier.parse("betterendisland:dragon_immune"));
        event.blockList().removeIf(block -> level.getBlockState(new BlockPos(block.getX(), block.getY(), block.getZ())).is(protectedBlocks));
    }
}
