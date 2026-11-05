// Paper port; original source and LGPL-3.0 notice: third-party/yungs/extras/
package com.yungnickyoung.minecraft.yungsextras.world.feature.desert;

import net.minecraft.world.level.chunk.ChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.ExtraCodecs;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.google.common.collect.Lists;
import com.yungnickyoung.minecraft.yungsextras.module.FeatureProcessorModule;
import com.yungnickyoung.minecraft.yungsextras.world.feature.AbstractNbtFeature;
import com.yungnickyoung.minecraft.yungsextras.world.processor.INbtFeatureProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;


import java.util.List;


public class DesertWellFeature extends AbstractNbtFeature {
    public static final MapCodec<DesertWellFeature> CODEC = RecordCodecBuilder.mapCodec(builder -> builder.group(
            Identifier.CODEC.fieldOf("location").forGetter(feature -> feature.location),
            ExtraCodecs.NON_NEGATIVE_INT.fieldOf("radius").forGetter(feature -> feature.radius)
    ).apply(builder, DesertWellFeature::new));
    private final Identifier location;
    private final int radius;

    private DesertWellFeature(Identifier location, int radius) {
        this.location = location;
        this.radius = radius;
    }

    @Override public MapCodec<DesertWellFeature> codec() { return CODEC; }

    /**
     * We override this method to supply the processors specific to this template feature.
     */
    @Override
    protected List<INbtFeatureProcessor> useProcessors() {
        return Lists.newArrayList(
                FeatureProcessorModule.DESERT_WELL_PROCESSOR
        );
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource randomSource, BlockPos pos) {
        // Find the surface
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.isEmptyBlock(mutable) && mutable.getY() > 7) {
            mutable.move(Direction.DOWN);
        }

        BlockPos surfacePos = mutable.immutable();
        Block block = level.getBlockState(surfacePos).getBlock();

        // Ensure valid position
        if (!block.defaultBlockState().is(BlockTags.SAND)) return false;

        // Naive check to avoid spawning on a shallow overhang or steep cliff
        for (int i = 1; i <= 7; i++) {
            mutable.set(surfacePos).move(Direction.DOWN, i);
            if (level.isEmptyBlock(mutable)) return false;
            mutable.set(surfacePos).move(Direction.DOWN, i).move(Direction.NORTH, radius).move(Direction.EAST, radius);
            if (level.isEmptyBlock(mutable)) return false;
            mutable.set(surfacePos).move(Direction.DOWN, i).move(Direction.EAST, radius).move(Direction.SOUTH, radius);
            if (level.isEmptyBlock(mutable)) return false;
            mutable.set(surfacePos).move(Direction.DOWN, i).move(Direction.SOUTH, radius).move(Direction.WEST, radius);
            if (level.isEmptyBlock(mutable)) return false;
            mutable.set(surfacePos).move(Direction.DOWN, i).move(Direction.WEST, radius).move(Direction.NORTH, radius);
            if (level.isEmptyBlock(mutable)) return false;
        }

        // Generate the well
        StructureTemplate template = this.createTemplateFromCenter(location, level, randomSource, surfacePos.relative(Direction.DOWN, 6));
        return template != null;
    }
}
