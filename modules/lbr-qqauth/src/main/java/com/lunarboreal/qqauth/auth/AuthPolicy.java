package com.lunarboreal.qqauth.auth;

import java.time.Duration;

/** Immutable policy. Password lengths count Unicode code points; BCrypt is limited to 72 UTF-8 bytes. */
public record AuthPolicy(int bcryptCost, int minPasswordLength, int maxPasswordLength,
                         int maxAccountsPerIp, int maxAttempts, Duration attemptWindow,
                         int maxAttemptEntries, boolean sessionsEnabled, Duration sessionTtl,
                         int maxSessions) {
    public AuthPolicy {
        if (bcryptCost < 4 || bcryptCost > 16 || minPasswordLength < 1
                || maxPasswordLength < minPasswordLength || maxPasswordLength > 64
                || maxAccountsPerIp < 1 || maxAttempts < 1 || maxAttemptEntries < 2
                || maxSessions < 1 || attemptWindow == null || attemptWindow.isNegative()
                || attemptWindow.isZero() || sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero())
            throw new IllegalArgumentException("Invalid authentication policy");
    }
    public static AuthPolicy defaults() {
        return new AuthPolicy(12, 10, 64, 3, 5, Duration.ofMinutes(5), 10000,
                false, Duration.ofMinutes(10), 10000);
    }
}
