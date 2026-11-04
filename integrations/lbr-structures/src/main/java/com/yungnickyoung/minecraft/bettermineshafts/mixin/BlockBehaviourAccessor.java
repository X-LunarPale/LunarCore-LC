// Paper reflection accessor; original source: third-party/yungs/mineshafts/upstream/Common/src/main/java/com/yungnickyoung/minecraft/bettermineshafts/mixin/BlockBehaviourAccessor.java
package com.yungnickyoung.minecraft.bettermineshafts.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public interface BlockBehaviourAccessor {

    boolean callCanSurvive(BlockState $$0, LevelReader $$1, BlockPos $$2);
}
