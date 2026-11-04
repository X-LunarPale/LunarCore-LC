// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples.entity;

import net.minecraft.world.phys.Vec3;

public interface IPharaohData {
    void setOriginalSpawnPos(Vec3 pos);
    Vec3 getOriginalSpawnPos();
}
