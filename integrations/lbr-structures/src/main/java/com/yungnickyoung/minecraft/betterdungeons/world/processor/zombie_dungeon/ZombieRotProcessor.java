// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons.world.processor.zombie_dungeon;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdungeons.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Replaces certain blocks w/ air in zombie dungeons when air is already there,
 * giving them a more natural, ruined look that opens up to caves.
 */


public class ZombieRotProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final ZombieRotProcessor INSTANCE = new ZombieRotProcessor();
    public static final MapCodec<ZombieRotProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() == Blocks.COBBLESTONE || blockInfoGlobal.state().getBlock() == Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.CYAN) || blockInfoGlobal.state().getBlock() == Blocks.COBBLESTONE_STAIRS) {
            if (levelReader.getBlockState(blockInfoGlobal.pos()).isAir()) {
                blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.CAVE_AIR.defaultBlockState(), null);
            }
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.ZOMBIE_ROT_PROCESSOR;
    }
}
