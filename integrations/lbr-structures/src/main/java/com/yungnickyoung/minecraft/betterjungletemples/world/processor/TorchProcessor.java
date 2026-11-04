// Paper port; original source and LGPL-3.0 notice: third-party/yungs/jungle-temples/
package com.yungnickyoung.minecraft.betterjungletemples.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterjungletemples.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;





public class TorchProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final TorchProcessor INSTANCE = new TorchProcessor();
    public static final MapCodec<TorchProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().is(Blocks.WALL_TORCH) || blockInfoGlobal.state().is(Blocks.TORCH)) {
            RandomSource randomSource = structurePlacementData.getRandom(blockInfoGlobal.pos());
            if (randomSource.nextFloat() < 0.9f) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.AIR.defaultBlockState(), null);
            }
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.TORCH_PROCESSOR;
    }
}
