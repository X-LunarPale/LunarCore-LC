package com.lunarboreal.core;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.command.CommandRegistry;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.module.ModuleManager;
import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.api.web.WebRegistry;
import com.lunarboreal.core.audit.LbrAuditLog;
import com.lunarboreal.core.command.LbrCommandRegistry;
import com.lunarboreal.core.command.LbrMainCommand;
import com.lunarboreal.core.database.SqlDatabase;
import com.lunarboreal.core.listener.CoreListener;
import com.lunarboreal.core.message.LbrMessageService;
import com.lunarboreal.core.module.LbrModuleManager;
import com.lunarboreal.core.module.ModuleMarket;
import com.lunarboreal.core.placeholder.CorePlaceholders;
import com.lunarboreal.core.placeholder.LbrPlaceholderRegistry;
import com.lunarboreal.core.player.LbrPlayerCache;
import com.lunarboreal.core.service.SimpleServiceRegistry;
import com.lunarboreal.core.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * LunarCore entry point and shared API.
 */
public final class LunarCorePlugin extends JavaPlugin implements LunarBorealAPI {

    private volatile boolean debug;
    private boolean paper;

    private LbrMessageService messages;
    private SqlDatabase database;
    private LbrPlayerCache playerCache;
    private LbrAuditLog auditLog;
    private SimpleServiceRegistry services;
    private LbrCommandRegistry commandRegistry;
    private LbrPlaceholderRegistry placeholders;
    private WebServer webServer;
    private LbrModuleManager moduleManager;
    private ModuleMarket moduleMarket;

    /** 非 Paper 平台的简易 TPS 统计 */
    private final double[] fallbackTps = {20, 20, 20};
    private long lastTickCheck = 0;

    // ================================================================== 生命周期

    @Override
    public void onEnable() {
        long t0 = System.currentTimeMillis();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        debug = getConfig().getBoolean("debug", false);
        paper = detectPaper();
        if (!paper) {
            getLogger().warning("==============================================================");
            getLogger().warning(" 未检测到 Paper！LunarCore 以 Paper 为主要平台，在 Spigot/Bukkit 上");
            getLogger().warning(" 部分功能会降级或不可用。强烈建议使用 Paper：https://papermc.io");
            getLogger().warning("==============================================================");
        }
        LBR.setInstance(this);

        // 语言
        messages = new LbrMessageService(this);
        messages.registerBundle("core", getClass().getClassLoader());

        // 数据库
        try {
            database = new SqlDatabase(this);
            playerCache = new LbrPlayerCache(this, database);
            playerCache.init();
            auditLog = new LbrAuditLog(this, database);
            auditLog.init();
            getLogger().info("数据库已连接：" + database.describe());
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "数据库初始化失败，LunarCore 将被禁用。请检查 config.yml 的 database 配置。", t);
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable ignored) {
                }
                database = null;
            }
            playerCache = null;
            LBR.setInstance(null);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 注册表
        services = new SimpleServiceRegistry(this);
        commandRegistry = new LbrCommandRegistry(this);
        placeholders = new LbrPlaceholderRegistry(this);
        placeholders.register("core", new CorePlaceholders(this), this);
        services.register(PlayerCache.class, playerCache, this);
        services.register(AuditLog.class, auditLog, this);

        // Web（构造后即可接受模块的路由/面板注册）
        webServer = new WebServer(this);

        // 监听器与主命令
        Bukkit.getPluginManager().registerEvents(playerCache, this);
        Bukkit.getPluginManager().registerEvents(new CoreListener(this), this);
        PluginCommand lc = getCommand("lc");
        if (lc != null) {
            LbrMainCommand main = new LbrMainCommand(this);
            lc.setExecutor(main);
            lc.setTabCompleter(main);
        }

        // 定时任务：每 5 分钟结算在线时长；非 Paper 平台统计 TPS
        Bukkit.getScheduler().runTaskTimer(this, playerCache::checkpoint, 20L * 300, 20L * 300);
        if (!paper) {
            lastTickCheck = System.nanoTime();
            Bukkit.getScheduler().runTaskTimer(this, this::sampleTps, 100L, 100L);
        }

        // 模块
        moduleManager = new LbrModuleManager(this);
        try {
            moduleMarket = new ModuleMarket(getDataFolder().toPath(), version());
            moduleMarket.applyPending();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "模块变更未能应用，保留原文件并停止启动", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        moduleManager.extractBuiltin(getFile());
        moduleManager.loadAll();

        // Web 启动
        try {
            webServer.start();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Web 面板启动失败", t);
        }

        // 以 /reload 或插件管理器在服务器运行中加载时，不会再收到 ServerLoadEvent
        if (!Bukkit.getWorlds().isEmpty()) {
            commandRegistry.markServerLoaded();
            placeholders.hookPapi();
            commandRegistry.scheduleSync();
        }
        getLogger().info("LunarCore 琉月 v" + version().replace("-preview", " 测试预览版") + " 已启用（" + (System.currentTimeMillis() - t0) + "ms）");
    }

    @Override
    public void onDisable() {
        if (moduleMarket != null) moduleMarket.close();
        if (webServer != null) {
            try {
                webServer.stop();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "停止 Web 面板出错", t);
            }
        }
        if (moduleManager != null) moduleManager.disableAll();
        if (placeholders != null) placeholders.unhookPapi();
        if (commandRegistry != null) {
            try {
                commandRegistry.unregisterEverything();
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getScheduler().cancelTasks(this);
        if (playerCache != null) playerCache.shutdown();
        if (database != null) database.close();
        if (LBR.isLoaded() && safeGet() == this) LBR.setInstance(null);
    }

    private static LunarBorealAPI safeGet() {
        try {
            return LBR.get();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 重载核心：config.yml、debug、语言、commands.disabled。不会调用 WebServer 的任何方法（主线程调用）。 */
    public void reloadCore() {
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        messages.reload();
        commandRegistry.reloadDisabled();
    }

    // ================================================================== 平台

    private static boolean detectPaper() {
        for (String cls : new String[]{"io.papermc.paper.configuration.Configuration", "com.destroystokyo.paper.PaperConfig",
                "io.papermc.paper.ServerBuildInfo"}) {
            try {
                Class.forName(cls);
                return true;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    private void sampleTps() {
        long now = System.nanoTime();
        long elapsed = now - lastTickCheck;
        lastTickCheck = now;
        if (elapsed <= 0) return;
        double tps = Math.min(20.0, 100.0 * 1_000_000_000.0 / elapsed);
        fallbackTps[0] = fallbackTps[0] * 0.8 + tps * 0.2;
        fallbackTps[1] = fallbackTps[1] * 0.95 + tps * 0.05;
        fallbackTps[2] = fallbackTps[2] * 0.983 + tps * 0.017;
    }

    /** 1m/5m/15m TPS。 */
    public double[] tps() {
        if (paper) {
            try {
                return Bukkit.getTPS();
            } catch (Throwable ignored) {
            }
        }
        return fallbackTps.clone();
    }

    /** 平均每 tick 毫秒数（非 Paper 根据 TPS 估算）。 */
    public double mspt() {
        if (paper) {
            try {
                return Bukkit.getAverageTickTime();
            } catch (Throwable ignored) {
            }
        }
        return 1000.0 / Math.max(0.1, fallbackTps[0]);
    }

    // ================================================================== 工具

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public void debug(String message) {
        if (debug) getLogger().info("[DEBUG] " + message);
    }

    /** owner 的可读名：module:&lt;id&gt; / plugin:&lt;name&gt;。 */
    public static String ownerName(Object owner) {
        if (owner instanceof LbrModule m) return "module:" + (m.description() == null ? "?" : m.id());
        if (owner instanceof Plugin p) return "plugin:" + p.getName();
        return String.valueOf(owner);
    }

    /** 外部插件卸载：清理其注册的一切。 */
    public void unregisterExternal(Plugin other) {
        if (commandRegistry != null) commandRegistry.unregisterAll(other);
        if (webServer != null) {
            try {
                webServer.unregisterAll(other);
            } catch (Throwable t) {
                debug("web 注销失败: " + t);
            }
        }
        if (services != null) services.unregisterAll(other);
        if (placeholders != null) placeholders.unregisterAll(other);
        if (messages != null) messages.unregisterBundles(other.getClass().getClassLoader());
    }

    /** 用于 /lc info 展示的 Web 访问地址。 */
    public String webAddress() {
        if (webServer == null || !webServer.isRunning()) {
            return messages.raw("core.web-offline");
        }
        String publicUrl = getConfig().getString("web.public-url", "");
        if (publicUrl != null && !publicUrl.isBlank()) return publicUrl;
        String host = getConfig().getString("web.host", "0.0.0.0");
        if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) {
            String ip = Bukkit.getIp();
            host = ip == null || ip.isBlank() ? "服务器IP" : ip;
        }
        String scheme = getConfig().getBoolean("web.https.enabled", false) ? "https" : "http";
        String base = getConfig().getString("web.base-path", "");
        return scheme + "://" + host + ":" + webServer.port() + (base == null ? "" : base);
    }

    // ================================================================== LunarBorealAPI

    @Override
    public Plugin plugin() {
        return this;
    }

    @SuppressWarnings("deprecation")
    @Override
    public String version() {
        return getDescription().getVersion();
    }

    @Override
    public SqlDatabase database() {
        return database;
    }

    @Override
    public LbrMessageService messages() {
        return messages;
    }

    @Override
    public CommandRegistry commands() {
        return commandRegistry;
    }

    @Override
    public SimpleServiceRegistry services() {
        return services;
    }

    @Override
    public WebRegistry web() {
        return webServer;
    }

    @Override
    public ModuleManager modules() {
        return moduleManager;
    }

    @Override
    public PlayerCache players() {
        return playerCache;
    }

    @Override
    public AuditLog audit() {
        return auditLog;
    }

    @Override
    public LbrPlaceholderRegistry placeholders() {
        return placeholders;
    }

    @Override
    public boolean isPaper() {
        return paper;
    }

    // ---------------- 具体类型 getter（供 core/web 包内部使用） ----------------

    public LbrCommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public WebServer webServer() {
        return webServer;
    }

    public LbrModuleManager moduleManager() {
        return moduleManager;
    }

    public ModuleMarket moduleMarket() {
        return moduleMarket;
    }

    public LbrPlayerCache playerCache() {
        return playerCache;
    }

    public LbrAuditLog auditLog() {
        return auditLog;
    }
}
