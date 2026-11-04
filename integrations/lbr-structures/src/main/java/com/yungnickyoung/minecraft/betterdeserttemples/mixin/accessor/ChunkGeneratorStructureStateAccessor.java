// Paper reflection accessor; original source: third-party/yungs/desert-temples/upstream/Common/src/main/java/com/yungnickyoung/minecraft/betterdeserttemples/mixin/accessor/ChunkGeneratorStructureStateAccessor.java
package com.yungnickyoung.minecraft.betterdeserttemples.mixin.accessor;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

public interface ChunkGeneratorStructureStateAccessor {
    BiomeSource getBiomeSource();
}
