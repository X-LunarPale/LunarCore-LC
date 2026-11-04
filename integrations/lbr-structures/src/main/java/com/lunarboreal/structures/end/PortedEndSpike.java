package com.lunarboreal.structures.end;

import com.yungnickyoung.minecraft.betterendisland.world.IEndSpike;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.phys.AABB;

/** Replaces the upstream EndSpike mixin with an ordinary server-side subclass. */
public final class PortedEndSpike extends EndSpikeFeature.EndSpike implements IEndSpike {
    private int crystalHeight;
    public PortedEndSpike(int x, int z, int radius, int height, boolean guarded) {
        super(x, z, radius, height, guarded);
        int variant = (height - 73) / 3;
        setCrystalYOffsetFromPillarHeight(variant == 10 ? 9 : variant);
    }
    @Override public AABB getTopBoundingBox() {
        return new AABB(getCenterX() - 9, net.minecraft.world.level.dimension.DimensionType.MIN_Y, getCenterZ() - 9,
                getCenterX() + 9, net.minecraft.world.level.dimension.DimensionType.MAX_Y, getCenterZ() + 9);
    }
    @Override public int getCrystalYOffset() { return crystalHeight; }
    @Override public void setCrystalYOffsetFromPillarHeight(int height) {
        crystalHeight = switch (height) { case 8, 7, 6 -> 32; case 5, 4 -> 27; case 3 -> 26; case 2, 1 -> 22; default -> 38; };
    }
}
