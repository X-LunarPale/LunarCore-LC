// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/ListPoolElementAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.util.List;

public interface ListPoolElementAccessor {
    List<StructurePoolElement> getElements();
}
