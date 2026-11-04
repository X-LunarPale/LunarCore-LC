// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/BeardifierAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

import java.util.List;

public interface BeardifierAccessor {
    @com.lunarboreal.structures.nms.NativeField("pieces")
    List<Beardifier.Rigid> getPieces();

    @com.lunarboreal.structures.nms.NativeField("junctions")
    List<JigsawJunction> getJunctions();

    @com.lunarboreal.structures.nms.NativeField("affectedBox")
    BoundingBox getAffectedBox();
}
