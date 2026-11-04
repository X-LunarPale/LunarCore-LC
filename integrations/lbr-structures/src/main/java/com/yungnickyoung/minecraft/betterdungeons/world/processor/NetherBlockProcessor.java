// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdungeons.BetterDungeonsCommon;
import com.yungnickyoung.minecraft.betterdungeons.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;





public class NetherBlockProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final NetherBlockProcessor INSTANCE = new NetherBlockProcessor();
    public static final MapCodec<NetherBlockProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (!BetterDungeonsCommon.CONFIG.general.enableNetherBlocks) {
            if (blockInfoGlobal.state().is(Blocks.SOUL_SAND) || blockInfoGlobal.state().is(Blocks.SOUL_SOIL)) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.COARSE_DIRT.defaultBlockState(), null);
            } else if (blockInfoGlobal.state().is(Blocks.SOUL_CAMPFIRE)) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.CAMPFIRE.defaultBlockState(), null);
            } else if (blockInfoGlobal.state().is(Blocks.SOUL_LANTERN)) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, blockInfoGlobal.state().getValue(LanternBlock.HANGING)), null);
            }
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.NETHER_BLOCK_PROCESSOR;
    }
}
