package com.lunarboreal.land;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure geometry shared by estimates, creation, expansion and bounded particle rendering. */
final class LandGeometry {
    private LandGeometry() {}

    static long height(int minY, int maxY, int worldMin, int worldMaxExclusive) {
        return Math.max(0L, (long) Math.min(maxY, worldMaxExclusive - 1) - Math.max(minY, worldMin) + 1);
    }

    static BigDecimal cost(BigDecimal price, long area, boolean volume, int minY, int maxY,
                           int worldMin, int worldMaxExclusive) {
        BigDecimal units = BigDecimal.valueOf(area);
        if (volume) units = units.multiply(BigDecimal.valueOf(height(minY, maxY, worldMin, worldMaxExclusive)));
        return price.max(BigDecimal.ZERO).multiply(units).setScale(2, RoundingMode.HALF_UP);
    }

    static boolean bypass(boolean hasAdminPermission, boolean defaultEnabled, boolean enabled, boolean disabled) {
        return hasAdminPermission && !disabled && (defaultEnabled || enabled);
    }

    static boolean spongeProtected(Land source, Land target) {
        return source != target;
    }
}
