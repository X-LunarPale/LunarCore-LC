package com.lunarboreal.qqauth.auth;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.database.SqlFunction;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lunarboreal.qqauth.auth.AuthService.Result.*;

class AuthServiceTest {
    private Connection connection;
    private SqlAccountRepository repo;
    private AuthService service;
    private final UUID uuid = UUID.randomUUID();
    private static final String PASSWORD = "A-valid-password";
    private final MutableClock clock = new MutableClock();
    private boolean failCommit;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        Database database = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(), new Class<?>[]{Database.class}, (p, m, args) -> {
            if (m.getName().equals("table")) return "test_" + args[0];
            if (m.getName().equals("type")) return Database.Type.SQLITE;
            if (m.getName().equals("transaction")) {
                synchronized (connection) {
                    connection.setAutoCommit(false);
                    try {
                        @SuppressWarnings("unchecked") var work = (SqlFunction<Connection, Object>) args[0];
                        Object result = work.apply(connection);
                        if (failCommit) throw new SQLException("Injected commit failure");
                        connection.commit(); return result;
                    } catch (Throwable e) { connection.rollback(); throw e; }
                    finally { connection.setAutoCommit(true); }
                }
            }
            throw new UnsupportedOperationException(m.getName());
        });
        repo = new SqlAccountRepository(database); repo.initialize();
        service = new AuthService(repo, policy(100, 1000, false, 3), clock);
    }
    private AuthPolicy policy(int attempts, int entries, boolean sessions, int maxIp) {
        return new AuthPolicy(4, 10, 64, maxIp, attempts, Duration.ofMinutes(1), entries, sessions, Duration.ofMinutes(2), 100);
    }
    @AfterEach void close() throws Exception { connection.close(); }
    private void register() throws Exception { assertEquals(SUCCESS, service.register(uuid, "Player", "127.0.0.1", PASSWORD)); }

    @Test void identityIsCaseInsensitiveButBoundToUuid() throws Exception {
        register();
        assertEquals(ALREADY_REGISTERED, service.register(UUID.randomUUID(), "pLaYeR", "127.0.0.2", PASSWORD));
        assertEquals(IDENTITY_MISMATCH, service.login(UUID.randomUUID(), "PLAYER", "127.0.0.1", PASSWORD));
        assertEquals(IDENTITY_MISMATCH, service.register(uuid, "OtherName", "127.0.0.2", PASSWORD));
        service.logout(uuid);
        assertEquals(SUCCESS, service.login(uuid, "PLAYER", "127.0.0.1", PASSWORD));
        assertTrue(service.isAuthenticated(uuid));
    }
    @Test void databaseContainsOnlyBcryptAndPublicViewsHaveNoPassword() throws Exception {
        register();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT password_hash FROM test_qqauth_accounts")) {
            assertTrue(r.next()); String hash = r.getString(1);
            assertTrue(hash.startsWith("$2")); assertNotEquals(PASSWORD, hash); assertEquals(60, hash.length());
            assertFalse(service.accounts().toString().contains(hash));
        }
        assertEquals("PreparedPassword[redacted]", service.preparePassword(PASSWORD).toString());
        assertEquals(12, AuthPolicy.defaults().bcryptCost());
    }
    @Test void utf8ByteLimitAndCharacterBoundsAreEnforced() {
        assertFalse(service.validPassword("short"));
        assertTrue(service.validPassword("a".repeat(64)));
        assertFalse(service.validPassword("a".repeat(65)));
        assertTrue(service.validPassword("界".repeat(24)));
        assertFalse(service.validPassword("界".repeat(25)));
    }
    @Test void recoveryCasAndDeleteRecreatePreventStaleReset() throws Exception {
        register(); var old = service.account("player").orElseThrow().generation();
        var prepared = service.preparePassword("replacement-password");
        assertEquals(SUCCESS, service.setPassword("PLAYER", old, prepared));
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(STALE_GENERATION, service.setPassword("player", old, prepared));
        assertEquals(SUCCESS, service.deleteAccount("Player"));
        register();
        assertEquals(STALE_GENERATION, service.setPassword("player", old, prepared));
    }
    @Test void changeUnregisterAndAdminResetInvalidateAuthentication() throws Exception {
        register();
        assertEquals(WRONG_PASSWORD, service.changePassword(uuid,"player","127.0.0.1","wrong",PASSWORD));
        assertEquals(SUCCESS, service.changePassword(uuid,"player","127.0.0.1",PASSWORD,"replacement-password"));
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(WRONG_PASSWORD, service.login(uuid,"player","127.0.0.1",PASSWORD));
        assertEquals(SUCCESS, service.login(uuid,"player","127.0.0.1","replacement-password"));
        assertEquals(SUCCESS, service.adminReset("player",PASSWORD));
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(SUCCESS, service.login(uuid,"player","127.0.0.1",PASSWORD));
        assertEquals(SUCCESS, service.unregister(uuid,"player","127.0.0.1",PASSWORD));
        assertFalse(service.isAuthenticated(uuid)); assertTrue(service.accounts().isEmpty());
    }
    @Test void accountLockRevokesLoginAndRememberedSessions() throws Exception {
        register(); assertEquals(SUCCESS, service.setLocked("player",true));
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(LOCKED, service.login(uuid,"player","127.0.0.1",PASSWORD));
        assertEquals(SUCCESS, service.setLocked("player",false));
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(SUCCESS, service.login(uuid,"player","127.0.0.1",PASSWORD));
    }
    @Test void registrationQuotaIsAtomicUnderConcurrency() throws Exception {
        service = new AuthService(repo, policy(100,1000,false,1),clock);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<AuthService.Result>> futures = new ArrayList<>();
            for (int i=0;i<6;i++) { final String name="User"+i;
                futures.add(executor.submit(() -> service.register(UUID.randomUUID(),name,"127.0.0.1",PASSWORD)));
            }
            int success=0;
            for (var future:futures) { var result=future.get(); if(result==SUCCESS) success++; else assertEquals(IP_LIMIT,result); }
            assertEquals(1,success); assertEquals(1,service.accounts().size());
        }
    }
    @Test void concurrentRecoveryHasExactlyOneGenerationWinner() throws Exception {
        register(); String generation=service.account("player").orElseThrow().generation();
        var prepared=service.preparePassword("replacement-password");
        try (var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> service.setPassword("player",generation,prepared));
            var second=executor.submit(() -> service.setPassword("player",generation,prepared));
            var results=List.of(first.get(),second.get());
            assertEquals(1,results.stream().filter(r -> r==SUCCESS).count());
            assertEquals(1,results.stream().filter(r -> r==STALE_GENERATION).count());
        }
    }
    @Test void initializeReloadsCommittedAccountsWithoutRevivingSessions() throws Exception {
        register(); service.logout(uuid);
        repo.initialize();
        assertEquals(1,service.accounts().size());
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(SUCCESS,service.login(uuid,"PLAYER","127.0.0.1",PASSWORD));
    }
    @Test void rollbackNeverPublishesRegistrationOrPasswordMutation() throws Exception {
        failCommit=true;
        assertThrows(SQLException.class, this::register);
        assertTrue(service.accounts().isEmpty());
        failCommit=false; register(); String generation=service.account("player").orElseThrow().generation();
        failCommit=true;
        assertThrows(SQLException.class, () -> service.adminReset("player","replacement-password"));
        assertEquals(generation,service.account("player").orElseThrow().generation());
        failCommit=false;
        assertEquals(SUCCESS,service.login(uuid,"player","127.0.0.1",PASSWORD));
    }
    @Test void attemptsBoundBothIpAndAccountAndExpireWithoutEvictionBypass() throws Exception {
        register(); service = new AuthService(repo,policy(2,4,false,3),clock);
        assertEquals(WRONG_PASSWORD,service.login(uuid,"player","ip1","bad"));
        assertEquals(WRONG_PASSWORD,service.login(uuid,"PLAYER","ip2","bad"));
        assertEquals(THROTTLED,service.login(uuid,"player","ip3",PASSWORD));
        assertEquals(THROTTLED,service.login(UUID.randomUUID(),"Unknown","ip3",PASSWORD)); // capacity fails closed
        clock.advance(Duration.ofMinutes(1));
        assertEquals(SUCCESS,service.login(uuid,"player","ip1",PASSWORD));
        assertEquals(SUCCESS,service.login(uuid,"player","ip1",PASSWORD));
        assertEquals(THROTTLED,service.login(UUID.randomUUID(),"Unknown","ip1",PASSWORD)); // success did not clear IP
    }
    @Test void sameIpSessionsAreOptInFixedExpiryAndLogoutRevocable() throws Exception {
        register(); service.disconnect(uuid);
        assertEquals(NOT_AUTHENTICATED,service.resumeSession(uuid,"player","127.0.0.1"));
        service = new AuthService(repo,policy(100,1000,true,3),clock);
        assertEquals(SUCCESS,service.login(uuid,"player","127.0.0.1",PASSWORD));
        service.disconnect(uuid);
        assertEquals(NOT_AUTHENTICATED,service.resumeSession(uuid,"player","127.0.0.2"));
        assertEquals(SUCCESS,service.resumeSession(uuid,"PLAYER","127.0.0.1"));
        service.disconnect(uuid); clock.advance(Duration.ofMinutes(2));
        assertEquals(NOT_AUTHENTICATED,service.resumeSession(uuid,"player","127.0.0.1"));
        assertEquals(SUCCESS,service.login(uuid,"player","127.0.0.1",PASSWORD));
        service.logout(uuid);
        assertEquals(NOT_AUTHENTICATED,service.resumeSession(uuid,"player","127.0.0.1"));
    }
    @Test void verifiedUuidRenameKeepsPasswordAndInvalidatesOldSessionsAndRecovery() throws Exception {
        register();
        String generation = service.account("player").orElseThrow().generation();
        var prepared = service.preparePassword("replacement-password");
        assertEquals(SUCCESS, service.renameByUuid(uuid, "Renamed"));
        assertTrue(service.account("Player").isEmpty());
        assertEquals("Renamed", service.account(uuid).orElseThrow().name());
        assertFalse(service.isAuthenticated(uuid));
        assertEquals(NOT_REGISTERED, service.setPassword("Player", generation, prepared));
        assertEquals(STALE_GENERATION, service.setPassword("Renamed", generation, prepared));
        assertEquals(SUCCESS, service.login(uuid, "Renamed", "127.0.0.1", PASSWORD));
        String renamedGeneration = service.account("Renamed").orElseThrow().generation();
        assertEquals(SUCCESS, service.renameByUuid(uuid, "Renamed"));
        assertEquals(renamedGeneration, service.account("Renamed").orElseThrow().generation());
    }
    @Test void uuidRenameRejectsTargetCollisionAndNeverPublishesRollback() throws Exception {
        register();
        UUID other = UUID.randomUUID();
        assertEquals(SUCCESS, service.register(other, "OtherName", "127.0.0.2", PASSWORD));
        String generation = service.account("Player").orElseThrow().generation();
        assertEquals(IDENTITY_MISMATCH, service.renameByUuid(uuid, "OTHERNAME"));
        assertEquals(generation, service.account("Player").orElseThrow().generation());
        assertEquals(NOT_REGISTERED, service.renameByUuid(UUID.randomUUID(), "UnusedName"));
        failCommit = true;
        assertThrows(SQLException.class, () -> service.renameByUuid(uuid, "Renamed"));
        assertTrue(service.account("Renamed").isEmpty());
        assertEquals(generation, service.account("Player").orElseThrow().generation());
        failCommit = false;
        repo.initialize();
        assertEquals("Player", service.account(uuid).orElseThrow().name());
        assertTrue(service.account("Renamed").isEmpty());
    }
    private static final class MutableClock extends Clock {
        private long millis=1_000_000;
        void advance(Duration duration) { millis+=duration.toMillis(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
