package com.lunarboreal.qqauth;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.database.*;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.qqauth.auth.AuthService;
import com.lunarboreal.qqauth.auth.SqlAccountRepository;
import com.lunarboreal.qqauth.binding.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily replaces the Bukkit and LC global entry points")
class OptionalVerificationTest {
    @TempDir Path directory;
    private final UUID uuid = UUID.randomUUID();
    private final List<String> replies = new ArrayList<>();
    private final Player player = proxy(Player.class, (method, args) -> switch (method) {
        case "getUniqueId" -> uuid;
        case "getName" -> "Player";
        case "getAddress" -> new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        case "isOnline" -> true;
        case "sendMessage" -> {
            if (args[0] instanceof Component component)
                replies.add(PlainTextComponentSerializer.plainText().serialize(component));
            yield null;
        }
        default -> null;
    });
    private QQAuthModule module;
    private AuthListener listener;
    private TestDatabase database;
    private Server previousServer;
    private LunarBorealAPI previousApi;
    private ThreadPoolExecutor immediateWorkers;

    @BeforeEach void setup() throws Exception {
        previousServer = Bukkit.getServer();
        previousApi = LC.isLoaded() ? LC.get() : null;
        database = new TestDatabase(directory.resolve("accounts.db"));
        module = new QQAuthModule();
        module.init(new ModuleDescription("qqauth", "QQAuth", "test", "", "", List.of(), List.of(), List.of()),
                directory.resolve("config").toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config().set("binding.required", false);
        module.config().set("auth.bcrypt-cost", 4);
        module.config().set("auth.max-attempts", 100);
        module.config().set("auth.sessions.enabled", true);
        SqlAccountRepository accounts = new SqlAccountRepository(database);
        accounts.initialize();
        module.auth = new AuthService(accounts, module.policy());
        BindingRepository bindings = new BindingRepository(database);
        bindings.initialize();
        module.bindings = new BindingService(bindings, module::bindingSettings,
                (name, generation, prepared) -> module.auth.setPassword(name, generation, prepared) == AuthService.Result.SUCCESS);
        module.ready = true;
        listener = new AuthListener(module);
    }

    @AfterEach void cleanup() throws Exception {
        if (immediateWorkers != null) immediateWorkers.shutdownNow();
        LC.setInstance(previousApi);
        setField(Bukkit.class, null, "server", previousServer);
    }

    private void authenticate() throws Exception {
        assertEquals(AuthService.Result.SUCCESS, module.auth.register(uuid, "Player", module.ip(player), "valid-password"));
        module.granted.put(uuid, player);
        module.connections.put(uuid, player);
    }

    @Test void optionalQqAllowsJoiningAndGameplayOnlyAfterPasswordLogin() throws Exception {
        var prelogin = prelogin("Player");
        listener.prelogin(prelogin);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, prelogin.getLoginResult());
        var login = login();
        listener.login(login);
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login.getResult());
        assertFalse(module.authenticated(player));
        var before = new BlockBreakEvent(proxy(Block.class, (m, a) -> null), player);
        listener.breakBlock(before);
        assertTrue(before.isCancelled());
        authenticate();
        assertTrue(module.authenticated(player));
        var after = new BlockBreakEvent(proxy(Block.class, (m, a) -> null), player);
        listener.breakBlock(after);
        assertFalse(after.isCancelled());
        module.auth.logout(uuid);
        assertFalse(module.authenticated(player));
    }

    @Test void requiredQqStillRejectsUnboundConnections() throws Exception {
        module.config().set("binding.required", true);
        var before = prelogin("Player");
        listener.prelogin(before);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, before.getLoginResult());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(before.kickMessage()).contains("/验证"));
        var login = login();
        listener.login(login);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        module.bindings.repository().bind(new Binding(uuid, "Player", "qq", "group", 1), 1);
        module.bound.add(uuid);
        var after = prelogin("Player");
        listener.prelogin(after);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, after.getLoginResult());
        authenticate();
        assertTrue(module.authenticated(player));
    }

    @Test void optionalQqDoesNotBypassLockedOrConflictingAccounts() throws Exception {
        authenticate();
        assertEquals(AuthService.Result.SUCCESS, module.auth.setLocked("Player", true));
        var locked = login();
        listener.login(locked);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, locked.getResult());
        assertFalse(module.authenticated(player));
    }

    @Test void optionalUnboundStableUuidRenameKeepsItsAccount() throws Exception {
        authenticate();
        var rename = prelogin("Renamed");
        listener.prelogin(rename);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, rename.getLoginResult());
        assertEquals("Renamed", module.auth.account(uuid).orElseThrow().name());
        assertFalse(module.auth.isAuthenticated(uuid));
    }

    @Test void optionalUnboundRememberedSessionCanResumeOnJoin() throws Exception {
        authenticate();
        module.auth.disconnect(uuid);
        module.granted.clear();
        listener.join(new PlayerJoinEvent(player, Component.empty()));
        assertTrue(module.authenticated(player));
        assertTrue(replies.stream().anyMatch(text -> text.contains("会话已恢复")));
    }

    @Test void unboundPlayersCannotChangeResetDeleteOrUnbindTheirAccount() throws Exception {
        authenticate();
        for (String command : List.of("changepassword", "changepass", "qqauthreset", "unregister", "unbind")) {
            AuthCommands.execute(module, player, command, switch (command) {
                case "qqauthreset" -> new String[]{"password", "replacement-password"};
                case "unregister" -> new String[]{"valid-password"};
                case "unbind" -> new String[0];
                default -> new String[]{"valid-password", "replacement-password"};
            });
            assertTrue(replies.getLast().contains("请先完成 QQ 验证"), command);
        }
        assertEquals(AuthService.Result.SUCCESS, module.auth.login(uuid, "Player", "127.0.0.1", "valid-password"));
        assertTrue(module.auth.account(uuid).isPresent());
    }

    @Test void qqBindRequiresPasswordLoginAndNeedsNoAdministratorPermission() throws Exception {
        AuthCommands.execute(module, player, "qqauth", new String[]{"bind"});
        assertEquals("请先登录。", replies.getLast());
        authenticate();
        module.bound.add(uuid);
        AuthCommands.execute(module, player, "qqauth", new String[]{"bind"});
        assertEquals("你已经完成 QQ 验证。", replies.getLast());
    }

    @Test void loggedInUnboundPlayerReceivesARedeemableQqBindCode() throws Exception {
        authenticate();
        enableImmediateTasks();
        module.config().set("integration.group-id", "12345678");
        AuthCommands.execute(module, player, "qqauth", new String[]{"bind"});
        assertEquals(1, replies.size());
        String message = replies.getFirst();
        assertTrue(message.contains("群 12345678"));
        var code = java.util.regex.Pattern.compile("/验证 ([0-9]{6,8})").matcher(message);
        assertTrue(code.find(), message);
        assertEquals(BindingService.Status.SUCCESS, module.bindings.redeemBind(code.group(1), "12345678", "qq").status());
        assertTrue(module.authenticated(player));
        assertFalse(module.busy.contains(uuid));
    }

    @Test void boundPlayersCanChangePasswordThroughThePlayerCommand() throws Exception {
        authenticate();
        module.bound.add(uuid);
        module.bindings.repository().bind(new Binding(uuid, "Player", "qq", "group", 1), 1);
        var audit = proxy(AuditLog.class, (method, args) -> null);
        LC.setInstance(proxy(LunarBorealAPI.class, (method, args) -> method.equals("audit") ? audit : null));
        var workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10));
        var field = QQAuthModule.class.getDeclaredField("workers");
        field.setAccessible(true);
        field.set(module, workers);
        try {
            AuthCommands.execute(module, player, "changepassword", new String[]{"valid-password", "replacement-password"});
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            assertEquals(AuthService.Result.SUCCESS, module.auth.login(uuid, "Player", "127.0.0.1", "replacement-password"));
        } finally { workers.shutdownNow(); }
    }

    @Test void remindersOnlyApplyToAuthenticatedOptionalUnboundPlayers() throws Exception {
        assertFalse(module.needsBindingReminder(player));
        authenticate();
        assertTrue(module.needsBindingReminder(player));
        module.config().set("binding.reminder.enabled", false);
        assertFalse(module.needsBindingReminder(player));
        module.config().set("binding.reminder.enabled", true);
        module.bound.add(uuid);
        assertFalse(module.needsBindingReminder(player));
        module.bound.clear();
        module.config().set("binding.required", true);
        assertFalse(module.needsBindingReminder(player));
    }

    @Test void reminderDatesSurviveReconnectsAndNewRepositoryInstances() throws Exception {
        DailyBindingReminders first = new DailyBindingReminders(database);
        first.initialize();
        LocalDate today = LocalDate.of(2026, 11, 7);
        assertTrue(first.claim(uuid, today));
        assertFalse(first.claim(uuid, today));
        DailyBindingReminders restarted = new DailyBindingReminders(new TestDatabase(directory.resolve("accounts.db")));
        restarted.initialize();
        assertFalse(restarted.claim(uuid, today));
        assertTrue(restarted.claim(uuid, today.plusDays(1)));
        assertFalse(first.claim(uuid, today.plusDays(1)));
        assertTrue(first.claim(UUID.randomUUID(), today));
    }

    @Test void remindersSendOncePerDayAndRemainQuietAcrossReconnectAndRestart() throws Exception {
        authenticate();
        enableImmediateTasks();
        module.config().set("integration.group-id", "12345678");
        module.remindBinding(player);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().contains("无法改密、注销或解绑"));
        assertTrue(replies.getFirst().contains("群 12345678"));
        assertTrue(replies.getFirst().matches("(?s).*/验证 [0-9]{6,8}.*"));
        module.remindBinding(player);
        assertEquals(1, replies.size());

        module.reminderDays.clear();
        module.remindBinding(player);
        assertEquals(1, replies.size(), "A reconnect must reuse the saved reminder date");
        DailyBindingReminders restarted = new DailyBindingReminders(new TestDatabase(directory.resolve("accounts.db")));
        restarted.initialize();
        setField(QQAuthModule.class, module, "reminders", restarted);
        module.reminderDays.clear();
        module.remindBinding(player);
        assertEquals(1, replies.size(), "A new module instance must reuse the saved reminder date");

        LocalDate today = LocalDate.now(module.reminderZone());
        assertTrue(restarted.claim(uuid, today.minusDays(1)));
        module.reminderDays.put(uuid, today.minusDays(1));
        module.remindBinding(player);
        assertEquals(2, replies.size(), "An online player must be reminded on the next natural day");
        module.bound.add(uuid);
        module.reminderDays.clear();
        module.remindBinding(player);
        assertEquals(2, replies.size(), "Verified players no longer receive binding reminders");
    }

    @Test void reminderTimezoneMayFollowServerOrAnExplicitZone() {
        assertEquals(ZoneId.systemDefault(), module.reminderZone());
        module.config().set("binding.reminder.timezone", "Asia/Hong_Kong");
        assertEquals(ZoneId.of("Asia/Hong_Kong"), module.reminderZone());
    }

    private PlayerLoginEvent login() {
        return new PlayerLoginEvent(player, "localhost", InetAddress.getLoopbackAddress());
    }

    private AsyncPlayerPreLoginEvent prelogin(String name) {
        PlayerProfile profile = proxy(PlayerProfile.class, (method, args) -> switch (method) {
            case "getName" -> name;
            case "getId", "getUniqueId" -> uuid;
            default -> null;
        });
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), uuid, false, profile);
    }

    private void enableImmediateTasks() throws Exception {
        var task = proxy(BukkitTask.class, (method, args) -> null);
        var scheduler = proxy(BukkitScheduler.class, (method, args) -> {
            if (method.equals("runTask")) {
                ((Runnable) args[1]).run();
                return task;
            }
            return null;
        });
        setField(Bukkit.class, null, "server", proxy(Server.class, (method, args) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "getPlayer" -> uuid.equals(args[0]) ? player : null;
            default -> null;
        }));
        var plugin = proxy(Plugin.class, (method, args) -> null);
        var audit = proxy(AuditLog.class, (method, args) -> null);
        LC.setInstance(proxy(LunarBorealAPI.class, (method, args) -> switch (method) {
            case "plugin" -> plugin;
            case "audit" -> audit;
            default -> null;
        }));
        immediateWorkers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10)) {
            @Override public void execute(Runnable work) { work.run(); }
        };
        setField(QQAuthModule.class, module, "workers", immediateWorkers);
        DailyBindingReminders reminders = new DailyBindingReminders(database);
        reminders.initialize();
        setField(QQAuthModule.class, module, "reminders", reminders);
        module.active = true;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private interface Answer { Object value(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getName().equals("equals")) return object == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
            Object value = answer.value(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == void.class) return null;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == double.class) return 0d;
            return 0;
        });
    }

    private static final class TestDatabase implements Database {
        private final String url;
        private final ThreadLocal<Connection> current = new ThreadLocal<>();
        TestDatabase(Path path) { url = "jdbc:sqlite:" + path; }
        public Type type() { return Type.SQLITE; }
        public String table(String name) { return "test_" + name; }
        public String autoIncrementPk() { return "INTEGER PRIMARY KEY AUTOINCREMENT"; }
        public String upsert(String table, List<String> keys, List<String> columns) {
            return "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                    + String.join(",", Collections.nCopies(columns.size(), "?")) + ") ON CONFLICT("
                    + String.join(",", keys) + ") DO NOTHING";
        }
        public Connection connection() throws SQLException { return DriverManager.getConnection(url); }
        public int update(String sql, Object... params) throws SQLException {
            return execute(connection -> {
                try (var statement = connection.prepareStatement(sql)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }
        public long insert(String sql, Object... params) throws SQLException { return update(sql, params); }
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            return execute(connection -> {
                try (var statement = connection.prepareStatement(sql)) {
                    bind(statement, params);
                    try (var rows = statement.executeQuery()) {
                        List<T> result = new ArrayList<>();
                        while (rows.next()) result.add(mapper.map(rows));
                        return result;
                    }
                }
            });
        }
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            return query(sql, mapper, params).stream().findFirst();
        }
        public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
            if (current.get() != null) return work.apply(current.get());
            try (var connection = connection()) {
                connection.setAutoCommit(false);
                current.set(connection);
                try { T result = work.apply(connection); connection.commit(); return result; }
                catch (SQLException | RuntimeException ex) { connection.rollback(); throw ex; }
                finally { current.remove(); }
            }
        }
        private <T> T execute(SqlFunction<Connection, T> work) throws SQLException {
            if (current.get() != null) return work.apply(current.get());
            try (var connection = connection()) { return work.apply(connection); }
        }
        private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
            for (int index = 0; index < params.length; index++) statement.setObject(index + 1, params[index]);
        }
        public <T> CompletableFuture<T> async(SqlFunction<Database, T> work) {
            try { return CompletableFuture.completedFuture(work.apply(this)); }
            catch (SQLException ex) { return CompletableFuture.failedFuture(ex); }
        }
        public CompletableFuture<Integer> updateAsync(String sql, Object... params) { return async(db -> db.update(sql, params)); }
        public <T> CompletableFuture<List<T>> queryAsync(String sql, RowMapper<T> mapper, Object... params) {
            return async(db -> db.query(sql, mapper, params));
        }
    }
}
