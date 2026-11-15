package com.lunarboreal.teleport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnPreferencesTest {
    @Test void firstEverLoginCountsAsFirstLoginToday() {
        assertTrue(SpawnPreferences.firstJoinToday(0, at("2026-11-05T12:00:00Z"), ZoneId.of("UTC")));
    }

    @Test void laterReconnectOnSameDayKeepsTheLogoutLocation() {
        assertFalse(SpawnPreferences.firstJoinToday(at("2026-11-05T00:01:00Z"),
                at("2026-11-05T23:59:59Z"), ZoneId.of("UTC")));
    }

    @Test void dateBoundaryUsesConfiguredTimezone() {
        long previous = at("2026-11-05T15:59:59Z");
        long now = at("2026-11-05T16:00:00Z");
        assertTrue(SpawnPreferences.firstJoinToday(previous, now, ZoneId.of("Asia/Shanghai")));
        assertFalse(SpawnPreferences.firstJoinToday(previous, now, ZoneId.of("UTC")));
    }

    @Test void restartIsIndependentOfTheDailyBoundary() {
        long started = at("2026-11-05T12:00:00Z");
        assertTrue(SpawnPreferences.firstJoinAfterRestart(at("2026-11-05T11:59:59Z"), started));
        assertFalse(SpawnPreferences.firstJoinAfterRestart(started, started));
        assertFalse(SpawnPreferences.firstJoinAfterRestart(at("2026-11-05T12:00:01Z"), started));
    }

    private static long at(String value) {
        return Instant.parse(value).toEpochMilli();
    }
}
