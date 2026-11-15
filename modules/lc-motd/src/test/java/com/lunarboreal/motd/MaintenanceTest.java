package com.lunarboreal.motd;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceTest {
    private static Map<String, Object> body(boolean enabled, boolean allowJoin, boolean kickOnline, String whitelist) {
        return Map.of("enabled", enabled, "allowJoin", allowJoin, "kickOnline", kickOnline,
                "whitelist", whitelist, "joinMessage", MaintenanceSettings.DEFAULT_JOIN_MESSAGE,
                "kickMessage", MaintenanceSettings.DEFAULT_KICK_MESSAGE, "line1", MaintenanceSettings.DEFAULT_LINE1,
                "line2", MaintenanceSettings.DEFAULT_LINE2);
    }

    @Test void legacyConfigurationDefaultsMaintenanceOffAndPreservesNormalFields() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", false);
        yaml.set("line1", "legacy first");
        yaml.set("line2", "legacy second");
        MotdSettings loaded = MotdSettings.load(yaml);
        assertFalse(loaded.enabled());
        assertEquals("legacy first", loaded.line1());
        assertEquals("legacy second", loaded.line2());
        assertFalse(loaded.maintenance().enabled());
        assertEquals(MaintenanceSettings.defaults(), loaded.maintenance());
    }

    @Test void maintenanceConfigurationRoundTripsWithoutChangingNormalValues() {
        YamlConfiguration yaml = new YamlConfiguration();
        MotdSettings normal = new MotdSettings(false, "normal one", "normal two");
        MaintenanceSettings maintenance = MaintenanceSettings.from(body(true, false, true,
                "  Example_User  \n0123456789abcdef0123456789ABCDEF"));
        MotdSettings configured = normal.withMaintenance(maintenance);
        configured.apply(yaml);
        MotdSettings loaded = MotdSettings.load(yaml);
        assertEquals(normal.webValues(), loaded.webValues());
        assertEquals(List.of("example_user", "01234567-89ab-cdef-0123-456789abcdef"), loaded.maintenance().whitelist());
        assertEquals(maintenance.webValues(), loaded.maintenance().webValues());
    }

    @Test void admissionAndEvictionRespectIndependentOptionsAndWhitelist() {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        var byName = new MaintenancePolicy(MaintenanceSettings.from(body(true, false, false, "Trusted_User")));
        assertTrue(byName.rejectJoin(true, "visitor", UUID.randomUUID()));
        assertFalse(byName.rejectJoin(false, "visitor", UUID.randomUUID()));
        assertFalse(byName.rejectJoin(true, "trusted_user", UUID.randomUUID()));
        assertFalse(byName.kickOnRequest("TRUSTED_USER", UUID.randomUUID()));
        assertTrue(byName.kickOnRequest("visitor", UUID.randomUUID()));

        var byUuid = new MaintenancePolicy(MaintenanceSettings.from(body(true, false, false, id.toString())));
        assertFalse(byUuid.rejectJoin(true, "renamed", id));
        assertFalse(byUuid.kickOnRequest("renamed", id));
        assertTrue(byUuid.rejectJoin(true, "visitor", UUID.randomUUID()));

        var allow = new MaintenancePolicy(MaintenanceSettings.from(body(true, true, false, "")));
        assertFalse(allow.rejectJoin(true, "visitor", UUID.randomUUID()));
        assertTrue(allow.kickOnRequest("visitor", UUID.randomUUID()));
    }

    @Test void automaticKickUsesEffectiveTransitionsAndDoesNotRepeatForContentEdits() {
        var off = MaintenanceSettings.from(body(false, false, true, ""));
        var active = MaintenanceSettings.from(body(true, false, true, ""));
        var activeNoKick = MaintenanceSettings.from(body(true, false, false, ""));
        var offState = MaintenanceState.at(off, Instant.EPOCH);
        var activeState = MaintenanceState.at(active, Instant.EPOCH);
        assertTrue(activeState.shouldKickAfter(offState, true, true));
        assertFalse(activeState.shouldKickAfter(activeState, true, true));
        assertFalse(activeState.shouldKickAfter(activeState, true, activeNoKick.kickOnline()));
        assertFalse(activeState.shouldKickAfter(activeState, false, false));
        assertTrue(activeState.shouldKickAfter(activeState, false, true));
        assertFalse(offState.shouldKickAfter(activeState, true, true));
    }

    @Test void rejectsMalformedIdentitiesAndUnsafeConfiguration() {
        for (String entry : List.of("AB", "invalid-name", "../Player", "not-a-uuid"))
            assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.from(body(true, false, false, entry)));
        assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.from(body(true, false, false, "valid_name\n" + "Player_".repeat(80))));
        Map<String, Object> changed = new HashMap<>(body(false, false, false, ""));
        changed.put("enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.from(changed));
        Map<String, Object> extra = new HashMap<>(body(false, false, false, ""));
        extra.put("unexpected", "value");
        assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.from(extra));
        assertThrows(IllegalArgumentException.class, () -> MaintenanceSettings.from(body(false, false, false, "valid_name\n" + "AnotherName\n".repeat(500))));
    }

    @Test void kickEndpointRejectsSurprisePayloadFields() {
        MotdModule.validateKickBody(null);
        MotdModule.validateKickBody(Map.of("row", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> MotdModule.validateKickBody(Map.of("confirmation", true)));
    }

    @Test void scheduleValidatesWindowAndReportsActiveTransition() {
        var schedule = new MaintenanceSchedule(true,
                "2026-11-15T20:00:00+08:00", "2026-11-15T22:00:00+08:00");
        assertFalse(schedule.activeAt(Instant.parse("2026-11-15T11:59:59Z")));
        assertTrue(schedule.activeAt(Instant.parse("2026-11-15T12:00:00Z")));
        assertTrue(schedule.activeAt(Instant.parse("2026-11-15T13:59:59Z")));
        assertFalse(schedule.activeAt(Instant.parse("2026-11-15T14:00:00Z")));
        assertEquals("2026-11-15T20:00+08:00", schedule.nextTransitionAt(Instant.parse("2026-11-15T11:00:00Z")));
        assertEquals("2026-11-15T22:00+08:00", schedule.nextTransitionAt(Instant.parse("2026-11-15T12:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceSchedule(true, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceSchedule(true,
                "2026-11-15T22:00:00+08:00", "2026-11-15T20:00:00+08:00"));
    }

    @Test void scheduledMaintenanceSettingsRoundTripThroughWebValues() {
        Map<String, Object> configured = new HashMap<>(body(false, false, false, ""));
        configured.put("scheduleEnabled", true);
        configured.put("scheduleStart", "2026-11-15T20:00:00+08:00");
        configured.put("scheduleEnd", "2026-11-15T22:00:00+08:00");
        MaintenanceSettings settings = MaintenanceSettings.from(configured);
        assertEquals("2026-11-15T20:00+08:00", settings.schedule().startAt());
        assertEquals("2026-11-15T22:00+08:00", settings.schedule().endAt());
        assertEquals(settings.webValues(), MaintenanceSettings.from(settings.webValues()).webValues());
    }

    @Test void openEndedScheduleAndDisabledModeHaveUnambiguousPhases() {
        var ongoing = new MaintenanceSchedule(true, "2026-11-15T20:00:00+08:00", "");
        assertEquals("pending", ongoing.phaseAt(Instant.parse("2026-11-15T11:00:00Z")));
        assertEquals("active", ongoing.phaseAt(Instant.parse("2027-01-01T00:00:00Z")));
        assertEquals("", ongoing.nextTransitionAt(Instant.parse("2027-01-01T00:00:00Z")));
        assertEquals("disabled", MaintenanceSchedule.defaults().phaseAt(Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceSchedule(false, "", "2026-11-15T20:00:00+08:00"));
        YamlConfiguration malformed = new YamlConfiguration();
        malformed.set("maintenance.schedule.enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> MaintenanceSchedule.load(malformed));
    }

}
