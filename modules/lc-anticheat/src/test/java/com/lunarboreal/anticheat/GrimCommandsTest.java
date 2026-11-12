package com.lunarboreal.anticheat;

import com.lunarboreal.anticheat.web.GrimCommands;
import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GrimCommandsTest {
    private String build(String id, Map<String, Object> args) { return GrimCommands.build(GrimCommands.find(id), args); }
    @Test void buildsOnlyFixedCommandPaths() {
        assertEquals("grim profile Steve", build("profile", Map.of("target", "Steve")));
        assertEquals("grim history Steve session latest page 2 --detailed --grep reach", build("history-session", Map.of("target", "Steve", "session", "latest", "page", 2.0, "detailed", true, "grep", "reach")));
        assertEquals("grim history migrate --delete", build("history-migrate", Map.of("delete", true)));
        assertEquals("grim list players", build("list", Map.of("target", "players")));
    }
    @Test void rejectsSelectorsNewlinesExtraArgumentsAndInvalidIntegers() {
        for (String invalid : new String[]{"@a", "Steve\nstop", "Steve reload", ";stop", "../server"})
            assertThrows(HttpException.class, () -> build("profile", Map.of("target", invalid)));
        assertThrows(HttpException.class, () -> build("help", Map.of("command", "stop")));
        assertThrows(HttpException.class, () -> build("log", Map.of("flagId", 1.5)));
        assertThrows(HttpException.class, () -> build("log", Map.of("flagId", Double.NaN)));
        assertThrows(HttpException.class, () -> build("history", Map.of("target", "Steve", "grep", "--delete")));
        assertThrows(HttpException.class, () -> build("sendalert", Map.of("message", "hello\r\nstop")));
        assertThrows(HttpException.class, () -> build("history-migrate", Map.of("delete", "true")));
        assertThrows(HttpException.class, () -> build("log", Map.of("flagId", "12")));
    }
    @Test void refusesPlayerSubscriptionsAndUnavailableStorageCopy() {
        for (String id : new String[]{"alerts", "brands", "verbose", "spectate", "stopspectating", "history-copy"}) {
            assertFalse(GrimCommands.find(id).supported());
            assertThrows(HttpException.class, () -> build(id, Map.of()));
        }
        assertEquals("anticheat.view", GrimCommands.find("profile").permission());
        assertEquals("grim consoledebug Steve", build("debug", Map.of("target", "Steve")));
        for (String id : new String[]{"dump", "log", "reload", "history-migrate", "sendalert", "debug"})
            assertEquals("anticheat.manage", GrimCommands.find(id).permission());
    }
}
