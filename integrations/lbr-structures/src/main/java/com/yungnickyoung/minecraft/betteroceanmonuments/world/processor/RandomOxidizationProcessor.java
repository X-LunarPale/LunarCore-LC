// Paper port; original source and LGPL-3.0 notice: third-party/yungs/ocean-monuments/
package com.yungnickyoung.minecraft.betteroceanmonuments.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betteroceanmonuments.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;



/**
 * Randomizes the oxidization level of copper blocks.
 */


public class RandomOxidizationProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final RandomOxidizationProcessor INSTANCE = new RandomOxidizationProcessor();
    public static final MapCodec<RandomOxidizationProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        RandomSource random = structurePlacementData.getRandom(blockInfoGlobal.pos());
        BlockState blockState;
        if (blockInfoGlobal.state().getBlock() == com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_copper")) {
            if (random.nextFloat() < 0.1f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("exposed_copper").defaultBlockState();
            else if (random.nextFloat() < 0.3f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("weathered_copper").defaultBlockState();
            else blockState = com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_copper").defaultBlockState();
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), blockState, blockInfoGlobal.nbt());
        } else if (blockInfoGlobal.state().getBlock() == com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper")) {
            if (random.nextFloat() < 0.1f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("exposed_cut_copper").defaultBlockState();
            else if (random.nextFloat() < 0.3f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("weathered_cut_copper").defaultBlockState();
            else blockState = com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper").defaultBlockState();
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), blockState, blockInfoGlobal.nbt());
        } else if (blockInfoGlobal.state().getBlock() == com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper_stairs")) {
            if (random.nextFloat() < 0.1f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("exposed_cut_copper_stairs").withPropertiesOf(blockInfoGlobal.state());
            else if (random.nextFloat() < 0.3f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("weathered_cut_copper_stairs").withPropertiesOf(blockInfoGlobal.state());
            else blockState = com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper_stairs").withPropertiesOf(blockInfoGlobal.state());
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), blockState, blockInfoGlobal.nbt());
        } else if (blockInfoGlobal.state().getBlock() == com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper_slab")) {
            if (random.nextFloat() < 0.1f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("exposed_cut_copper_slab").withPropertiesOf(blockInfoGlobal.state());
            else if (random.nextFloat() < 0.3f) blockState = com.lunarboreal.structures.nms.PaperBlocks.block("weathered_cut_copper_slab").withPropertiesOf(blockInfoGlobal.state());
            else blockState = com.lunarboreal.structures.nms.PaperBlocks.block("oxidized_cut_copper_slab").withPropertiesOf(blockInfoGlobal.state());
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), blockState, blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.RANDOM_OXIDIZATION_PROCESSOR;
    }
}
