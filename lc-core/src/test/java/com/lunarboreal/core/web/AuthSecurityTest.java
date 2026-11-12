package com.lunarboreal.core.web;

import com.lunarboreal.api.database.Database;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthSecurityTest {
    private AuthManager manager() throws Exception {
        AuthManager auth = new AuthManager();
        Database db = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(), new Class[]{Database.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "table" -> "test_" + args[0];
                    case "upsert" -> "upsert";
                    case "update" -> 1;
                    case "query" -> List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WebConfig cfg = new WebConfig(true, "127.0.0.1", 8765, "", "", false, "", "", "", 24,
                List.of(), 5, 10, List.of(), List.of());
        auth.init(db, cfg, AuthManager.randomSecret());
        return auth;
    }

    @Test void staleLoginCannotUndoLogoutOrPasswordReset() throws Exception {
        AuthManager auth = manager();
        AuthManager.UserRow stale = auth.user("admin");
        String token = auth.issue(stale);
        auth.invalidateTokens("admin");
        assertThrows(IllegalArgumentException.class, () -> auth.touch(stale, "127.0.0.1"));
        assertNull(auth.verify(token));
        AuthManager.UserRow beforeReset = auth.user("admin");
        auth.setPassword("admin", "replacement-password");
        assertThrows(IllegalArgumentException.class, () -> auth.touch(beforeReset, "127.0.0.1"));
        assertEquals(beforeReset.tokenVersion() + 1, auth.user("admin").tokenVersion());
    }

    @Test void staleLoginDoesNotRestorePreviousRoleOrDeletedUser() throws Exception {
        AuthManager auth = manager();
        AuthManager.UserRow stale = auth.createUser("alice", "example-password", "admin");
        auth.setRole("alice", "viewer");
        assertEquals("viewer", auth.touch(stale, "127.0.0.1").role());
        auth.deleteUser("alice");
        assertThrows(IllegalArgumentException.class, () -> auth.touch(stale, "127.0.0.1"));
        assertNull(auth.user("alice"));
    }

    @Test void deletedUserTokensDoNotReviveWhenNameIsRecreated() throws Exception {
        AuthManager auth = manager();
        AuthManager.UserRow original = auth.createUser("alice", "example-password", "viewer");
        String token = auth.issue(original);
        auth.deleteUser("alice");
        auth.createUser("alice", "another-password", "viewer");
        assertNull(auth.verify(token));
    }

    @Test void staleSelfPasswordChangeCannotOverrideAdminResetOrLogout() throws Exception {
        AuthManager auth = manager();
        AuthManager.UserRow verified = auth.user("admin");
        auth.setPassword("admin", "admin-reset-password");
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(verified, verified.tokenVersion(), "attacker-password"));
        assertTrue(AuthManager.verifyPassword("admin-reset-password", auth.user("admin").hash()));
        AuthManager.UserRow beforeLogout = auth.user("admin");
        auth.invalidateTokens("admin");
        assertThrows(IllegalArgumentException.class, () -> auth.changePassword(beforeLogout, beforeLogout.tokenVersion(), "attacker-password"));
        AuthManager.UserRow fresh = auth.user("admin");
        auth.changePassword(fresh, fresh.tokenVersion(), "new-valid-password");
        assertTrue(AuthManager.verifyPassword("new-valid-password", auth.user("admin").hash()));
    }

    @Test void expiredSocketIdentityCannotRefresh() throws Exception {
        AuthManager auth = manager();
        WebUserImpl expired = new WebUserImpl("admin", "admin", Set.of("*"), "expired", 1, auth.user("admin").tokenVersion());
        assertNull(auth.refresh(expired));
    }

    private List<String> enroll(AuthManager auth, String name) throws Exception {
        var user = auth.user(name);
        var enrollment = auth.beginTotp(user);
        assertTrue(enrollment.uri().startsWith("otpauth://totp/"));
        String code = Totp.code(Totp.decode(enrollment.secret()), System.currentTimeMillis() / 30_000, 6);
        return auth.enableTotp(user, user.tokenVersion(), code);
    }

    @Test void totpEnrollmentRevokesSessionsAndRecoveryCodesAreSingleUse() throws Exception {
        AuthManager auth = manager();
        String previousToken = auth.issue(auth.user("admin"));
        var recovery = enroll(auth, "admin");
        assertTrue(auth.totpEnabled("admin"));
        assertNull(auth.verify(previousToken));
        assertEquals(10, recovery.size());
        String challenge = auth.totpChallenge(auth.user("admin"), "local", "login.game", "game login");
        assertNull(auth.completeTotp(challenge, "local", "invalid"));
        var login = auth.completeTotp(challenge, "local", recovery.getFirst());
        assertNotNull(login);
        assertEquals("login.game", login.action());
        assertNull(auth.completeTotp(challenge, "local", recovery.get(1)));
        String next = auth.totpChallenge(auth.user("admin"), "local", "login", "password login");
        assertNull(auth.completeTotp(next, "local", recovery.getFirst()));
        assertNotNull(auth.completeTotp(next, "local", recovery.get(1)));
        var current = auth.user("admin");
        assertTrue(auth.disableTotp(current, current.tokenVersion(), recovery.get(2)));
        assertFalse(auth.totpEnabled("admin"));
    }

    @Test void pendingTotpCannotSurvivePasswordResetOrMoveToAnotherIp() throws Exception {
        AuthManager auth = manager();
        var recovery = enroll(auth, "admin");
        String pending = auth.totpChallenge(auth.user("admin"), "local", "login", "password login");
        assertNull(auth.completeTotp(pending, "different-ip", recovery.getFirst()));
        pending = auth.totpChallenge(auth.user("admin"), "local", "login", "password login");
        auth.setPassword("admin", "changed-password");
        assertNull(auth.completeTotp(pending, "local", recovery.getFirst()));
        assertTrue(auth.totpEnabled("admin"));
        auth.resetAdmin();
        assertFalse(auth.totpEnabled("admin"));
    }

    @Test void totpChallengeIsDiscardedAfterFiveFailedAttempts() throws Exception {
        AuthManager auth = manager();
        var recovery = enroll(auth, "admin");
        String pending = auth.totpChallenge(auth.user("admin"), "local", "login", "password login");
        for (int i = 0; i < 5; i++) assertNull(auth.completeTotp(pending, "local", "invalid"));
        assertTrue(auth.lockRemaining("local") > 0);
        assertNull(auth.completeTotp(pending, "local", recovery.getFirst()));
    }

    @Test void staleEnrollmentCannotActivateAfterPasswordReset() throws Exception {
        AuthManager auth = manager();
        var stale = auth.user("admin");
        var setup = auth.beginTotp(stale);
        auth.setPassword("admin", "changed-password");
        String code = Totp.code(Totp.decode(setup.secret()), System.currentTimeMillis() / 30_000, 6);
        assertThrows(RuntimeException.class, () -> auth.enableTotp(stale, stale.tokenVersion(), code));
        assertFalse(auth.totpEnabled("admin"));
    }

    @Test void missingStaticPathsAreNotRetained() throws Exception {
        StaticFiles files = new StaticFiles();
        ClassLoader empty = new ClassLoader(null) {};
        for (int i = 0; i < 2000; i++) assertNull(files.load(empty, "web", "missing-" + i + ".js", true));
        Field field = StaticFiles.class.getDeclaredField("cache");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(files)).isEmpty());
        assertNull(StaticFiles.safePath("../config.yml"));
        assertNull(StaticFiles.safePath("%2e%2e/config.yml"));
    }
}
