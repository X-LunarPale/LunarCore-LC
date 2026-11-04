// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons.world.processor.small_dungeon;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdungeons.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Replaces any liquid in the ceiling with cobblestone to attempt to minimize weird
 * floating fluid interactions.
 */


public class SmallDungeonCeilingProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final SmallDungeonCeilingProcessor INSTANCE = new SmallDungeonCeilingProcessor();
    public static final MapCodec<SmallDungeonCeilingProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() == Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.ORANGE)) {
            if (levelReader.getFluidState(blockInfoGlobal.pos()).is(FluidTags.WATER) || levelReader.getFluidState(blockInfoGlobal.pos()).is(FluidTags.LAVA)) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.COBBLESTONE.defaultBlockState(), null);
            } else {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), levelReader.getBlockState(blockInfoGlobal.pos()), null);
            }
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.SMALL_DUNGEON_CEILING_PROCESSOR;
    }
}
