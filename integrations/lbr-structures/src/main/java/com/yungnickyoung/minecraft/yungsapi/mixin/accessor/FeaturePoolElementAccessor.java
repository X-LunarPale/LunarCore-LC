// Paper reflection accessor; original source: third-party/yungs/api/upstream/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/mixin/accessor/FeaturePoolElementAccessor.java
package com.yungnickyoung.minecraft.yungsapi.mixin.accessor;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;

public interface FeaturePoolElementAccessor {
    Holder<PlacedFeature> getFeature();
}
