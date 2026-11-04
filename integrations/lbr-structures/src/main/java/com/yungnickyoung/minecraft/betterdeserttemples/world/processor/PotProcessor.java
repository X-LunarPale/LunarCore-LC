// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterdeserttemples.module.StructureProcessorModule;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;



/**
 * Randomizes decorated pots' sherds.
 */


public class PotProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final PotProcessor INSTANCE = new PotProcessor();
    public static final MapCodec<PotProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (blockInfoGlobal.state().getBlock() == Blocks.DECORATED_POT) {
            RandomSource randomSource = structurePlacementData.getRandom(blockInfoGlobal.pos());

            CompoundTag newNBT = blockInfoGlobal.nbt() == null ? new CompoundTag() : blockInfoGlobal.nbt().copy();
            // 26.3 uses named sides containing ItemStackTemplate values, in the same RNG order.
            CompoundTag sherds = new CompoundTag();
            for (String side : new String[]{"back", "left", "right", "front"}) {
                sherds.putString(side, getRandomSherd(randomSource));
            }
            newNBT.put("sherds", sherds);
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.DECORATED_POT.defaultBlockState(), newNBT);
        }
        return blockInfoGlobal;
    }

    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorModule.POT_PROCESSOR;
    }

    private String getRandomSherd(RandomSource random) {
        float f = random.nextFloat();
        if (f < 0.05f) {
            return "minecraft:archer_pottery_sherd";
        } else if (f < 0.1f) {
            return "minecraft:miner_pottery_sherd";
        } else if (f < 0.15f) {
            return "minecraft:prize_pottery_sherd";
        } else if (f < 0.2f) {
            return "minecraft:skull_pottery_sherd";
        }
        return "minecraft:brick";
    }
}
