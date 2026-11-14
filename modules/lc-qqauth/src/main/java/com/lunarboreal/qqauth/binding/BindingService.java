package com.lunarboreal.qqauth.binding;

import com.lunarboreal.qqauth.auth.AuthService;

import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Domain operations for binding, self-unbinding, and generation-guarded password recovery. */
public final class BindingService {
    @FunctionalInterface public interface ResetHandler {
        boolean reset(String playerName, String expectedGeneration, AuthService.PreparedPassword prepared) throws SQLException;
    }
    public enum Status { SUCCESS, INVALID_CODE, ALREADY_BOUND, NOT_BOUND, LIMIT_REACHED, IDENTITY_CONFLICT, QQ_MISMATCH, STALE_GENERATION }
    public record IssuedCode(String code, long expiresAt) {}
    public record Result(Status status, Binding binding, UUID previousUuid) {
        public Result(Status status, Binding binding) { this(status, binding, null); }
    }
    private record Pending(UUID uuid, String name, long expiresAt) {}
    private record Recovery(UUID uuid, String name, Binding original, long expiresAt) {}
    private record Unbind(UUID uuid, String name, String qq, long expiresAt) {}
    private record Reset(UUID uuid, String name, String qq, String generation, AuthService.PreparedPassword prepared, long expiresAt) {}

    private final BindingRepository repository;
    private final Supplier<BindingSettings> settings;
    private final ResetHandler resetHandler;
    private final SecureRandom random;
    private final Clock clock;
    private final Map<String, Pending> binds = new HashMap<>();
    private final Map<UUID, String> bindByUuid = new HashMap<>();
    private final Map<String, Recovery> recoveries = new HashMap<>();
    private final Map<UUID, String> recoveryByUuid = new HashMap<>();
    private final Map<String, Unbind> unbinds = new HashMap<>();
    private final Map<String, Reset> resets = new HashMap<>();

    public BindingService(BindingRepository repository, Supplier<BindingSettings> settings, ResetHandler resetHandler) {
        this(repository, settings, resetHandler, new SecureRandom(), Clock.systemUTC());
    }
    BindingService(BindingRepository repository, Supplier<BindingSettings> settings, ResetHandler resetHandler, SecureRandom random, Clock clock) {
        this.repository = Objects.requireNonNull(repository); this.settings = Objects.requireNonNull(settings);
        this.resetHandler = Objects.requireNonNull(resetHandler); this.random = Objects.requireNonNull(random); this.clock = Objects.requireNonNull(clock);
    }

    public synchronized IssuedCode issueBind(UUID uuid, String playerName) {
        validateName(playerName); sweepExpired(); BindingSettings s = settings.get();
        String old = bindByUuid.get(uuid);
        if (old != null) {
            Pending pending = binds.get(old);
            if (pending != null) { Pending refresh = new Pending(uuid, playerName, expiry(s.bindTtl())); binds.put(old, refresh); return new IssuedCode(old, refresh.expiresAt); }
        }
        ensureCapacity();
        String code = newCode(s.codeLength(), binds, recoveries, unbinds, resets);
        long until = expiry(s.bindTtl()); binds.put(code, new Pending(uuid, playerName, until)); bindByUuid.put(uuid, code);
        return new IssuedCode(code, until);
    }

    public synchronized IssuedCode issueIdentityRecovery(UUID uuid, String playerName) throws SQLException {
        Objects.requireNonNull(uuid, "uuid"); validateName(playerName); sweepExpired();
        Binding original = repository.findByName(playerName).orElseThrow(() -> new IllegalStateException("player is not bound"));
        if (original.uuid().equals(uuid) || repository.find(uuid).isPresent()) throw new IllegalStateException("target UUID is already bound");
        BindingSettings s = settings.get();
        String old = recoveryByUuid.get(uuid);
        if (old != null) {
            Recovery pending = recoveries.get(old);
            if (pending != null && original.equals(pending.original) && playerName.equals(pending.name)) {
                long until = expiry(s.bindTtl());
                recoveries.put(old, new Recovery(uuid, playerName, original, until));
                return new IssuedCode(old, until);
            }
            removeRecovery(old);
        }
        ensureCapacity();
        String code = newCode(s.codeLength(), binds, recoveries, unbinds, resets);
        long until = expiry(s.bindTtl());
        recoveries.put(code, new Recovery(uuid, playerName, original, until)); recoveryByUuid.put(uuid, code);
        return new IssuedCode(code, until);
    }

    public synchronized Result redeemBind(String code, String group, String sender) throws SQLException {
        sweepExpired();
        Recovery recovery = recoveries.get(code);
        if (recovery != null) return redeemIdentityRecovery(code, recovery, group, sender);
        Pending pending = binds.get(code); if (pending == null) return result(Status.INVALID_CODE);
        BindingSettings s = settings.get();
        OptionalBinding existing = findPendingIdentity(pending);
        if (existing.bound) { removeBind(code); return result(Status.ALREADY_BOUND); }
        if (existing.nameConflict) { removeBind(code); return result(Status.IDENTITY_CONFLICT); }
        Binding binding = new Binding(pending.uuid, pending.name, sender, group, clock.millis());
        boolean stored = repository.bind(binding, s.quotaFor(sender));
        if (!stored) {
            if (repository.find(pending.uuid).isPresent() || repository.findByName(pending.name).isPresent()) { removeBind(code); return result(Status.IDENTITY_CONFLICT); }
            return result(Status.LIMIT_REACHED);
        }
        removeBind(code); return new Result(Status.SUCCESS, binding);
    }

    private Result redeemIdentityRecovery(String code, Recovery pending, String group, String sender) throws SQLException {
        Binding original = pending.original;
        if (!original.qq().equals(sender) || !original.group().equals(group)) return result(Status.QQ_MISMATCH);
        if (!repository.migrateIdentity(original, pending.uuid, pending.name)) {
            removeRecovery(code); return result(Status.IDENTITY_CONFLICT);
        }
        Binding migrated = new Binding(pending.uuid, pending.name, original.qq(), original.group(), original.boundAt());
        cancel(original.uuid()); cancel(pending.uuid);
        return new Result(Status.SUCCESS, migrated, original.uuid());
    }

    private record OptionalBinding(boolean bound, boolean nameConflict) {}
    private OptionalBinding findPendingIdentity(Pending pending) throws SQLException {
        if (repository.find(pending.uuid).isPresent()) return new OptionalBinding(true, false);
        return new OptionalBinding(false, repository.findByName(pending.name).isPresent());
    }

    public synchronized IssuedCode issueUnbind(UUID uuid) throws SQLException {
        sweepExpired(); Binding binding = repository.find(uuid).orElseThrow(() -> new IllegalStateException("player is not bound"));
        ensureCapacity(); BindingSettings s = settings.get();
        String code = newCode(s.codeLength(), binds, recoveries, unbinds, resets); long until = expiry(s.unbindTtl());
        unbinds.put(code, new Unbind(uuid, binding.playerName(), binding.qq(), until)); return new IssuedCode(code, until);
    }
    public synchronized Result redeemUnbind(String code, String group, String sender) throws SQLException {
        sweepExpired(); Unbind pending = unbinds.get(code); if (pending == null) return result(Status.INVALID_CODE);
        Binding current = repository.find(pending.uuid).orElse(null);
        if (current == null) { unbinds.remove(code); return result(Status.NOT_BOUND); }
        if (!pending.qq.equals(current.qq()) || !current.qq().equals(sender) || !current.group().equals(group)) return result(Status.QQ_MISMATCH);
        if (!repository.unbind(current)) return result(Status.NOT_BOUND);
        cancel(pending.uuid); return new Result(Status.SUCCESS, current);
    }

    public synchronized IssuedCode issueReset(UUID uuid, AuthService.PreparedPassword prepared, String generation) throws SQLException {
        Objects.requireNonNull(prepared, "prepared"); if (generation == null || generation.isBlank()) throw new IllegalArgumentException("generation");
        sweepExpired(); Binding binding = repository.find(uuid).orElseThrow(() -> new IllegalStateException("player is not bound"));
        ensureCapacity(); BindingSettings s = settings.get(); String code = newCode(s.codeLength(), binds, recoveries, unbinds, resets); long until = expiry(s.resetTtl());
        resets.put(code, new Reset(uuid, binding.playerName(), binding.qq(), generation, prepared, until)); return new IssuedCode(code, until);
    }
    public synchronized Result redeemReset(String code, String group, String sender) throws SQLException {
        sweepExpired(); Reset pending = resets.get(code); if (pending == null) return result(Status.INVALID_CODE);
        Binding current = repository.find(pending.uuid).orElse(null);
        if (current == null) { resets.remove(code); return result(Status.NOT_BOUND); }
        if (!pending.qq.equals(current.qq()) || !current.qq().equals(sender) || !current.group().equals(group)) return result(Status.QQ_MISMATCH);
        boolean done = resetHandler.reset(current.playerName(), pending.generation, pending.prepared);
        resets.values().removeIf(p -> p.uuid.equals(pending.uuid));
        return new Result(done ? Status.SUCCESS : Status.STALE_GENERATION, current);
    }

    public synchronized void cancel(UUID uuid) {
        String bind = bindByUuid.remove(uuid); if (bind != null) binds.remove(bind);
        for (Iterator<Map.Entry<String, Recovery>> it = recoveries.entrySet().iterator(); it.hasNext();) {
            var e = it.next(); Recovery recovery = e.getValue();
            if (recovery.uuid.equals(uuid) || recovery.original.uuid().equals(uuid)) {
                recoveryByUuid.remove(recovery.uuid, e.getKey()); it.remove();
            }
        }
        unbinds.values().removeIf(p -> p.uuid.equals(uuid)); resets.values().removeIf(p -> p.uuid.equals(uuid));
    }
    public synchronized void clear() { binds.clear(); bindByUuid.clear(); recoveries.clear(); recoveryByUuid.clear(); unbinds.clear(); resets.clear(); }
    public synchronized int sweepExpired() {
        long now = clock.millis(); int removed = 0;
        for (Iterator<Map.Entry<String, Pending>> it = binds.entrySet().iterator(); it.hasNext();) {
            var e = it.next(); if (e.getValue().expiresAt <= now) { bindByUuid.remove(e.getValue().uuid, e.getKey()); it.remove(); removed++; }
        }
        for (Iterator<Map.Entry<String, Recovery>> it = recoveries.entrySet().iterator(); it.hasNext();) {
            var e = it.next(); if (e.getValue().expiresAt <= now) { recoveryByUuid.remove(e.getValue().uuid, e.getKey()); it.remove(); removed++; }
        }
        removed += removeExpired(unbinds, now); removed += removeExpired(resets, now); return removed;
    }
    private int removeExpired(Map<String, ?> map, long now) {
        int before = map.size();
        map.values().removeIf(p -> p instanceof Unbind u ? u.expiresAt <= now : ((Reset) p).expiresAt <= now);
        return before - map.size();
    }

    public synchronized java.util.Optional<Binding> find(UUID uuid) throws SQLException { return repository.find(uuid); }
    public synchronized java.util.Optional<Binding> findByName(String name) throws SQLException { return repository.findByName(name); }
    public synchronized void updateName(UUID uuid, String name) throws SQLException { validateName(name); repository.updateName(uuid, name); }
    public synchronized AuthService.Result login(AuthService auth, UUID uuid, String name, String ip, String password) throws SQLException {
        var previous = auth.account(name).orElse(null);
        var result = auth.login(uuid, name, ip, password,
                (connection, playerName, oldUuid, newUuid) -> repository.migrateUuid(connection, playerName, oldUuid, newUuid));
        if (result == AuthService.Result.SUCCESS && previous != null && !previous.uuid().equals(uuid)) {
            cancel(previous.uuid());
            cancel(uuid);
        }
        return result;
    }
    public synchronized Result forceUnbind(String name) throws SQLException {
        Binding binding = repository.findByName(name).orElse(null);
        if (binding == null) return result(Status.NOT_BOUND);
        repository.unbind(binding.uuid()); cancel(binding.uuid()); return new Result(Status.SUCCESS, binding);
    }
    public BindingRepository repository() { return repository; }
    private void removeBind(String code) { Pending p = binds.remove(code); if (p != null) bindByUuid.remove(p.uuid, code); }
    private void removeRecovery(String code) { Recovery p = recoveries.remove(code); if (p != null) recoveryByUuid.remove(p.uuid, code); }
    private void ensureCapacity() { sweepExpired(); if (binds.size() + recoveries.size() + unbinds.size() + resets.size() >= settings.get().maxPending()) throw new IllegalStateException("pending code capacity reached"); }
    private long expiry(java.time.Duration ttl) { return Math.addExact(clock.millis(), ttl.toMillis()); }
    @SafeVarargs
    private final String newCode(int length, Map<String, ?>... maps) {
        int bound = 1; for (int i = 0; i < length; i++) bound *= 10;
        for (int attempt = 0; attempt < 128; attempt++) {
            String code = String.format(Locale.ROOT, "%0" + length + "d", random.nextInt(bound));
            boolean used = false; for (Map<String, ?> map : maps) used |= map.containsKey(code);
            if (!used) return code;
        }
        throw new IllegalStateException("unable to allocate a unique verification code");
    }
    private static Result result(Status status) { return new Result(status, null); }
    private static void validateName(String name) { if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("invalid player name"); }
}
