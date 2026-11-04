// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons.world.processor.small_nether_dungeon;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdungeons.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;





public class SmallNetherDungeonLavaBlockProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final SmallNetherDungeonLavaBlockProcessor INSTANCE = new SmallNetherDungeonLavaBlockProcessor();
    public static final MapCodec<SmallNetherDungeonLavaBlockProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().is(Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.ORANGE))) {
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.LAVA.defaultBlockState(), null);
            if (levelReader instanceof WorldGenRegion worldGenRegion && !worldGenRegion.getCenter().equals(ChunkPos.containing(blockInfoGlobal.pos()))) {
                return blockInfoGlobal;
            }
            levelReader.getChunk(blockInfoGlobal.pos()).markPosForPostProcessing(blockInfoGlobal.pos()); // Schedule fluid tick
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.SMALL_NETHER_DUNGEON_LAVA_BLOCK_PROCESSOR;
    }
}
