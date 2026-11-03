package com.lunarboreal.economy;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.economy.command.BaltopCommand;
import com.lunarboreal.economy.command.EcoCommand;
import com.lunarboreal.economy.command.MoneyCommand;
import com.lunarboreal.economy.command.PayCommand;
import com.lunarboreal.economy.web.EconomyWeb;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionDefault;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Level;

public final class EconomyModule extends LbrModule implements Listener {

    private volatile EconomySettings settings;
    private EconomyServiceImpl service;
    /** VaultHook 实例（以 Object 持有，避免未安装 Vault 时加载 Vault 类）。 */
    private Object vaultHook;

    public EconomySettings settings() {
        return settings;
    }

    public EconomyServiceImpl service() {
        return service;
    }

    @Override
    public void onEnable() {
        settings = new EconomySettings(config());
        service = new EconomyServiceImpl(this, api().database());

        // 先注册服务：加载完成前的查询会回退到单条同步读取
        provide(EconomyService.class, service);

        runAsync(() -> {
            try {
                service.init();
            } catch (Exception e) {
                logger().log(Level.SEVERE, "经济数据初始化失败", e);
            }
        });
        scheduleTimers();

        defaultPermissions("lbr.economy.balance", "lbr.economy.pay", "lbr.economy.baltop", "lbr.economy.log");
        registerPermission("lbr.economy.balance.others", PermissionDefault.OP, "查看他人余额");
        registerPermission("lbr.economy.log.others", PermissionDefault.OP, "查看他人流水");
        registerPermission("lbr.economy.admin", PermissionDefault.OP, "经济管理 /eco");

        registerListener(this);
        registerCommand(new MoneyCommand(this));
        registerCommand(new PayCommand(this));
        registerCommand(new BaltopCommand(this));
        registerCommand(new EcoCommand(this));

        registerPlaceholders();
        new EconomyWeb(this).register();
        hookVault();
    }

    private void scheduleTimers() {
        long flushTicks = settings.flushInterval * 20L;
        runAsyncTimer(() -> service.flush(), flushTicks, flushTicks);
        long topTicks = settings.topRefresh * 20L;
        runAsyncTimer(() -> { if (service.isLoaded()) service.refreshTop(); }, topTicks, topTicks);
    }

    @Override
    public void onDisable() {
        unhookVault();
        if (service != null) service.flush();
    }

    @Override
    public void onReload() {
        reloadConfig();
        boolean vaultBefore = settings.vaultEnabled;
        int flushBefore = settings.flushInterval;
        int topBefore = settings.topRefresh;
        settings = new EconomySettings(config());
        if (flushBefore != settings.flushInterval || topBefore != settings.topRefresh) {
            logger().info("刷盘/排行榜间隔已变更，将在模块重新加载（/lc module reload economy）后生效");
        }
        if (vaultBefore != settings.vaultEnabled) {
            if (settings.vaultEnabled) hookVault(); else unhookVault();
        }
        service.refreshTop();
    }

    // ---------------- Vault ----------------

    private void hookVault() {
        if (!settings.vaultEnabled || vaultHook != null) return;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            logger().info("未检测到 Vault，跳过 Vault 桥接");
            return;
        }
        try {
            // 确认 Vault 类对模块类加载器可见（父加载器为 LunarCore 插件类加载器，LunarCore softdepend Vault）
            Class.forName("net.milkbowl.vault.economy.Economy", false, moduleClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            logger().warning("检测到 Vault 但无法访问其类（" + e + "），Vault 桥接未启用");
            return;
        }
        try {
            vaultHook = com.lunarboreal.economy.vault.VaultHook.register(this, service);
            logger().info("已作为 Vault 经济提供者注册（优先级 High）");
        } catch (Throwable t) {
            logger().log(Level.WARNING, "注册 Vault 经济失败", t);
        }
    }

    private void unhookVault() {
        if (vaultHook == null) return;
        try {
            ((com.lunarboreal.economy.vault.VaultHook) vaultHook).unregister();
        } catch (Throwable t) {
            logger().log(Level.WARNING, "注销 Vault 经济失败", t);
        }
        vaultHook = null;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("Vault")) hookVault();
    }

    // ---------------- 账户 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        service.touchName(e.getUniqueId(), e.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        service.touchName(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    // ---------------- 变量 ----------------

    private void registerPlaceholders() {
        placeholder((OfflinePlayer player, String key) -> {
            switch (key) {
                case "balance":
                    return player == null ? null : service.balance(player.getUniqueId()).toPlainString();
                case "balance_formatted":
                    return player == null ? null : service.format(service.balance(player.getUniqueId()));
                case "balance_short":
                    return player == null ? null : settings.number(service.balance(player.getUniqueId()));
                case "rank":
                    if (player == null) return null;
                    int r = service.rank(player.getUniqueId());
                    return r == 0 ? "-" : String.valueOf(r);
                case "currency":
                    return settings.plural;
                case "total":
                    return settings.format(service.totalMoney());
                case "accounts":
                    return String.valueOf(service.accountCount());
                default:
                    break;
            }
            // top_<n>_name / top_<n>_balance / top_<n>_balance_formatted
            if (key.startsWith("top_")) {
                String[] parts = key.split("_", 3);
                if (parts.length < 3) return null;
                int n;
                try { n = Integer.parseInt(parts[1]); } catch (NumberFormatException ex) { return null; }
                List<EconomyService.TopEntry> top = service.topAll();
                if (n < 1) return null;
                EconomyService.TopEntry entry = n <= top.size() ? top.get(n - 1) : null;
                return switch (parts[2]) {
                    case "name" -> entry == null ? "-" : entry.name();
                    case "balance" -> entry == null ? "0" : entry.balance().toPlainString();
                    case "balance_formatted" -> entry == null ? "-" : service.format(entry.balance());
                    default -> null;
                };
            }
            return null;
        });
    }

    // ---------------- 工具 ----------------

    /** 错误码 → 本地化纯文本（不含 MiniMessage 标签，用于 Vault 错误信息）。 */
    public String errorText(String code) {
        if (code == null) return "";
        return switch (code) {
            case EconomyServiceImpl.ERR_INVALID -> "金额无效";
            case EconomyServiceImpl.ERR_FUNDS -> "余额不足";
            case EconomyServiceImpl.ERR_MAX -> "超过账户最大余额";
            case EconomyServiceImpl.ERR_NO_ACCOUNT -> "账户不存在";
            case EconomyServiceImpl.ERR_SAME -> "不能给自己转账";
            case EconomyServiceImpl.ERR_LOADING -> "经济数据加载中，请稍后再试";
            default -> code;
        };
    }

    public String fmt(BigDecimal amount) {
        return service.format(amount);
    }
}
