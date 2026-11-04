// Paper reflection accessor; original source: third-party/yungs/ocean-monuments/upstream/Common/src/main/java/com/yungnickyoung/minecraft/betteroceanmonuments/mixin/accessor/ProjectileAccessor.java
package com.yungnickyoung.minecraft.betteroceanmonuments.mixin.accessor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public interface ProjectileAccessor {
    @Nullable EntityReference<Entity> getOwner();
}
