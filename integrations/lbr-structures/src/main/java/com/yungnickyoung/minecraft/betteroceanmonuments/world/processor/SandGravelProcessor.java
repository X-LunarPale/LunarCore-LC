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
 * Replaces yellow stained glass with sand/gravel.
 */


public class SandGravelProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final SandGravelProcessor INSTANCE = new SandGravelProcessor();
    public static final MapCodec<SandGravelProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() == Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.YELLOW)) {
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.GRAVEL.defaultBlockState(), blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.SAND_GRAVEL_PROCESSOR;
    }
}
