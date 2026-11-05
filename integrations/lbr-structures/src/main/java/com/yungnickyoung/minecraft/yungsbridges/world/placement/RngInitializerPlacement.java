// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.world.placement;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;


import java.util.function.Consumer;

/**
 * Properly initializes this placement's Random seed (which MC doesn't do on its own)
 * to maximize variability in feature placement.
 */


public class RngInitializerPlacement implements PlacementModifier {
    private static final RngInitializerPlacement INSTANCE = new RngInitializerPlacement();
    public static final MapCodec<RngInitializerPlacement> CODEC = MapCodec.unit(() -> INSTANCE);

    public static RngInitializerPlacement randomized() {
        return INSTANCE;
    }

    @Override
    public void modify(PlacementContext context, RandomSource randomSource, BlockPos blockPos, Consumer<BlockPos> output) {
        long a = randomSource.nextLong() | 1L;
        long b = randomSource.nextLong() | 1L;
        randomSource.setSeed(((blockPos.getX() * a * 951873395712L + 12132586) * (blockPos.getZ() * b * 132899567841L + 9789717)) ^ 313281234);
        output.accept(blockPos);
    }

    @Override
    public MapCodec<RngInitializerPlacement> codec() {
        return CODEC;
    }
}
