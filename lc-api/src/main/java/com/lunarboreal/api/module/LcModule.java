package com.lunarboreal.api.module;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.command.LcCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * LC 功能模块基类。模块 jar 放在 plugins/LunarCore/modules/ 下由 core 加载。
 * <p>
 * 生命周期：{@link #onLoad()} → {@link #onEnable()} → {@link #onDisable()}。
 * 通过本类注册的监听器、命令、任务、Web 路由/面板、服务会在模块卸载时自动清理。
 */
public abstract class LcModule {

    private ModuleDescription description;
    private File dataFolder;
    private ClassLoader moduleClassLoader;
    private Logger logger;
    private YamlConfiguration config;
    private boolean enabled;

    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.Queue<BukkitTask> tasks = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 由 core 在实例化后立即调用，模块请勿调用。 */
    public final void init(ModuleDescription description, File dataFolder, ClassLoader loader, Logger logger) {
        if (this.description != null) throw new IllegalStateException("already initialized");
        this.description = description;
        this.dataFolder = dataFolder;
        this.moduleClassLoader = loader;
        this.logger = logger;
    }

    /** 由 core 设置启用状态，模块请勿调用。 */
    public final void setEnabledState(boolean enabled) {
        this.enabled = enabled;
    }

    // ---------------- 生命周期 ----------------

    public void onLoad() {}

    public abstract void onEnable();

    public void onDisable() {}

    /** /lc module reload <id> 时调用；默认重载 config.yml。 */
    public void onReload() {
        reloadConfig();
    }

    // ---------------- 基础访问器 ----------------

    public final LunarBorealAPI api() {
        return LC.get();
    }

    public final Plugin plugin() {
        return LC.get().plugin();
    }

    public final ModuleDescription description() {
        return description;
    }

    public final String id() {
        return description.id();
    }

    public final File dataFolder() {
        return dataFolder;
    }

    public final ClassLoader moduleClassLoader() {
        return moduleClassLoader;
    }

    public final Logger logger() {
        return logger;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    // ---------------- 配置 ----------------

    /** 模块数据目录下的 config.yml，首次自动从 jar 内 config.yml 复制，并补全缺失键。 */
    public final YamlConfiguration config() {
        if (config == null) reloadConfig();
        return config;
    }

    public final void reloadConfig() {
        File file = new File(dataFolder, "config.yml");
        saveResource("config.yml", false);
        YamlConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        try (InputStream in = moduleClassLoader.getResourceAsStream("config.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(def);
                cfg.options().copyDefaults(true);
                if (file.exists()) cfg.save(file);
            }
        } catch (IOException e) {
            logger.warning("加载默认配置失败: " + e.getMessage());
        }
        this.config = cfg;
    }

    public final void saveConfig() {
        try {
            config().save(new File(dataFolder, "config.yml"));
        } catch (IOException e) {
            logger.severe("保存配置失败: " + e.getMessage());
        }
    }

    /** 把 jar 内资源复制到数据目录。 */
    public final void saveResource(String path, boolean replace) {
        File out = new File(dataFolder, path);
        if (out.exists() && !replace) return;
        try (InputStream in = moduleClassLoader.getResourceAsStream(path)) {
            if (in == null) return;
            out.getParentFile().mkdirs();
            Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warning("复制资源失败 " + path + ": " + e.getMessage());
        }
    }

    // ---------------- 注册（卸载时自动清理） ----------------

    public final void registerListener(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin());
        listeners.add(listener);
    }

    public final void registerCommand(LcCommand command) {
        api().commands().register(this, command);
    }

    public final BukkitTask runTask(Runnable r) {
        return track(Bukkit.getScheduler().runTask(plugin(), r));
    }

    public final BukkitTask runTaskLater(Runnable r, long delayTicks) {
        return track(Bukkit.getScheduler().runTaskLater(plugin(), r, delayTicks));
    }

    public final BukkitTask runTaskTimer(Runnable r, long delayTicks, long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimer(plugin(), r, delayTicks, periodTicks));
    }

    public final BukkitTask runAsync(Runnable r) {
        return track(Bukkit.getScheduler().runTaskAsynchronously(plugin(), r));
    }

    public final BukkitTask runAsyncTimer(Runnable r, long delayTicks, long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin(), r, delayTicks, periodTicks));
    }

    private BukkitTask track(BukkitTask task) {
        tasks.removeIf(BukkitTask::isCancelled);
        tasks.add(task);
        return task;
    }

    // ---------------- Web / 服务快捷方式（命名空间 = 模块 ID） ----------------

    /** Stable WebUI integration entry point for module routes, panels, permissions and static assets. */
    public final com.lunarboreal.api.web.WebRegistry web() {
        return api().web();
    }

    public final void webRoutes(java.util.function.Consumer<com.lunarboreal.api.web.WebRouter> builder) {
        web().routes(id(), this, builder);
    }

    public final void webPanel(com.lunarboreal.api.web.WebPanel panel) {
        api().web().registerPanel(id(), panel, this);
    }

    public final void webPermission(String node, String description) {
        api().web().registerPermission(node, description, this);
    }

    public final <T> void provide(Class<T> type, T impl) {
        api().services().register(type, impl, this);
    }

    /** 注册 %lc_&lt;模块ID&gt;_&lt;key&gt;% 变量（桥接 PlaceholderAPI）。 */
    public final void placeholder(com.lunarboreal.api.placeholder.PlaceholderRegistry.Resolver resolver) {
        api().placeholders().register(id(), resolver, this);
    }

    public final void audit(String actor, String action, String detail) {
        api().audit().log(id(), actor, action, detail);
    }

    // ---------------- 消息快捷方式 ----------------

    /** 发送本模块语言文件中的消息，key 不需要带模块前缀。占位符格式 {name}，参数按 key,value 交替传入。 */
    public final void msg(CommandSender to, String key, Object... placeholders) {
        api().messages().send(to, id() + "." + key, placeholders);
    }

    public final String raw(String key, Object... placeholders) {
        return api().messages().raw(id() + "." + key, placeholders);
    }

    public final Map<String, Object> placeholders(Object... kv) {
        return com.lunarboreal.api.message.MessageService.toMap(kv);
    }

    private final List<org.bukkit.permissions.Permission> permissions = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 声明 Bukkit 权限节点及其默认值（未声明的节点 Bukkit 默认仅 OP 拥有）。
     * 普通玩家默认可用的功能（如 /msg、/home、/signin）请用 PermissionDefault.TRUE 声明。模块禁用时自动移除。
     */
    public final void registerPermission(String node, org.bukkit.permissions.PermissionDefault def, String description) {
        var pm = Bukkit.getPluginManager();
        org.bukkit.permissions.Permission existing = pm.getPermission(node);
        if (existing != null) {
            existing.setDefault(def);
            return;
        }
        org.bukkit.permissions.Permission perm = new org.bukkit.permissions.Permission(node, description, def);
        pm.addPermission(perm);
        permissions.add(perm);
    }

    /** 批量声明玩家默认拥有（PermissionDefault.TRUE）的节点。 */
    public final void defaultPermissions(String... nodes) {
        for (String n : nodes) registerPermission(n, org.bukkit.permissions.PermissionDefault.TRUE, "");
    }

    /** 由 core 在 onDisable 之后调用，清理监听器与任务。 */
    public final void cleanupInternal() {
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        permissions.forEach(p -> Bukkit.getPluginManager().removePermission(p));
        permissions.clear();
        tasks.forEach(t -> { if (!t.isCancelled()) t.cancel(); });
        tasks.clear();
    }
}
