// Paper port; original source and LGPL-3.0 notice: third-party/yungs/extras/
package com.yungnickyoung.minecraft.yungsextras.world.feature.swamp;

import net.minecraft.world.level.chunk.ChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

public class SwampOgreFeature extends AbstractSwampFeature {
    public static final MapCodec<SwampOgreFeature> CODEC = Identifier.CODEC.fieldOf("location").xmap(SwampOgreFeature::new, feature -> feature.location);
    private final Identifier location;

    private SwampOgreFeature(Identifier location) {
        this.location = location;
    }

    @Override public MapCodec<SwampOgreFeature> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource randomSource, BlockPos pos) {
        // Find the surface
        BlockPos.MutableBlockPos mutable = pos.mutable();
        while (level.isEmptyBlock(mutable) && mutable.getY() > 2) {
            mutable.move(Direction.DOWN);
        }

        BlockPos surfacePos = mutable.immutable();
        BlockPos cornerPos = surfacePos.offset(-2, 0, -2);

        // Can only extend max 3 down in air
        mutable.set(cornerPos);
        if (level.isEmptyBlock(mutable)) return false;

        mutable.set(cornerPos).move(Direction.SOUTH, 3);
        if (level.isEmptyBlock(mutable)) return false;

        mutable.set(cornerPos).move(Direction.EAST, 3);
        if (level.isEmptyBlock(mutable)) return false;

        mutable.set(cornerPos).move(Direction.SOUTH, 3).move(Direction.EAST, 3);
        if (level.isEmptyBlock(mutable)) return false;

        // Generate
        StructureTemplate template = this.createTemplateFromCenter(location, level, randomSource, surfacePos);
        return template != null;
    }
}