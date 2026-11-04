// Paper port; original source: third-party/yungs/jungle-temples/
package com.yungnickyoung.minecraft.betterjungletemples.services;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.ItemFrameProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;

public class FabricProcessorProvider implements IProcessorProvider {
    @Override
    public MapCodec<StructureProcessor> itemFrameProcessorCodec() {
        return ItemFrameProcessor.CODEC;
    }
}
