package com.lunarboreal.teleport;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.service.EssentialsService;
import com.lunarboreal.api.service.LandService;
import com.lunarboreal.teleport.command.AdminCommands;
import com.lunarboreal.teleport.command.HomeCommands;
import com.lunarboreal.teleport.command.SpawnCommands;
import com.lunarboreal.teleport.command.TpaCommands;
import com.lunarboreal.teleport.command.WarpCommands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

/** 传送模块入口。 */
public final class TeleportModule extends LbrModule {

    private TeleportManager teleports;
    private TpaManager tpa;
    private HomeManager homes;
    private WarpManager warps;
    private SpawnManager spawns;
    private RtpManager rtp;
    private volatile boolean shutdown;

    @Override
    public void onEnable() {
        reloadConfig();
        teleports = new TeleportManager(this);
        tpa = new TpaManager(this);
        homes = new HomeManager(this);
        warps = new WarpManager(this);
        spawns = new SpawnManager(this);
        rtp = new RtpManager(this);

        spawns.load();
        // 异步建表，完成后回主线程再加载数据（避免远程 MySQL 阻塞主线程）
        runAsync(() -> {
            try {
                homes.createTable();
                warps.createTable();
                teleports.createBackTable();
                db().update("CREATE TABLE IF NOT EXISTS " + db().table("teleport_toggles") + " (uuid VARCHAR(36) NOT NULL PRIMARY KEY)");
            } catch (Exception e) {
                logger().severe("创建传送数据表失败: " + e.getMessage());
            }
            if (shutdown) return;
            runTask(() -> {
                if (shutdown) return;
                warps.loadAll();
                tpa.loadToggles();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    homes.load(p.getUniqueId());
                    teleports.loadBack(p.getUniqueId());
                }
            });
        });

        declarePermissions();

        TpaCommands.register(this);
        AdminCommands.register(this);
        HomeCommands.register(this);
        WarpCommands.register(this);
        SpawnCommands.register(this);

        registerListener(new TeleportListener(this));
        registerListener(new WarpGui.GuiListener(this));
        runTaskTimer(tpa::tick, 20L, 20L);

        placeholder((player, key) -> switch (key) {
            case "homes" -> player == null ? "0" : String.valueOf(homes.cached(player.getUniqueId()).size());
            case "homes_max" -> {
                if (player == null || player.getPlayer() == null) yield String.valueOf(config().getInt("homes.default-limit", 3));
                int lim = homes.limit(player.getPlayer());
                yield lim < 0 ? "∞" : String.valueOf(lim);
            }
            case "warps" -> String.valueOf(warps.count());
            default -> null;
        });

        new WebSetup(this).register();
    }

    private void declarePermissions() {
        defaultPermissions(
                "lbr.teleport.tpa", "lbr.teleport.tpahere", "lbr.teleport.tpaccept", "lbr.teleport.tpdeny",
                "lbr.teleport.tpacancel", "lbr.teleport.tptoggle",
                "lbr.teleport.home", "lbr.teleport.sethome", "lbr.teleport.delhome", "lbr.teleport.homes",
                "lbr.teleport.home.bed",
                "lbr.teleport.warp", "lbr.teleport.warps",
                "lbr.teleport.spawn", "lbr.teleport.back", "lbr.teleport.back.ondeath", "lbr.teleport.rtp");
    }

    @Override
    public void onDisable() {
        shutdown = true;
        try {
            WarpGui.closeAll();
        } catch (Throwable ignored) {
        }
        if (teleports != null) teleports.shutdown();
        if (tpa != null) tpa.clear();
        if (homes != null) homes.clear();
    }

    @Override
    public void onReload() {
        reloadConfig();
        spawns.load();
        warps.loadAll();
    }

    // ---------------- 访问器 ----------------

    public Database db() {
        return api().database();
    }

    public TeleportManager teleports() { return teleports; }

    public TpaManager tpa() { return tpa; }

    public HomeManager homes() { return homes; }

    public WarpManager warps() { return warps; }

    public SpawnManager spawns() { return spawns; }

    public RtpManager rtp() { return rtp; }

    public Optional<EconomyService> economy() {
        try {
            return api().services().get(EconomyService.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public Optional<LandService> land() {
        try {
            return api().services().get(LandService.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public Optional<EssentialsService> essentials() {
        try {
            return api().services().get(EssentialsService.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** viewer 能否看见 target（隐身玩家对无权限者视为不存在）。 */
    public boolean canSee(CommandSender viewer, Player target) {
        if (viewer instanceof Player vp && vp.getUniqueId().equals(target.getUniqueId())) return true;
        Optional<EssentialsService> ess = essentials();
        if (ess.isPresent()) {
            try {
                return ess.get().canSee(viewer, target);
            } catch (Throwable ignored) {
            }
        }
        return !(viewer instanceof Player vp) || vp.canSee(target);
    }

    /** 查找在线玩家（精确名优先，其次前缀），找不到或不可见时提示并返回 null。 */
    public Player findPlayer(CommandSender viewer, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getPlayer(name);
        if (p == null || !canSee(viewer, p)) {
            msg(viewer, "common.player-not-found", "player", name);
            return null;
        }
        return p;
    }

    public String actor(CommandSender s) {
        return s instanceof Player p ? p.getName() : "CONSOLE";
    }
}
