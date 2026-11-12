package com.lunarboreal.essentials.feature;

import com.lunarboreal.essentials.EssentialsModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AFK：活动计时、自动 AFK、AFK 踢出、状态广播。 */
public final class AfkManager {

    private final EssentialsModule m;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    /** AFK 玩家 → 进入 AFK 的时间 */
    private final Map<UUID, Long> afk = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AfkManager(EssentialsModule m) {
        this.m = m;
    }

    public void start() {
        task = m.runTaskTimer(this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (UUID u : afk.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && m.config().getBoolean("afk.ignore-sleep", true)) p.setSleepingIgnored(false);
        }
        afk.clear();
        lastActivity.clear();
    }

    public boolean isAfk(UUID u) {
        return afk.containsKey(u);
    }

    public long afkSince(UUID u) {
        Long v = afk.get(u);
        return v == null ? 0L : v;
    }

    public int afkCount() {
        return afk.size();
    }

    public long idleMillis(UUID u) {
        Long last = lastActivity.get(u);
        return last == null ? 0 : System.currentTimeMillis() - last;
    }

    /** 记录活动；若处于 AFK 则解除。 */
    public void activity(Player p) {
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
        if (afk.containsKey(p.getUniqueId())) setAfk(p, false, true);
    }

    /** 只刷新时间，不解除 AFK。 */
    public void touch(Player p) {
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
    }

    public void quit(Player p) {
        lastActivity.remove(p.getUniqueId());
        afk.remove(p.getUniqueId());
    }

    public void setAfk(Player p, boolean value, boolean broadcast) {
        UUID u = p.getUniqueId();
        if (value == afk.containsKey(u)) return;
        if (value) {
            afk.put(u, System.currentTimeMillis());
        } else {
            afk.remove(u);
            lastActivity.put(u, System.currentTimeMillis());
        }
        if (m.config().getBoolean("afk.ignore-sleep", true)) p.setSleepingIgnored(value);
        if (broadcast && m.config().getBoolean("afk.broadcast", true) && !m.vanish().isVanished(u)) {
            String key = value ? "afk.broadcast-on" : "afk.broadcast-off";
            for (Player o : Bukkit.getOnlinePlayers()) m.msg(o, key, "player", p.getName());
            m.msg(Bukkit.getConsoleSender(), key, "player", p.getName());
        } else {
            m.msg(p, value ? "afk.self-on" : "afk.self-off");
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long auto = m.config().getLong("afk.auto-afk-seconds", 300) * 1000L;
        long kick = m.config().getLong("afk.kick-seconds", 0) * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            long last = lastActivity.computeIfAbsent(u, k -> now);
            long idle = now - last;
            if (!afk.containsKey(u) && auto > 0 && idle >= auto && !p.hasPermission("lc.essentials.afk.exempt")) {
                setAfk(p, true, true);
            }
            if (kick > 0 && idle >= kick && !p.hasPermission("lc.essentials.afk.kickexempt")) {
                p.kick(m.api().messages().component("essentials.afk.kick"));
                m.audit("system", "afk-kick", p.getName());
            }
        }
    }
}
