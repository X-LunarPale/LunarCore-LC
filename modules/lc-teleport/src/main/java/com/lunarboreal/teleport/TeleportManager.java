package com.lunarboreal.teleport;

import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.service.LandService;
import com.lunarboreal.api.service.SoundService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用传送管理：预热、冷却、费用、安全落点、领地检查、异步区块加载、/back 记录。
 * 除特别说明外所有方法在主线程调用。
 */
public final class TeleportManager {

    public static final String PERM_BYPASS_WARMUP = "lc.teleport.bypass.warmup";
    public static final String PERM_BYPASS_COOLDOWN = "lc.teleport.bypass.cooldown";
    public static final String PERM_BYPASS_COST = "lc.teleport.bypass.cost";
    public static final String PERM_BYPASS_LAND = "lc.teleport.bypass.land";

    private final TeleportModule m;
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, StoredLoc> backLocations = new ConcurrentHashMap<>();

    public TeleportManager(TeleportModule m) {
        this.m = m;
    }

    // =====================================================================
    // 请求

    /** 一次传送请求（链式构建）。 */
    public static final class Request {
        final Player player;
        final String key;
        Supplier<CompletableFuture<Location>> target;
        Player payer;
        BigDecimal cost;
        boolean warmup = true, cooldown = true, safe = true, land = true, charge = true, recordBack = true;
        String successKey = "teleport.success";
        Object[] successArgs = new Object[0];
        String targetFailKey = "teleport.target-unavailable";
        Consumer<Location> onSuccess;

        private Request(Player player, String key) {
            this.player = player;
            this.key = key;
        }

        public Request to(Location loc) {
            Location c = loc == null ? null : loc.clone();
            this.target = () -> CompletableFuture.completedFuture(c);
            return this;
        }

        /** 执行时（预热结束后）才解析目标，如 tpa 目标玩家的当前位置。 */
        public Request to(Supplier<Location> supplier) {
            this.target = () -> CompletableFuture.completedFuture(supplier.get());
            return this;
        }

        public Request toAsync(Supplier<CompletableFuture<Location>> supplier) {
            this.target = supplier;
            return this;
        }

        public Request payer(Player p) { this.payer = p; return this; }

        public Request cost(BigDecimal c) { this.cost = c; return this; }

        public Request warmup(boolean b) { this.warmup = b; return this; }

        public Request cooldown(boolean b) { this.cooldown = b; return this; }

        public Request safe(boolean b) { this.safe = b; return this; }

        public Request land(boolean b) { this.land = b; return this; }

        public Request charge(boolean b) { this.charge = b; return this; }

        public Request recordBack(boolean b) { this.recordBack = b; return this; }

        public Request success(String key, Object... args) { this.successKey = key; this.successArgs = args; return this; }

        public Request targetFail(String key) { this.targetFailKey = key; return this; }

        public Request onSuccess(Consumer<Location> c) { this.onSuccess = c; return this; }

        /** 管理传送：无预热/冷却/费用/领地检查，不做安全检查。 */
        public Request admin() {
            warmup = false; cooldown = false; charge = false; land = false; safe = false;
            return this;
        }
    }

    public Request request(Player player, String key) {
        return new Request(player, key);
    }

    private static final class Warmup {
        final Request request;
        final Location start;
        BukkitTask task;

        Warmup(Request request, Location start) {
            this.request = request;
            this.start = start;
        }
    }

    // =====================================================================

    /** 提交请求：检查冷却与余额，然后预热或立即执行。 */
    public void submit(Request r) {
        Player p = r.player;
        if (r.cooldown) {
            long left = cooldownLeft(p, r.key);
            if (left > 0) {
                m.msg(p, "teleport.cooldown", "seconds", (left + 999) / 1000);
                return;
            }
        }
        Player payer = r.payer != null ? r.payer : p;
        BigDecimal cost = effectiveCost(r, payer);
        if (cost.signum() > 0) {
            Optional<EconomyService> eco = m.economy();
            if (eco.isPresent() && !eco.get().has(payer.getUniqueId(), cost)) {
                m.msg(payer, "teleport.no-money", "cost", eco.get().format(cost));
                if (payer != p) m.msg(p, "teleport.payer-no-money", "player", payer.getName());
                return;
            }
        }
        cancelWarmup(p, false);
        int seconds = r.warmup && !p.hasPermission(PERM_BYPASS_WARMUP) ? warmupSeconds(r.key) : 0;
        if (seconds <= 0) {
            execute(r);
            return;
        }
        Warmup w = new Warmup(r, p.getLocation());
        w.task = m.runTaskLater(() -> {
            if (warmups.get(p.getUniqueId()) != w) return;
            warmups.remove(p.getUniqueId());
            execute(r);
        }, seconds * 20L);
        warmups.put(p.getUniqueId(), w);
        String hint = "";
        if (m.config().getBoolean("teleport.cancel-on-move", true)) hint = m.raw("teleport.warmup-hint");
        m.msg(p, "teleport.warmup", "seconds", seconds, "hint", com.lunarboreal.api.message.MessageService.rich(hint));
        playSound(p, "teleport-warmup");
    }

    public boolean hasWarmup(UUID uuid) {
        return warmups.containsKey(uuid);
    }

    /** 由监听器调用：移动出起始方块。 */
    public void checkMove(Player p, Location to) {
        Warmup w = warmups.get(p.getUniqueId());
        if (w == null || to == null || !m.config().getBoolean("teleport.cancel-on-move", true)) return;
        Location s = w.start;
        if (s.getWorld() != to.getWorld() || s.getBlockX() != to.getBlockX() || s.getBlockY() != to.getBlockY()
                || s.getBlockZ() != to.getBlockZ()) {
            cancelWarmup(p, true);
        }
    }

    public void onDamage(Player p) {
        if (warmups.containsKey(p.getUniqueId()) && m.config().getBoolean("teleport.cancel-on-damage", true)) {
            cancelWarmup(p, true);
        }
    }

    public void cancelWarmup(Player p, boolean notify) {
        Warmup w = warmups.remove(p.getUniqueId());
        if (w == null) return;
        if (w.task != null) w.task.cancel();
        if (notify) {
            m.msg(p, "teleport.warmup-cancelled");
            playSound(p, "teleport-cancel");
        }
    }

    public void forget(UUID uuid) {
        Warmup w = warmups.remove(uuid);
        if (w != null && w.task != null) w.task.cancel();
    }

    // =====================================================================

    private void execute(Request r) {
        Player p = r.player;
        if (!p.isOnline()) return;
        CompletableFuture<Location> f;
        try {
            f = r.target.get();
        } catch (Throwable t) {
            f = CompletableFuture.failedFuture(t);
        }
        f.whenComplete((loc, err) -> sync(() -> {
            if (!p.isOnline()) return;
            if (err != null || loc == null || loc.getWorld() == null) {
                if (err != null) m.logger().fine("解析传送目标失败: " + err);
                m.msg(p, r.targetFailKey);
                playSound(p, "teleport-fail");
                return;
            }
            prepareChunk(loc).whenComplete((c, e2) -> sync(() -> finish(r, loc)));
        }));
    }

    private void finish(Request r, Location loc) {
        Player p = r.player;
        if (!p.isOnline()) return;
        Location dest = loc;
        if (r.safe && m.config().getBoolean("teleport.safe-location", true) && needsSafety(p)) {
            dest = SafeLocation.find(loc, m.config().getInt("teleport.safe-search-radius", 3));
            if (dest == null) {
                m.msg(p, "teleport.unsafe");
                playSound(p, "teleport-fail");
                return;
            }
        }
        if (r.land && !landAllowed(p, dest)) {
            m.msg(p, "teleport.land-denied");
            playSound(p, "teleport-fail");
            return;
        }
        Player payer = r.payer != null && r.payer.isOnline() ? r.payer : p;
        BigDecimal cost = effectiveCost(r, payer);
        EconomyService eco = cost.signum() > 0 ? m.economy().orElse(null) : null;
        // 预热结束、真正传送前扣费；扣费失败取消传送，传送失败退款
        if (eco != null) {
            EconomyService.Result res = eco.withdraw(payer.getUniqueId(), cost, "传送: " + r.key);
            if (!res.success()) {
                m.msg(payer, "teleport.no-money", "cost", eco.format(cost));
                if (payer != p) m.msg(p, "teleport.payer-no-money", "player", payer.getName());
                return;
            }
            m.msg(payer, "teleport.charged", "cost", eco.format(cost));
        }
        Location from = p.getLocation();
        Location finalDest = dest;
        CompletableFuture<Boolean> tf;
        try {
            tf = doTeleport(p, dest);
        } catch (Throwable t) {
            tf = CompletableFuture.failedFuture(t);
        }
        tf.whenComplete((ok, err) -> sync(() -> {
            if (Boolean.TRUE.equals(ok) && err == null) {
                if (r.recordBack) setBack(p.getUniqueId(), from);
                if (r.cooldown) setCooldown(p, r.key);
                if (r.successKey != null) m.msg(p, r.successKey, r.successArgs);
                playSound(p, "teleport-success");
                if (r.onSuccess != null) r.onSuccess.accept(finalDest);
            } else {
                if (eco != null) eco.deposit(payer.getUniqueId(), cost, "传送失败退款");
                m.msg(p, "teleport.failed");
                playSound(p, "teleport-fail");
            }
        }));
    }

    private void playSound(Player player, String event) {
        m.api().services().get(SoundService.class).ifPresent(sounds -> sounds.play(player, event));
    }

    /** 直接传送（不经过任何检查），返回是否成功。 */
    public CompletableFuture<Boolean> doTeleport(Player p, Location dest) {
        if (p.isInsideVehicle()) p.leaveVehicle();
        if (m.api().isPaper() && m.config().getBoolean("teleport.use-async", true)) {
            try {
                return p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.COMMAND);
            } catch (NoSuchMethodError ignored) {
                // 非 Paper 运行时回退
            }
        }
        return CompletableFuture.completedFuture(p.teleport(dest, PlayerTeleportEvent.TeleportCause.COMMAND));
    }

    /** 确保目标区块已加载：Paper 异步，否则同步加载。 */
    public CompletableFuture<?> prepareChunk(Location loc) {
        if (loc.getWorld() == null) return CompletableFuture.completedFuture(null);
        if (m.api().isPaper()) {
            try {
                return loc.getWorld().getChunkAtAsync(loc);
            } catch (NoSuchMethodError ignored) {
                // 回退
            }
        }
        loc.getChunk().load();
        return CompletableFuture.completedFuture(null);
    }

    public boolean needsSafety(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        return !(p.getAllowFlight() && p.isFlying());
    }

    public boolean landAllowed(Player p, Location dest) {
        if (!m.config().getBoolean("teleport.check-land", true) || p.hasPermission(PERM_BYPASS_LAND)) return true;
        Optional<LandService> land = m.land();
        if (land.isEmpty()) return true;
        try {
            return land.get().can(p, dest, LandService.Flag.TELEPORT);
        } catch (Throwable t) {
            return true;
        }
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (m.isEnabled()) m.runTask(r);
    }

    // =====================================================================
    // 配置值

    public int warmupSeconds(String key) {
        int def = m.config().getInt("teleport.warmup", 3);
        return Math.max(0, m.config().getInt("warmups." + key, def));
    }

    public BigDecimal configCost(String key) {
        double v = m.config().getDouble("costs." + key, 0);
        return v <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }

    private BigDecimal effectiveCost(Request r, Player payer) {
        if (!r.charge || payer.hasPermission(PERM_BYPASS_COST) || m.economy().isEmpty()) return BigDecimal.ZERO;
        BigDecimal c = r.cost != null ? r.cost : configCost(r.key);
        return c == null || c.signum() <= 0 ? BigDecimal.ZERO : c;
    }

    // =====================================================================
    // 冷却

    public long cooldownLeft(Player p, String key) {
        if (p.hasPermission(PERM_BYPASS_COOLDOWN)) return 0;
        Map<String, Long> map = cooldowns.get(p.getUniqueId());
        if (map == null) return 0;
        Long until = map.get(key);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    public void setCooldown(Player p, String key) {
        int sec = m.config().getInt("cooldowns." + key, 0);
        if (sec <= 0 || p.hasPermission(PERM_BYPASS_COOLDOWN)) return;
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(key, System.currentTimeMillis() + sec * 1000L);
    }

    // =====================================================================
    // /back

    private String backTable() {
        return m.db().table("teleport_back");
    }

    public void createBackTable() throws java.sql.SQLException {
        m.db().update("CREATE TABLE IF NOT EXISTS " + backTable() + " ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                + "updated_at BIGINT NOT NULL)");
    }

    private boolean persistBack() {
        return m.config().getBoolean("back.persist", true);
    }

    /** 玩家加入时从数据库加载 /back 位置（back.persist 开启时）。 */
    public void loadBack(UUID uuid) {
        if (!persistBack()) return;
        m.db().queryAsync("SELECT * FROM " + backTable() + " WHERE uuid = ?", StoredLoc::read, uuid.toString())
                .thenAccept(list -> {
                    if (!list.isEmpty() && Bukkit.getPlayer(uuid) != null) backLocations.putIfAbsent(uuid, list.get(0));
                })
                .exceptionally(e -> { m.logger().warning("加载 back 位置失败: " + e.getMessage()); return null; });
    }

    public void unloadBack(UUID uuid) {
        backLocations.remove(uuid);
    }

    /** 世界未加载时返回 null。 */
    public Location back(UUID uuid) {
        StoredLoc s = backLocations.get(uuid);
        return s == null ? null : s.toLocation();
    }

    public void setBack(UUID uuid, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        StoredLoc s = StoredLoc.of(loc);
        backLocations.put(uuid, s);
        if (!persistBack()) return;
        var db = m.db();
        db.updateAsync(db.upsert(backTable(), List.of("uuid"),
                        List.of("uuid", "world", "x", "y", "z", "yaw", "pitch", "updated_at")),
                uuid.toString(), s.world(), s.x(), s.y(), s.z(), s.yaw(), s.pitch(), System.currentTimeMillis())
                .exceptionally(e -> { m.logger().warning("保存 back 位置失败: " + e.getMessage()); return 0; });
    }

    public void shutdown() {
        for (Warmup w : warmups.values()) if (w.task != null) w.task.cancel();
        warmups.clear();
    }
}
