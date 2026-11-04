// Paper reflection accessor; original source: third-party/yungs/end-island/upstream/Common/src/main/java/com/yungnickyoung/minecraft/betterendisland/mixin/accessor/EnderDragonFightAccessor.java
package com.yungnickyoung.minecraft.betterendisland.mixin.accessor;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;

import java.util.List;

public interface EnderDragonFightAccessor {
    @com.lunarboreal.structures.nms.NativeField("exitPortalLocation")
    BlockPos getPortalLocation();

    @com.lunarboreal.structures.nms.NativeField("exitPortalLocation")
    void setPortalLocation(BlockPos pos);

    @com.lunarboreal.structures.nms.NativeMethod("createNewDragon")
    EnderDragon invokeCreateNewDragon();

    @com.lunarboreal.structures.nms.NativeField("dragonKilled")
    void setDragonKilled(boolean killed);

    @com.lunarboreal.structures.nms.NativeField("hasPreviouslyKilledDragon")
    boolean getPreviouslyKilled();

    @com.lunarboreal.structures.nms.NativeField("dragonEvent")
    ServerBossEvent getDragonEvent();

    @com.lunarboreal.structures.nms.NativeField("gateways")
    List<Integer> getGateways();
}
