// Paper reflection accessor; original source: third-party/yungs/mineshafts/upstream/Common/src/main/java/com/yungnickyoung/minecraft/bettermineshafts/mixin/BoundingBoxAccessor.java
package com.yungnickyoung.minecraft.bettermineshafts.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

public interface BoundingBoxAccessor {

    @com.lunarboreal.structures.nms.NativeField("minX")
    void setMinX(int minX);

    @com.lunarboreal.structures.nms.NativeField("minY")
    void setMinY(int minY);

    @com.lunarboreal.structures.nms.NativeField("minZ")
    void setMinZ(int minZ);

    @com.lunarboreal.structures.nms.NativeField("maxX")
    void setMaxX(int maxX);

    @com.lunarboreal.structures.nms.NativeField("maxY")
    void setMaxY(int maxY);

    @com.lunarboreal.structures.nms.NativeField("maxZ")
    void setMaxZ(int maxZ);
}
