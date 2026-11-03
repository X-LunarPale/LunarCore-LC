package com.lunarboreal.qqauth.binding;

import java.time.Duration;
import java.util.Map;

public record BindingSettings(int codeLength, Duration bindTtl, Duration unbindTtl, Duration resetTtl,
                              int maxPending, int defaultQuota, Map<String, Integer> perQqLimits) {
    public BindingSettings {
        if (codeLength < 6 || codeLength > 8) throw new IllegalArgumentException("codeLength must be 6..8");
        if (bindTtl == null || bindTtl.isNegative() || bindTtl.isZero()
                || unbindTtl == null || unbindTtl.isNegative() || unbindTtl.isZero()
                || resetTtl == null || resetTtl.isNegative() || resetTtl.isZero()) throw new IllegalArgumentException("TTLs must be positive");
        if (maxPending < 1 || defaultQuota < 1) throw new IllegalArgumentException("limits must be positive");
        perQqLimits = Map.copyOf(perQqLimits == null ? Map.of() : perQqLimits);
        perQqLimits.forEach((qq, limit) -> {
            if (qq == null || qq.isBlank() || limit == null || limit < 1) throw new IllegalArgumentException("invalid per-QQ limit");
        });
    }
    public static BindingSettings defaults() {
        return new BindingSettings(6, Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(5), 10000, 1, Map.of());
    }
    public int quotaFor(String qq) { return perQqLimits.getOrDefault(qq, defaultQuota); }
}
