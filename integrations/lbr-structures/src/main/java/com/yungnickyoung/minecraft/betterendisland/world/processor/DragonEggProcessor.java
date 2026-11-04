// Paper port; original source and LGPL-3.0 notice: third-party/yungs/end-island/
package com.yungnickyoung.minecraft.betterendisland.world.processor;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterendisland.module.StructureProcessorTypeModule;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.NullMarked;



/**
 * Avoids overwriting the dragon egg.
 */
@NullMarked
public class DragonEggProcessor extends com.lunarboreal.structures.nms.LegacyStructureProcessor {
    public static final DragonEggProcessor INSTANCE = new DragonEggProcessor();
    public static final MapCodec<DragonEggProcessor> CODEC = MapCodec.unit(() -> INSTANCE);

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader levelReader,
                                                             BlockPos jigsawPiecePos,
                                                             BlockPos jigsawPieceBottomCenterPos,
                                                             StructureTemplate.StructureBlockInfo blockInfoLocal,
                                                             StructureTemplate.StructureBlockInfo blockInfoGlobal,
                                                             StructurePlaceSettings structurePlacementData) {
        if (levelReader.getBlockState(blockInfoGlobal.pos()).is(Blocks.DRAGON_EGG)) {
            blockInfoGlobal = new StructureTemplate.StructureBlockInfo(blockInfoGlobal.pos(), Blocks.DRAGON_EGG.defaultBlockState(), blockInfoGlobal.nbt());
        }
        return blockInfoGlobal;
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends StructureProcessor> getType() {
        return StructureProcessorTypeModule.DRAGON_EGG_PROCESSOR;
    }
}
