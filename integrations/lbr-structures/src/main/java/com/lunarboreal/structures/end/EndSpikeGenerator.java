package com.lunarboreal.structures.end;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterSpikeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;

public record EndSpikeGenerator(EndSpikeFeature original) implements Feature {
    public static final MapCodec<EndSpikeGenerator> CODEC = EndSpikeFeature.CODEC.xmap(EndSpikeGenerator::new, EndSpikeGenerator::original);
    @Override public MapCodec<EndSpikeGenerator> codec() { return CODEC; }
    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var spikes = original.spikes().isEmpty() ? BetterSpikeFeature.getSpikesForLevel(level) : original.spikes();
        for (var spike : spikes) {
            if (spike.isCenterWithinChunk(origin)) {
                var custom = spike instanceof PortedEndSpike ? spike : new PortedEndSpike(spike.getCenterX(), spike.getCenterZ(), spike.getRadius(), spike.getHeight(), spike.isGuarded());
                BetterSpikeFeature.placeSpike(level, random, original, custom, level instanceof net.minecraft.server.level.WorldGenRegion);
            }
        }
        return true;
    }
}
