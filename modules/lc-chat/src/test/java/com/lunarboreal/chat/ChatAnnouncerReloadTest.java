package com.lunarboreal.chat;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.module.ModuleDescription;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ChatAnnouncerReloadTest {
    @TempDir Path directory;

    @Test
    void intervalAndRepeatedReloadsReplaceThePreviousAnnouncer() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.save(true, 30);
            fixture.module.onReload();
            Task first = fixture.announcer();
            assertNotNull(first);
            assertEquals(600, first.delay);
            assertEquals(600, first.period);

            fixture.save(true, 60);
            fixture.module.onReload();
            Task second = fixture.announcer();
            assertNotSame(first, second);
            assertTrue(first.isCancelled());
            assertEquals(1200, second.delay);
            assertEquals(1200, second.period);
            assertEquals(1, fixture.activeAnnouncers());

            fixture.module.onReload();
            assertTrue(second.isCancelled());
            assertNotSame(second, fixture.announcer());
            assertEquals(1, fixture.activeAnnouncers());
            assertEquals(2, fixture.activeTasks(), "Only the announcer and title refresh should remain");
        }
    }

    @Test
    void disablingAndReenablingAnnouncementsAppliesWithoutUnloadingTheModule() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.save(true, 30);
            fixture.module.onReload();
            Task original = fixture.announcer();

            fixture.save(false, 30);
            fixture.module.onReload();
            assertTrue(original.isCancelled());
            assertNull(fixture.announcer());
            assertEquals(0, fixture.activeAnnouncers());
            assertEquals(1, fixture.activeTasks(), "Disabling announcements must preserve the title refresh");

            fixture.save(true, 90);
            fixture.module.onReload();
            assertNotNull(fixture.announcer());
            assertEquals(1800, fixture.announcer().period);
            assertEquals(1, fixture.activeAnnouncers());
        }
    }

    @Test
    void moduleDisableCancelsTheAnnouncerAndOtherDisplayTasks() throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            fixture.save(true, 30);
            fixture.module.onReload();
            Task announcer = fixture.announcer();
            assertEquals(2, fixture.activeTasks());

            fixture.module.onDisable();
            assertTrue(announcer.isCancelled());
            assertNull(fixture.announcer());
            assertEquals(0, fixture.activeTasks());
            assertDoesNotThrow(fixture.module::onDisable);
            assertEquals(1, announcer.cancellations);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ChatModule module = new ChatModule();
        private final Path config;
        private final List<Task> tasks = new ArrayList<>();
        private final Field serverField;
        private final Server previousServer;
        private final LunarBorealAPI previousApi;
        private final Plugin plugin;

        Fixture(Path directory) throws Exception {
            config = directory.resolve("config.yml");
            serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            previousServer = (Server) serverField.get(null);
            previousApi = LC.isLoaded() ? LC.get() : null;
            plugin = proxy(Plugin.class, (name, args) -> switch (name) {
                case "getName" -> "LunarCore-Test";
                case "isEnabled" -> true;
                default -> throw new AssertionError("Unexpected plugin call: " + name);
            });
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (name, args) -> {
                if (!name.equals("runTaskTimer")) throw new AssertionError("Unexpected scheduler call: " + name);
                Task task = new Task(tasks.size() + 1, plugin, ((Number) args[2]).longValue(),
                        ((Number) args[3]).longValue());
                tasks.add(task);
                return task;
            });
            serverField.set(null, proxy(Server.class, (name, args) -> switch (name) {
                case "getScheduler" -> scheduler;
                case "getOnlinePlayers" -> List.of();
                default -> throw new AssertionError("Unexpected server call: " + name);
            }));
            LC.setInstance(proxy(LunarBorealAPI.class, (name, args) -> {
                if (name.equals("plugin")) return plugin;
                throw new AssertionError("Unexpected API call: " + name);
            }));
            module.init(new ModuleDescription("chat", "Chat", "test", ChatModule.class.getName(),
                            "", List.of(), List.of(), List.of()), directory.toFile(),
                    new ClassLoader(null) {}, Logger.getLogger(getClass().getName()));
        }

        void save(boolean enabled, long interval) throws Exception {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.set("announcer.enabled", enabled);
            configuration.set("announcer.interval-seconds", interval);
            configuration.set("announcer.messages", List.of("Announcement"));
            configuration.set("tablist.enabled", false);
            configuration.set("nameplate.enabled", false);
            configuration.save(config.toFile());
        }

        Task announcer() throws Exception {
            Field field = ChatModule.class.getDeclaredField("announcerTask");
            field.setAccessible(true);
            return (Task) field.get(module);
        }

        long activeAnnouncers() {
            return tasks.stream().filter(task -> !task.isCancelled() && task.period != 20).count();
        }

        long activeTasks() {
            return tasks.stream().filter(task -> !task.isCancelled()).count();
        }

        @Override public void close() throws Exception {
            try {
                module.onDisable();
                module.cleanupInternal();
            } finally {
                LC.setInstance(previousApi);
                serverField.set(null, previousServer);
            }
        }
    }

    private static final class Task implements BukkitTask {
        final long delay;
        final long period;
        private final int id;
        private final Plugin plugin;
        private int cancellations;

        Task(int id, Plugin plugin, long delay, long period) {
            this.id = id;
            this.plugin = plugin;
            this.delay = delay;
            this.period = period;
        }

        @Override public int getTaskId() { return id; }
        @Override public Plugin getOwner() { return plugin; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return cancellations > 0; }
        @Override public void cancel() { cancellations++; }
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] arguments) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> {
                    return switch (method.getName()) {
                        case "equals" -> object == args[0];
                        case "hashCode" -> System.identityHashCode(object);
                        case "toString" -> type.getSimpleName() + " test fixture";
                        default -> invocation.call(method.getName(), args);
                    };
                }));
    }
}
