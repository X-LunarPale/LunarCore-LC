// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/NoiseChunkAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import net.minecraft.world.level.levelgen.NoiseChunk;

public interface NoiseChunkAccessor {
    @com.lunarboreal.structures.nms.NativeField("cellHeight")
    int getCellHeight();

    @com.lunarboreal.structures.nms.NativeField("cellCountY")
    int getCellCountY();

    @com.lunarboreal.structures.nms.NativeField("cellNoiseMinY")
    int getCellNoiseMinY();
}
