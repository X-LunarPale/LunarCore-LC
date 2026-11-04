package com.lunarboreal.structures.nms;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Adapts the 26.1 processor contract to 26.3 while retaining upstream processing code. */
public abstract class LegacyStructureProcessor implements StructureProcessor {
    @Override
    public final StructureBlockInfo processBlock(LevelReader level, BlockPos piecePos, BlockPos pivot,
            BlockPos localPos, StructureBlockInfo global, StructurePlaceSettings settings) {
        return processBlock(level, piecePos, pivot, new StructureBlockInfo(localPos, global.state(), global.nbt()), global, settings);
    }

    public StructureBlockInfo processBlock(LevelReader level, BlockPos piecePos, BlockPos pivot,
            StructureBlockInfo local, StructureBlockInfo global, StructurePlaceSettings settings) { return global; }

    protected abstract MapCodec<? extends StructureProcessor> getType();
    @Override public final MapCodec<? extends StructureProcessor> codec() { return getType(); }
}
