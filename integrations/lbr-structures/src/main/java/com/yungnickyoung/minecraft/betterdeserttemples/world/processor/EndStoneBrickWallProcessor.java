// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdeserttemples.module.StructureProcessorModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;



/**
 * Replaces end stone brick walls w/ sandstone walls.
 */


public class EndStoneBrickWallProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final EndStoneBrickWallProcessor INSTANCE = new EndStoneBrickWallProcessor();
    public static final MapCodec<EndStoneBrickWallProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() == Blocks.END_STONE_BRICK_WALL) {
            BlockState blockState = Blocks.SANDSTONE_WALL.defaultBlockState()
                    .setValue(WallBlock.EAST, blockInfoGlobal.state().getValue(WallBlock.EAST))
                    .setValue(WallBlock.WEST, blockInfoGlobal.state().getValue(WallBlock.WEST))
                    .setValue(WallBlock.NORTH, blockInfoGlobal.state().getValue(WallBlock.NORTH))
                    .setValue(WallBlock.SOUTH, blockInfoGlobal.state().getValue(WallBlock.SOUTH))
                    .setValue(WallBlock.UP, blockInfoGlobal.state().getValue(WallBlock.UP))
                    .setValue(WallBlock.WATERLOGGED, blockInfoGlobal.state().getValue(WallBlock.WATERLOGGED));
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), blockState, blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorModule.END_STONE_BRICK_WALL_PROCESSOR;
    }
}
