package com.lunarboreal.essentials.feature;

import com.lunarboreal.essentials.EssentialsModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 隐身：对无 lbr.essentials.vanish.see 权限的玩家隐藏（同时从在线列表隐藏）。 */
public final class VanishManager {

    public static final String SEE = "lbr.essentials.vanish.see";
    private final EssentialsModule m;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public VanishManager(EssentialsModule m) {
        this.m = m;
    }

    public boolean isVanished(UUID u) {
        return vanished.contains(u);
    }

    public int count() {
        return vanished.size();
    }

    public Set<UUID> all() {
        return vanished;
    }

    public boolean canSee(CommandSender viewer, Player target) {
        if (!vanished.contains(target.getUniqueId())) return true;
        if (viewer == null) return false;
        if (!(viewer instanceof Player vp)) return true;
        return vp.equals(target) || vp.hasPermission(SEE);
    }

    @SuppressWarnings("deprecation")
    public void setVanished(Player p, boolean value, boolean announce) {
        UUID u = p.getUniqueId();
        var d = m.data(p);
        if (d.vanish != value) {
            d.vanish = value;
            d.markDirty();
        }
        if (value) {
            vanished.add(u);
            p.setMetadata("vanished", new FixedMetadataValue(m.plugin(), true));
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p) && !o.hasPermission(SEE)) o.hidePlayer(m.plugin(), p);
            }
        } else {
            vanished.remove(u);
            p.removeMetadata("vanished", m.plugin());
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) o.showPlayer(m.plugin(), p);
            }
        }
        if (announce && m.config().getBoolean("vanish.fake-join-quit", false)) {
            String key = value ? "vanish.fake-quit" : "vanish.fake-join";
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.hasPermission(SEE)) m.msg(o, key, "player", p.getName());
            }
        }
    }

    /** 新加入的玩家：隐藏所有隐身者。 */
    public void hideVanishedFrom(Player joiner) {
        if (joiner.hasPermission(SEE)) return;
        for (UUID u : vanished) {
            Player v = Bukkit.getPlayer(u);
            if (v != null && !v.equals(joiner)) joiner.hidePlayer(m.plugin(), v);
        }
    }

    public void quit(Player p) {
        vanished.remove(p.getUniqueId());
    }

    /** 模块禁用：恢复可见（状态已持久化，重新启用后再隐藏）。 */
    public void showAllOnDisable() {
        for (UUID u : vanished) {
            Player v = Bukkit.getPlayer(u);
            if (v == null) continue;
            v.removeMetadata("vanished", m.plugin());
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(v)) o.showPlayer(m.plugin(), v);
            }
        }
        vanished.clear();
    }
}
