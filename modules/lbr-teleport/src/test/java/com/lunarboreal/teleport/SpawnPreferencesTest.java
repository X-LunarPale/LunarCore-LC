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

    private static long at(String value) {
        return Instant.parse(value).toEpochMilli();
    }
}
