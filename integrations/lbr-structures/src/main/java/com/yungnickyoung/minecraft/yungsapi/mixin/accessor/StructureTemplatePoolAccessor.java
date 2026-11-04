// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/StructureTemplatePoolAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.List;

public interface StructureTemplatePoolAccessor {
    List<Pair<StructurePoolElement, Integer>> getRawTemplates();
}
