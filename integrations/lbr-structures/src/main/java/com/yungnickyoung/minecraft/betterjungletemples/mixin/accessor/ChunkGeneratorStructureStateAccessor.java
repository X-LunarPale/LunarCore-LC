// Paper reflection accessor; original source: third-party/yungs/jungle-temples/upstream/Common/src/main/java/com/yungnickyoung/minecraft/betterjungletemples/mixin/accessor/ChunkGeneratorStructureStateAccessor.java
package com.yungnickyoung.minecraft.betterjungletemples.mixin.accessor;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

public interface ChunkGeneratorStructureStateAccessor {
    BiomeSource getBiomeSource();
}
