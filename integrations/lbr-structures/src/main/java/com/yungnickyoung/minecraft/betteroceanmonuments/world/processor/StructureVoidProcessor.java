// Paper port; original source and LGPL-3.0 notice: third-party/yungs/ocean-monuments/
package com.yungnickyoung.minecraft.betteroceanmonuments.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betteroceanmonuments.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;



/**
 * Replaces certain placeholder blocks with block that currently exists in the world.
 * Ultimately has the same function as structure void, but makes it easy to visualize
 * how the structure blends into terrain when building.
 */


public class StructureVoidProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final StructureVoidProcessor INSTANCE = new StructureVoidProcessor();
    public static final MapCodec<StructureVoidProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().is(Blocks.PURPUR_SLAB) || blockInfoGlobal.state().is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.BROWN))) {
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), levelReader.getBlockState(blockInfoGlobal.pos()), blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.STRUCTURE_VOID_PROCESSOR;
    }
}
