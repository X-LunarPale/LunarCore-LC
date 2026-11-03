package com.lunarboreal.core.web;

import com.google.gson.reflect.TypeToken;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.web.HttpException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Persisted second factors and short-lived, single-use pre-auth challenges. */
final class TotpManager {
    record Login(AuthManager.UserRow user, String action, String how) {}
    record Enrollment(String secret, String uri, long expiresAt) {}
    private record Factor(String cipher, long lastStep, List<String> recoveryHashes) {}
    private record Setup(String secret, int tokenVersion, long expiresAt) {}
    private record Challenge(String username, String hash, int tokenVersion, String ip, String action,
                             String how, long expiresAt, int attempts) {
        Challenge failed() { return new Challenge(username, hash, tokenVersion, ip, action, how, expiresAt, attempts + 1); }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Database db;
    private final String table;
    private final byte[] key;
    private final Map<String, Factor> factors = new HashMap<>();
    private final Map<String, Setup> setups = new HashMap<>();
    private final Map<String, Challenge> challenges = new HashMap<>();

    TotpManager(Database db, String jwtSecret) throws SQLException {
        this.db = db;
        this.table = db.table("web_totp");
        try { key = MessageDigest.getInstance("SHA-256").digest(("LunarCore TOTP v1:" + jwtSecret).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ex) { throw new IllegalStateException("TOTP key unavailable", ex); }
        db.update("CREATE TABLE IF NOT EXISTS " + table + " (username VARCHAR(32) NOT NULL PRIMARY KEY, "
                + "secret_cipher TEXT NOT NULL, last_step BIGINT NOT NULL, recovery_hashes TEXT NOT NULL)");
        for (var row : db.query("SELECT username, secret_cipher, last_step, recovery_hashes FROM " + table,
                rs -> Map.entry(rs.getString(1).toLowerCase(Locale.ROOT), new Factor(rs.getString(2), rs.getLong(3),
                        Json.GSON.fromJson(rs.getString(4), new TypeToken<List<String>>() {}.getType()))))) {
            factors.put(row.getKey(), row.getValue());
        }
    }

    synchronized boolean enabled(String username) { return factors.containsKey(username.toLowerCase(Locale.ROOT)); }

    synchronized Enrollment begin(AuthManager.UserRow user) {
        if (enabled(user.username())) throw HttpException.badRequest("两步验证已经启用");
        trim();
        if (setups.size() >= 256) throw new HttpException(429, "设置请求过多，请稍后再试");
        String secret = Totp.newSecret();
        long expires = System.currentTimeMillis() + 10 * 60_000L;
        setups.put(user.username().toLowerCase(Locale.ROOT), new Setup(secret, user.tokenVersion(), expires));
        String label = URLEncoder.encode("LunarCore:" + user.username(), StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=LunarCore&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, uri, expires);
    }

    synchronized List<String> confirm(AuthManager.UserRow user, int sessionVersion, String code) throws SQLException {
        String name = user.username().toLowerCase(Locale.ROOT);
        Setup setup = setups.get(name);
        if (setup == null || enabled(name) || setup.expiresAt() < System.currentTimeMillis()
                || setup.tokenVersion() != sessionVersion || user.tokenVersion() != sessionVersion) {
            throw HttpException.badRequest("设置已过期，请重新开始");
        }
        long step = Totp.matchingStep(setup.secret(), code, System.currentTimeMillis(), -1);
        if (step < 0) throw HttpException.badRequest("动态验证码无效");
        List<String> recovery = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] random = new byte[8]; RANDOM.nextBytes(random);
            String value = Totp.base32(random);
            recovery.add(value.substring(0, 6) + "-" + value.substring(6));
            hashes.add(recoveryHash(value));
        }
        Factor factor = new Factor(encrypt(setup.secret()), step, List.copyOf(hashes));
        persist(name, factor);
        factors.put(name, factor);
        setups.remove(name);
        challenges.values().removeIf(c -> c.username().equals(name));
        return recovery;
    }

    synchronized String challenge(AuthManager.UserRow user, String ip, String action, String how) {
        trim();
        if (challenges.size() >= 1024) throw new HttpException(429, "登录验证请求过多，请稍后再试");
        byte[] random = new byte[24]; RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        challenges.put(token, new Challenge(user.username().toLowerCase(Locale.ROOT), user.hash(),
                user.tokenVersion(), ip, action, how, System.currentTimeMillis() + 5 * 60_000L, 0));
        return token;
    }

    synchronized Login complete(AuthManager auth, String token, String ip, String code) throws SQLException {
        Challenge pending = challenges.get(token);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis() || !pending.ip().equals(ip)) {
            challenges.remove(token);
            auth.recordFailure(ip);
            return null;
        }
        String userKey = "user:" + pending.username();
        AuthManager.UserRow current = auth.user(pending.username());
        if (auth.lockRemaining(ip) > 0 || auth.lockRemaining(userKey) > 0 || current == null
                || current.tokenVersion() != pending.tokenVersion() || !current.hash().equals(pending.hash())) {
            challenges.remove(token);
            return null;
        }
        if (!verify(current.username(), code)) {
            auth.recordFailure(userKey);
            auth.recordFailure(ip);
            if (pending.attempts() >= 4) challenges.remove(token);
            else challenges.put(token, pending.failed());
            return null;
        }
        challenges.remove(token);
        auth.recordSuccess(ip);
        auth.recordSuccess(userKey);
        return new Login(auth.touch(current, ip), pending.action(), pending.how());
    }

    synchronized boolean verify(String username, String code) throws SQLException {
        String name = username.toLowerCase(Locale.ROOT);
        Factor factor = factors.get(name);
        if (factor == null || code == null) return false;
        long step;
        try { step = Totp.matchingStep(decrypt(factor.cipher()), code, System.currentTimeMillis(), factor.lastStep()); }
        catch (Exception ex) { throw new IllegalStateException("TOTP secret unavailable", ex); }
        if (step >= 0) {
            Factor next = new Factor(factor.cipher(), step, factor.recoveryHashes());
            persist(name, next); factors.put(name, next); return true;
        }
        String normalized = code.replace("-", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-7]{13}")) return false;
        String hash = recoveryHash(normalized);
        if (!factor.recoveryHashes().contains(hash)) return false;
        List<String> remaining = new ArrayList<>(factor.recoveryHashes());
        remaining.remove(hash);
        Factor next = new Factor(factor.cipher(), factor.lastStep(), List.copyOf(remaining));
        persist(name, next); factors.put(name, next); return true;
    }

    synchronized void remove(String username) throws SQLException {
        String name = username.toLowerCase(Locale.ROOT);
        db.update("DELETE FROM " + table + " WHERE username = ?", name);
        factors.remove(name); setups.remove(name);
        challenges.values().removeIf(c -> c.username().equals(name));
    }

    private void persist(String username, Factor factor) throws SQLException {
        db.update(db.upsert(table, List.of("username"), List.of("username", "secret_cipher", "last_step", "recovery_hashes")),
                username, factor.cipher(), factor.lastStep(), Json.GSON.toJson(factor.recoveryHashes()));
    }

    private String recoveryHash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key);
            return Base64.getEncoder().encodeToString(digest.digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String encrypt(String secret) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] payload = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            byte[] output = new byte[nonce.length + payload.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length);
            System.arraycopy(payload, 0, output, nonce.length, payload.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception ex) { throw new IllegalStateException("TOTP encryption unavailable", ex); }
    }

    private String decrypt(String encrypted) throws Exception {
        byte[] input = Base64.getDecoder().decode(encrypted);
        if (input.length < 29) throw new IllegalArgumentException("Invalid TOTP state");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, input, 0, 12));
        return new String(cipher.doFinal(input, 12, input.length - 12), StandardCharsets.US_ASCII);
    }

    private void trim() {
        long now = System.currentTimeMillis();
        setups.values().removeIf(s -> s.expiresAt() < now);
        challenges.values().removeIf(c -> c.expiresAt() < now);
    }
}
