package com.lunarboreal.signin;

import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.signin.SigninStore.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SigninModule extends LcModule implements Listener {

    private volatile SigninSettings settings;
    private SigninStore store;
    private volatile boolean ready;
    private LocalDate lastDay;

    /** 待写库的签到。 */
    private record Write(UUID uuid, String name, LocalDate date, boolean makeup, int streak, int total, LocalDate last) {}

    private final Queue<Write> writes = new ConcurrentLinkedQueue<>();
    /**
     * 已在内存签到但尚未确认写入数据库的日期（不随玩家退出清除）。
     * 玩家重新加载数据时合并进去，避免“签到 → 秒退 → 秒进”读到旧数据而重复领奖；写库失败时保留，防止无限重签。
     */
    private final Map<UUID, Map<LocalDate, Boolean>> inflight = new ConcurrentHashMap<>();

    public SigninSettings settings() { return settings; }

    public SigninStore store() { return store; }

    @Override
    public void onEnable() {
        loadSettings();
        store = new SigninStore(api().database());
        lastDay = settings.today();

        defaultPermissions("lc.signin.use", "lc.signin.makeup", "lc.signin.top");
        registerPermission("lc.signin.admin", PermissionDefault.OP, "签到管理");

        registerListener(this);
        registerListener(new SigninGui.Listener(this));
        registerCommand(new SigninCommand(this));
        registerPlaceholders();
        new SigninWeb(this).register();

        runAsync(() -> {
            try {
                store.init();
                ready = true;
                runTask(() -> Bukkit.getOnlinePlayers().forEach(p -> loadPlayer(p, false)));
            } catch (Exception e) {
                logger().log(Level.SEVERE, "签到数据表初始化失败", e);
            }
        });
        // 每 30 秒检查是否跨天
        runTaskTimer(this::checkNewDay, 600L, 600L);
        runTaskTimer(() -> { if (!writes.isEmpty()) runAsync(() -> drainWrites(true)); }, 600L, 600L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SigninGui) p.closeInventory();
        }
        // 尚未执行的异步写库任务会随模块禁用被取消，这里同步写回
        drainWrites(false);
        if (store != null) store.clearCache();
    }

    @Override
    public void onReload() {
        reloadConfig();
        loadSettings();
    }

    // ---------------- 配置 ----------------

    public File rewardsFile() {
        return new File(dataFolder(), "rewards.yml");
    }

    public void loadSettings() {
        saveResource("rewards.yml", false);
        YamlConfiguration rewards = YamlConfiguration.loadConfiguration(rewardsFile());
        settings = new SigninSettings(config(), rewards, logger());
    }

    // ---------------- 玩家缓存 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        loadPlayer(e.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        store.unload(e.getPlayer().getUniqueId());
    }

    private void loadPlayer(Player p, boolean remind) {
        withData(p, d -> {
            if (remind && settings.remindJoin && !d.signed(settings.today())) {
                runTaskLater(() -> {
                    if (p.isOnline() && !d.signed(settings.today())) msg(p, "remind.join");
                }, settings.remindDelay * 20L);
            }
        });
    }

    /** 取得玩家缓存（若未加载则异步加载后在主线程回调）。 */
    public void withData(Player p, Consumer<PlayerData> action) {
        PlayerData d = store.get(p.getUniqueId());
        if (d != null) {
            action.accept(d);
            return;
        }
        if (!ready) {
            msg(p, "error.loading");
            return;
        }
        var uuid = p.getUniqueId();
        var name = p.getName();
        runAsync(() -> {
            try {
                PlayerData loaded = store.load(uuid, name);
                runTask(() -> {
                    if (!p.isOnline()) return;
                    // 合并尚未落库的签到（防止签到后秒退秒进时重复领奖）
                    Map<LocalDate, Boolean> pending = inflight.get(uuid);
                    if (pending != null) pending.forEach((date, makeup) -> store.mark(loaded, date, makeup));
                    PlayerData existing = store.get(uuid);
                    PlayerData use = existing != null ? existing : loaded;
                    if (existing == null) store.put(loaded);
                    action.accept(use);
                });
            } catch (Exception e) {
                logger().log(Level.WARNING, "加载签到数据失败: " + name, e);
            }
        });
    }

    private void checkNewDay() {
        LocalDate today = settings.today();
        if (today.equals(lastDay)) return;
        lastDay = today;
        if (!settings.remindNewDay) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = store.get(p.getUniqueId());
            if (d != null && !d.signed(today) && p.hasPermission("lc.signin.use")) msg(p, "remind.new-day");
        }
    }

    // ---------------- 签到 / 补签 ----------------

    /** 主线程：今日签到。返回是否成功。 */
    public boolean signToday(Player p, PlayerData d) {
        LocalDate today = settings.today();
        if (!store.mark(d, today, false)) {
            msg(p, "sign.already", "streak", d.streak(today), "total", d.total());
            return false;
        }
        int streak = d.streak(today);
        int total = d.total();
        persistAsync(d, today, false, streak, total);

        settings.daily.give(this, p, "每日签到");
        msg(p, "sign.success", "streak", streak, "total", total,
                "reward", rich(settings.daily.description()));
        Reward sr = settings.streak.get(settings.streakKey(streak));
        if (sr != null && !sr.isEmpty()) {
            sr.give(this, p, "连续签到 " + streak + " 天");
            msg(p, "sign.streak-reward", "days", streak, "reward", rich(sr.description()));
        }
        checkMilestone(p, total);
        if (settings.broadcast) {
            for (Player o : Bukkit.getOnlinePlayers()) msg(o, "sign.broadcast", "player", p.getName(), "streak", streak);
        }
        return true;
    }

    /** 主线程：补签某日。 */
    public boolean makeup(Player p, PlayerData d, LocalDate date) {
        LocalDate today = settings.today();
        if (!settings.makeupEnabled || !p.hasPermission("lc.signin.makeup")) {
            msg(p, "makeup.disabled");
            return false;
        }
        if (!date.isBefore(today) || !YearMonth.from(date).equals(YearMonth.from(today))) {
            msg(p, "makeup.invalid-date");
            return false;
        }
        if (d.signed(date)) {
            msg(p, "makeup.already");
            return false;
        }
        YearMonth ym = YearMonth.from(today);
        if (d.makeupCount(ym) >= settings.makeupLimit) {
            msg(p, "makeup.limit", "limit", settings.makeupLimit);
            return false;
        }
        BigDecimal cost = settings.makeupCost;
        EconomyService eco = api().services().get(EconomyService.class).orElse(null);
        if (cost.signum() > 0) {
            if (eco == null) {
                msg(p, "makeup.no-economy");
                return false;
            }
            EconomyService.Result r = eco.withdraw(p.getUniqueId(), cost, "补签 " + date);
            if (!r.success()) {
                msg(p, "makeup.no-money", "cost", eco.format(cost), "balance", eco.format(r.balance()));
                return false;
            }
        }
        if (!store.mark(d, date, true)) {
            if (cost.signum() > 0 && eco != null) eco.deposit(p.getUniqueId(), cost, "补签退款 " + date);
            msg(p, "makeup.already");
            return false;
        }
        int streak = d.streak(today);
        int total = d.total();
        persistAsync(d, date, true, streak, total);
        if (settings.makeupDaily) settings.daily.give(this, p, "补签 " + date);
        msg(p, "makeup.success", "date", date.toString(), "cost", eco == null ? cost.toPlainString() : eco.format(cost),
                "streak", streak, "total", total, "left", Math.max(0, settings.makeupLimit - d.makeupCount(ym)));
        if (settings.makeupMilestones) checkMilestone(p, total);
        return true;
    }

    private void checkMilestone(Player p, int total) {
        Reward mr = settings.milestones.get(total);
        if (mr != null && !mr.isEmpty()) {
            mr.give(this, p, "累计签到 " + total + " 天");
            msg(p, "sign.milestone-reward", "days", total, "reward", rich(mr.description()));
        }
    }

    /** 可信 MiniMessage（服主配置的奖励描述）作为富文本占位符。 */
    public static MessageService.Rich rich(String miniMessage) {
        return MessageService.rich(miniMessage == null ? "-" : miniMessage);
    }

    /** 主线程：登记写库任务并标记为“写入中”，随后异步写入。 */
    private void persistAsync(PlayerData d, LocalDate date, boolean makeup, int streak, int total) {
        inflight.computeIfAbsent(d.uuid, k -> new ConcurrentHashMap<>()).put(date, makeup);
        writes.add(new Write(d.uuid, d.name, date, makeup, streak, total, d.last()));
        runAsync(() -> drainWrites(true));
    }

    /**
     * 依次写入队列中的签到。成功后回主线程移除“写入中”标记（排在已排队的加载合并之后，保证不丢失）；
     * 失败时记录日志并保留标记，防止同一次运行中重复签到领奖。
     */
    private synchronized void drainWrites(boolean scheduleCleanup) {
        Write w;
        while ((w = writes.peek()) != null) {
            try {
                store.persist(w.uuid(), w.name(), w.date(), w.makeup(), w.streak(), w.total(), w.last());
                writes.remove(w);
                // Keep the per-run claimed-date guard even after persistence: an older
                // in-flight load may still be queued and must not resurrect an unsigned date.
            } catch (Exception e) {
                logger().log(Level.SEVERE, "签到记录写库失败（保留日期并等待重试）: " + w.name() + " " + w.date(), e);
                break; // Keep failed writes in order; do not retry stale summaries after newer ones.
            }
        }
    }

    // ---------------- 变量 ----------------

    private void registerPlaceholders() {
        placeholder((OfflinePlayer player, String key) -> {
            if (player == null) return null;
            PlayerData d = store.get(player.getUniqueId());
            LocalDate today = settings.today();
            return switch (key) {
                case "today" -> d != null && d.signed(today) ? settings.textSigned : settings.textUnsigned;
                case "today_bool" -> String.valueOf(d != null && d.signed(today));
                case "streak" -> String.valueOf(d == null ? 0 : d.streak(today));
                case "total" -> String.valueOf(d == null ? 0 : d.total());
                case "month" -> String.valueOf(d == null ? 0 : d.monthCount(YearMonth.from(today)));
                case "makeup_left" -> String.valueOf(d == null ? settings.makeupLimit
                        : Math.max(0, settings.makeupLimit - d.makeupCount(YearMonth.from(today))));
                default -> null;
            };
        });
    }

    /** 管理员重置后刷新在线玩家缓存。 */
    public void resetCached(java.util.UUID uuid) {
        inflight.remove(uuid);
        PlayerData d = store.get(uuid);
        if (d != null) d.dates.clear();
    }

    public Map<String, Object> ph(Object... kv) {
        return placeholders(kv);
    }
}
