// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdungeons.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;


import java.util.List;



public class CandleProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final CandleProcessor INSTANCE = new CandleProcessor();
    public static final MapCodec<CandleProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    private static final List<Block> CANDLES = List.of(Blocks.CANDLE, Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.WHITE), Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.GRAY),
            Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY), Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.BROWN), Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.GREEN), Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.PURPLE), Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.BLACK));

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() instanceof SeaPickleBlock) {
            RandomSource random = structurePlacementData.getRandom(blockInfoGlobal.pos());
            int numCandles = random.nextInt(4) + 1;
            boolean lit = random.nextFloat() < .1f;
            BlockState newBlockState = getRandomCandle(random).defaultBlockState()
                    .setValue(CandleBlock.CANDLES, numCandles)
                    .setValue(CandleBlock.LIT, lit);
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), newBlockState, blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    private static Block getRandomCandle(RandomSource random) {
        int i = random.nextInt(CANDLES.size());
        return CANDLES.get(i);
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.CANDLE_PROCESSOR;
    }
}
