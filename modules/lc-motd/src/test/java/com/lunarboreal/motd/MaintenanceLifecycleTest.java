package com.lunarboreal.motd;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.module.ModuleDescription;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceLifecycleTest {
    @TempDir Path directory;
    private static final Instant START = Instant.parse("2026-11-15T12:00:00Z");
    private static final Instant END = Instant.parse("2026-11-15T14:00:00Z");

    @Test void timerEntersOnceAndEndsWithoutRewritingManualSettings() throws Exception {
        try (Fixture fixture = new Fixture(directory, START.minusSeconds(1))) {
            fixture.load(scheduled(true));
            String original = Files.readString(directory.resolve("config.yml"));
            assertFalse(fixture.active(), "The saved manual switch must not override scheduled mode");
            fixture.clock.now = START;
            fixture.tick();
            assertTrue(fixture.active());
            assertEquals(1, fixture.visitorKicks.get());
            assertEquals(0, fixture.trustedKicks.get());
            fixture.tick();
            assertEquals(1, fixture.visitorKicks.get());
            fixture.clock.now = END;
            fixture.tick();
            assertFalse(fixture.active());
            assertEquals(original, Files.readString(directory.resolve("config.yml")));
        }
    }

    @Test void reloadWithinWindowRestoresRestrictionsWithoutReplayingKicks() throws Exception {
        try (Fixture fixture = new Fixture(directory, START.plusSeconds(1))) {
            fixture.load(scheduled(false));
            assertTrue(fixture.active());
            fixture.tick();
            assertEquals(0, fixture.visitorKicks.get());
            fixture.module.onReload();
            fixture.tick();
            assertTrue(fixture.active());
            assertEquals(0, fixture.visitorKicks.get());
            fixture.clock.now = END;
            fixture.tick();
            assertFalse(fixture.active());
        }
    }

    @Test void editingMessagesDoesNotKickAgainAndManualStopCancelsThePlan() throws Exception {
        try (Fixture fixture = new Fixture(directory, START.minusSeconds(1))) {
            fixture.load(scheduled(false));
            fixture.clock.now = START;
            fixture.tick();
            assertEquals(1, fixture.visitorKicks.get());
            MaintenanceSettings current = scheduled(false);
            MaintenanceSettings edited = new MaintenanceSettings(false, false, true, current.whitelist(),
                    "Updated", current.kickMessage(), "New first line", current.line2(), current.schedule());
            assertEquals(0, fixture.save(edited));
            assertEquals(1, fixture.visitorKicks.get());
            fixture.save(new MaintenanceSettings(false, false, true, current.whitelist(),
                    current.joinMessage(), current.kickMessage(), current.line1(), current.line2()));
            fixture.tick();
            assertFalse(fixture.active());
            assertEquals(1, fixture.visitorKicks.get());
        }
    }

    @Test void legacyClientPreservesExistingScheduleAndInvalidReloadKeepsWorkingState() throws Exception {
        MaintenanceSettings original = scheduled(false);
        Map<String, Object> legacy = new java.util.HashMap<>(original.webValues());
        legacy.keySet().removeAll(List.of("scheduleEnabled", "scheduleStart", "scheduleEnd"));
        assertEquals(original.schedule(), MaintenanceSettings.from(legacy, original.schedule()).schedule());
        try (Fixture fixture = new Fixture(directory, START.plusSeconds(1))) {
            fixture.load(original);
            YamlConfiguration invalid = new YamlConfiguration();
            original.apply(invalid);
            invalid.set("maintenance.schedule.start-at", "invalid");
            invalid.save(directory.resolve("config.yml").toFile());
            assertThrows(IllegalArgumentException.class, fixture.module::onReload);
            assertTrue(fixture.active());
            assertEquals(0, fixture.visitorKicks.get());
        }
    }

    private static MaintenanceSettings scheduled(boolean manualEnabled) {
        var defaults = MaintenanceSettings.defaults();
        return new MaintenanceSettings(manualEnabled, false, true, List.of("trusted_user"),
                defaults.joinMessage(), defaults.kickMessage(), defaults.line1(), defaults.line2(),
                new MaintenanceSchedule(true, START.toString(), END.toString()));
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock;
        final MotdModule module;
        final AtomicInteger visitorKicks = new AtomicInteger();
        final AtomicInteger trustedKicks = new AtomicInteger();
        private final Path config;
        private final Field serverField;
        private final Server previousServer;
        private final LunarBorealAPI previousApi;

        Fixture(Path directory, Instant now) throws Exception {
            clock = new MutableClock(now);
            module = new MotdModule(clock);
            config = directory.resolve("config.yml");
            serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            previousServer = (Server) serverField.get(null);
            previousApi = LC.isLoaded() ? LC.get() : null;
            List<Player> players = List.of(player("visitor", visitorKicks), player("trusted_user", trustedKicks));
            serverField.set(null, proxy(Server.class, (name, args) -> switch (name) {
                case "getMinecraftVersion" -> "26.3";
                case "getOnlinePlayers" -> players;
                case "getMaxPlayers" -> 100;
                case "isPrimaryThread" -> true;
                default -> throw new AssertionError("Unexpected server call: " + name);
            }));
            AuditLog audit = proxy(AuditLog.class, (name, args) -> null);
            LC.setInstance(proxy(LunarBorealAPI.class, (name, args) -> {
                if (name.equals("audit")) return audit;
                throw new AssertionError("Unexpected API call: " + name);
            }));
            module.init(new ModuleDescription("motd", "MOTD", "test", MotdModule.class.getName(),
                    "", List.of(), List.of(), List.of()), directory.toFile(),
                    new ClassLoader(null) {}, Logger.getLogger(getClass().getName()));
        }

        void load(MaintenanceSettings settings) throws Exception {
            YamlConfiguration yaml = new YamlConfiguration();
            new MotdSettings(true, "Normal", "Second", settings).apply(yaml);
            yaml.save(config.toFile());
            module.onReload();
        }

        void tick() throws Exception {
            var method = MotdModule.class.getDeclaredMethod("refreshSchedule");
            method.setAccessible(true);
            method.invoke(module);
        }

        int save(MaintenanceSettings settings) throws Exception {
            var method = MotdModule.class.getDeclaredMethod("save", MotdSettings.class);
            method.setAccessible(true);
            return (int) method.invoke(module, new MotdSettings(true, "Normal", "Second", settings));
        }

        boolean active() throws Exception {
            var method = MotdModule.class.getDeclaredMethod("maintenanceStatus");
            method.setAccessible(true);
            return (boolean) ((Map<?, ?>) method.invoke(module)).get("enabled");
        }

        @Override public void close() throws Exception {
            try { module.onDisable(); module.cleanupInternal(); }
            finally { LC.setInstance(previousApi); serverField.set(null, previousServer); }
        }
    }

    private static Player player(String name, AtomicInteger kicks) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> true;
            case "kick" -> { kicks.incrementAndGet(); yield null; }
            default -> throw new AssertionError("Unexpected player call: " + method);
        });
    }

    @FunctionalInterface private interface Invocation { Object call(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> switch (method.getName()) {
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            case "toString" -> type.getSimpleName() + "Test";
            default -> invocation.call(method.getName(), args);
        }));
    }
}
