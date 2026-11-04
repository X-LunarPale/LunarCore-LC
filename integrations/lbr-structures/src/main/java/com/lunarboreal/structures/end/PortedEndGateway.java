package com.lunarboreal.structures.end;

import com.lunarboreal.structures.nms.PaperAccess;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndGatewayFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.EndGatewayFeature;
import net.minecraft.world.phys.Vec3;

/** LGPL-3.0: Paper port of Better End Island's gateway landing mixin and feature hook.
 * The saved block entity remains minecraft:end_gateway; no client type is added. */
public final class PortedEndGateway extends TheEndGatewayBlockEntity {
    private static final TagKey<Block> CANNOT_PLACE_ON = TagKey.create(Registries.BLOCK,
            Identifier.parse("betterendisland:end_gateway_cannot_place_player_on"));

    private PortedEndGateway(BlockPos pos, BlockState state) { super(pos, state); }

    public static PortedEndGateway install(ServerLevel level, TheEndGatewayBlockEntity original) {
        if (original instanceof PortedEndGateway ported) return ported;
        var ported = new PortedEndGateway(original.getBlockPos(), original.getBlockState());
        ported.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), original.saveWithoutMetadata(level.registryAccess())));
        PaperAccess.write(ported, "teleportCooldown", PaperAccess.read(original, "teleportCooldown"));
        level.setBlockEntity(ported);
        return ported;
    }

    @Override public Vec3 getPortalPosition(ServerLevel level, BlockPos origin) {
        if (exitPortal == null) {
            BlockPos remote = findRemoteGround(level, origin).above(10);
            if (!BetterEndGatewayFeature.place(level, remote, level.getRandom(), EndGatewayFeature.knownExit(origin, false)))
                throw new IllegalStateException("Cannot place Better End Island return gateway");
            setExitPosition(remote, exactTeleport);
            setChanged();
        }
        return Vec3.atBottomCenterOf(exactTeleport ? exitPortal : tallest(level, exitPortal.above(2), 5, false).above());
    }

    private static BlockPos findRemoteGround(ServerLevel level, BlockPos origin) {
        Vec3 direction = new Vec3(origin.getX(), 0, origin.getZ()).normalize();
        Vec3 cursor = direction.scale(1024);
        for (int i = 0; i < 16 && !empty(level, cursor); i++) cursor = cursor.add(direction.scale(-16));
        for (int i = 0; i < 16 && empty(level, cursor); i++) cursor = cursor.add(direction.scale(16));
        BlockPos ground = validSpawn(level.getChunk(net.minecraft.util.Mth.floor(cursor.x) >> 4, net.minecraft.util.Mth.floor(cursor.z) >> 4));
        if (ground == null) {
            ground = BlockPos.containing(cursor.x + .5, 75, cursor.z + .5);
            var island = level.registryAccess().lookupOrThrow(Registries.FEATURE)
                    .getOrThrow(net.minecraft.data.worldgen.features.EndFeatures.END_ISLAND).value();
            island.place(level, level.getChunkSource().getGenerator(), RandomSource.create(ground.asLong()), ground);
        }
        return tallest(level, ground, 16, true);
    }

    private static boolean empty(ServerLevel level, Vec3 point) {
        return level.getChunk(net.minecraft.util.Mth.floor(point.x) >> 4, net.minecraft.util.Mth.floor(point.z) >> 4).getHighestFilledSectionIndex() == -1;
    }

    static BlockPos tallest(BlockGetter level, BlockPos origin, int radius, boolean placeAnywhere) {
        BlockPos best = null;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x == 0 && z == 0 && !placeAnywhere) continue;
            for (int y = level.getMaxY() - 1; y > (best == null ? level.getMinY() : best.getY()); y--) {
                var candidate = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                var state = level.getBlockState(candidate);
                if (state.isCollisionShapeFullBlock(level, candidate) && (placeAnywhere || !state.is(CANNOT_PLACE_ON))) {
                    best = candidate; break;
                }
            }
        }
        return best == null ? origin : best;
    }

    private static BlockPos validSpawn(LevelChunk chunk) {
        BlockPos best = null;
        double distance = 0;
        var pos = chunk.getPos();
        for (var candidate : BlockPos.betweenClosed(new BlockPos(pos.getMinBlockX(), 30, pos.getMinBlockZ()),
                new BlockPos(pos.getMaxBlockX(), chunk.getHighestSectionPosition() + 15, pos.getMaxBlockZ()))) {
            if (!chunk.getBlockState(candidate).is(Blocks.END_STONE) || !chunk.getBlockState(candidate.above()).isAir()
                    || !chunk.getBlockState(candidate.above(2)).isAir()) continue;
            double next = candidate.distToCenterSqr(0, 0, 0);
            if (best == null || next < distance) { best = candidate.immutable(); distance = next; }
        }
        return best;
    }
}
