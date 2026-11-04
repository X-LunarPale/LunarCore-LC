package com.lunarboreal.structures.end;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndGatewayFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.EndGatewayFeature;
import net.minecraft.world.level.levelgen.feature.Feature;

public record EndGatewayGenerator(EndGatewayFeature original) implements Feature {
    public static final MapCodec<EndGatewayGenerator> CODEC = EndGatewayFeature.CODEC.xmap(EndGatewayGenerator::new, EndGatewayGenerator::original);
    @Override public MapCodec<EndGatewayGenerator> codec() { return CODEC; }
    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        return BetterEndGatewayFeature.place(level, origin, random, original);
    }
}
