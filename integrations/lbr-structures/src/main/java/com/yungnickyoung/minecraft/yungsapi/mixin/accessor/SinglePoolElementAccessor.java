// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/SinglePoolElementAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public interface SinglePoolElementAccessor {

    StructureTemplate callGetTemplate(StructureTemplateManager $$0);
}
