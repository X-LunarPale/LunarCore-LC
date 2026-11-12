package com.lunarboreal.terrain.nms;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Per-world version of vanilla's freeze-top-layer feature with Tectonic's temperature position. */
record SnowlineFeature(int offset) implements Feature {
    @Override
    public MapCodec<? extends Feature> codec() {
        return MapCodec.unit(this);
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int px = origin.getX() + x;
                int pz = origin.getZ() + z;
                top.set(px, level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz), pz);
                below.set(top).move(Direction.DOWN);
                Biome biome = level.getBiome(top).value();
                if (!coldAtSnowline(biome, top, level.getSeaLevel(), offset)) continue;
                if (biome.shouldFreeze(level, below, false)) {
                    level.setBlock(below, Blocks.ICE.defaultBlockState(), 2);
                }
                if (biome.shouldSnow(level, top)) {
                    level.setBlock(top, Blocks.SNOW.defaultBlockState(), 2);
                    BlockState ground = level.getBlockState(below);
                    if (ground.hasProperty(SnowyBlock.SNOWY)) {
                        level.setBlock(below, ground.setValue(SnowyBlock.SNOWY, true), 2);
                    }
                }
            }
        }
        return true;
    }

    static boolean coldAtSnowline(Biome biome, BlockPos pos, int seaLevel, int offset) {
        return biome.getTemperature(pos.below(offset), seaLevel) < 0.15F;
    }
}
