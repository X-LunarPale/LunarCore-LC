package com.lunarboreal.qqauth;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.qqauth.auth.*;
import com.lunarboreal.qqauth.binding.*;
import com.lunarboreal.qqauth.bridge.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Owns lifecycle, bounded authentication workers and both QQ transports. */
public final class QQAuthModule extends LbrModule {
    private volatile Messages messages = new Messages();
    String msg(String key, Object... args) { return messages.text(key, args); }
    String formattedMsg(String key, Object... args) { return messages.formatted(key, args); }
    String plainMsg(String key, Object... args) { return Messages.plain(messages.formatted(key, args)); }
    net.kyori.adventure.text.Component componentMsg(String key, Object... args) { return Messages.render(messages.formatted(key, args)); }
    void applyMessages(org.bukkit.configuration.file.YamlConfiguration configuration) { messages = new Messages(configuration); }
    String authStatus(AuthService.Result result) { return plainMsg("status.auth." + result.name()); }
    String bindingStatus(BindingService.Status status) { return plainMsg("status.binding." + status.name()); }
    net.kyori.adventure.text.Component authStatusComponent(AuthService.Result result) { return componentMsg("status.auth." + result.name()); }
    net.kyori.adventure.text.Component bindingStatusComponent(BindingService.Status status) { return componentMsg("status.binding." + status.name()); }
    volatile AuthService auth;
    volatile BindingService bindings;
    volatile boolean ready;
    volatile boolean active;
    private ThreadPoolExecutor workers;
    private AutoCloseable transport;
    private volatile long epoch;
    private final LinkedHashMap<String, long[]> groupAttempts = new LinkedHashMap<>();
    private synchronized boolean admitGroup(String sender) {
        long now = System.currentTimeMillis();
        groupAttempts.values().removeIf(v -> v[1] <= now);
        long[] budget = groupAttempts.get(sender);
        if (budget == null) {
            if (groupAttempts.size() >= 10000) return false;
            budget = new long[]{0, now + 300000}; groupAttempts.put(sender, budget);
        }
        return ++budget[0] <= 10;
    }
    final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    final Map<UUID, Long> joined = new ConcurrentHashMap<>();
    final Map<UUID, Player> granted = new ConcurrentHashMap<>();
    final Map<UUID, Player> connections = new ConcurrentHashMap<>();
    final Set<UUID> bound = ConcurrentHashMap.newKeySet();
    final MovementFreeze movement = new MovementFreeze();

    @Override public void onEnable() {
        applyMessages(config());
        if (Bukkit.getPluginManager().getPlugin("AuthMe") != null)
            throw new IllegalStateException(msg("lifecycle.authme-conflict"));
        if (!config().getBoolean("auth.enabled", true))
            throw new IllegalStateException(msg("lifecycle.auth-required"));
        validateConfig();
        active = true;
        long current = ++epoch;
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "QQAuth-Worker"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        registerPermission("qqauth.admin", PermissionDefault.OP, msg("permission.admin"));
        registerListener(new AuthListener(this));
        AuthCommands.register(this);
        QQAuthWeb.register(this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            joined.put(p.getUniqueId(), System.currentTimeMillis());
            p.kick(componentMsg("kick.loading"));
        }
        submit(() -> {
            try {
                SqlAccountRepository accounts = new SqlAccountRepository(api().database());
                accounts.initialize();
                AuthService nextAuth = new AuthService(accounts, policy());
                BindingRepository repo = new BindingRepository(api().database());
                repo.initialize();
                repo.all().forEach(binding -> bound.add(binding.uuid()));
                BindingService nextBindings = new BindingService(repo, this::bindingSettings,
                        (name, generation, prepared) -> nextAuth.setPassword(name, generation, prepared) == AuthService.Result.SUCCESS);
                synchronized (this) {
                    if (!active || current != epoch) return;
                    auth = nextAuth;
                    bindings = nextBindings;
                    startTransport();
                    ready = true;
                }
                logger().info("QQAuth 数据加载完成；账号与 QQ 绑定使用 LunarCore 数据库。未绑定玩家将在预登录阶段拦截。");
            } catch (Exception ex) {
                logger().log(java.util.logging.Level.SEVERE, "QQAuth 加载失败，保持拒绝登录，请检查配置/数据库后重新加载模块。", ex);
            }
        });
        // Reconcile worker-driven invalidations on the main thread, without retaining past connections.
        runTaskTimer(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) movement.update(p, !authenticated(p));
        }, 1, 1);
        runTaskTimer(() -> {
            if (!ready) return;
            long now = System.currentTimeMillis();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!authenticated(p)) {
                    long start = joined.computeIfAbsent(id, k -> now);
                    if (now - start > 120_000) { p.kick(componentMsg("kick.timeout")); continue; }
                    p.sendActionBar(componentMsg(auth.account(p.getName()).isPresent() ? "reminder.login" : "reminder.register", "player", p.getName()));
                }
            }
        }, 100, 100);
        runAsyncTimer(() -> { if (ready) bindings.sweepExpired(); }, 1200, 1200);
    }

    private void validateConfig() {
        policy(); bindingSettings();
        String mode = config().getString("integration.mode", "astrbot");
        if (!Set.of("astrbot", "onebot", "disabled").contains(mode)) throw new IllegalArgumentException("integration.mode 无效");
        if (mode.equals("astrbot")) {
            String token = config().getString("integration.astrbot.token", "");
            if (token.equals("CHANGE_THIS_LONG_RANDOM_TOKEN") || token.isBlank()) {
                byte[] secret = new byte[32]; new java.security.SecureRandom().nextBytes(secret);
                config().set("integration.astrbot.token", Base64.getUrlEncoder().withoutPadding().encodeToString(secret)); saveConfig();
                logger().info("已生成 QQAuth 桥接 token，请从模块 config.yml 配置到 AstrBot；不要公开该文件。");
            }
        }
    }

    AuthPolicy policy() {
        return new AuthPolicy(config().getInt("auth.bcrypt-cost", 12), config().getInt("auth.min-password-length", 10),
                config().getInt("auth.max-password-length", 64), config().getInt("auth.max-accounts-per-ip", 3),
                config().getInt("auth.max-attempts", 8), Duration.ofSeconds(config().getLong("auth.attempt-window-seconds", 300)),
                config().getInt("auth.max-attempt-entries", 20000), config().getBoolean("auth.sessions.enabled", false),
                Duration.ofSeconds(config().getLong("auth.sessions.ttl-seconds", 86400)), config().getInt("auth.sessions.max-sessions", 100000));
    }

    BindingSettings bindingSettings() {
        Map<String, Integer> quotas = new HashMap<>();
        var section = config().getConfigurationSection("binding.per-qq-limits");
        if (section != null) for (String key : section.getKeys(false)) quotas.put(key, section.getInt(key));
        return new BindingSettings(config().getInt("binding.code-length", 6),
                Duration.ofSeconds(config().getLong("binding.bind-ttl-seconds", 600)),
                Duration.ofSeconds(config().getLong("binding.unbind-ttl-seconds", 600)),
                Duration.ofSeconds(config().getLong("binding.reset-ttl-seconds", 300)),
                config().getInt("binding.max-pending-codes", 10000), config().getInt("binding.max-per-qq", 1), quotas);
    }

    private void startTransport() throws Exception {
        String mode = config().getString("integration.mode", "astrbot");
        if (mode.equals("astrbot")) {
            var server = new AstrBotHttpServer(logger(), new AstrBotSettings(
                    config().getString("integration.astrbot.bind-address", "127.0.0.1"),
                    config().getInt("integration.astrbot.port", 12312), config().getString("integration.astrbot.token")), this::groupMessage);
            server.start(); transport = server;
        } else if (mode.equals("onebot")) {
            var client = new OneBotClient(logger(), new OneBotSettings(URI.create(config().getString("integration.onebot.endpoint")),
                    config().getString("integration.onebot.token", ""), Duration.ofSeconds(config().getLong("integration.onebot.reconnect-seconds", 5))), this::groupMessage);
            client.start(); transport = client;
        }
        if (config().getString("integration.group-id", "").contains("REPLACE_"))
            logger().warning("尚未配置 integration.group-id，群验证将被拒绝。设置正确群 ID 后重新加载模块。");
    }

    private GroupResult groupMessage(String group, String sender, String message) {
        if (!active || !ready) return GroupResult.reply(msg("group.not-ready"));
        String expected = config().getString("integration.group-id", "");
        if (expected.isBlank() || expected.contains("REPLACE_") || (!expected.equals("*") && !expected.equals(group))) return GroupResult.ignored();
        String[] args = message.strip().split("\\s+");
        String command = args[0].replaceFirst("^/", "");
        if (!Set.of("验证", "解绑", "改密").contains(command)) return GroupResult.ignored();
        if (!admitGroup(group + ":" + sender)) return GroupResult.reply(msg("group.rate-limited"));
        if (args.length != 2 || !args[1].matches("[0-9]{6,8}")) return GroupResult.reply(msg("group.usage", "command", command));
        try {
            BindingService.Result result = switch (command) {
                case "验证" -> bindings.redeemBind(args[1], group, sender);
                case "解绑" -> bindings.redeemUnbind(args[1], group, sender);
                default -> bindings.redeemReset(args[1], group, sender);
            };
            if (result.status() == BindingService.Status.SUCCESS) {
                if (command.equals("验证")) bound.add(result.binding().uuid());
                if (command.equals("解绑")) bound.remove(result.binding().uuid());
                if (!command.equals("验证")) kick(result.binding().uuid(), msg("kick.qq-completed"));
                audit("QQ:" + sender, "qq-" + command, result.binding().playerName());
                return GroupResult.reply(msg("group.success", "command", command, "player", result.binding().playerName()));
            }
            return GroupResult.reply(msg("group.failed", "status", bindingStatus(result.status())));
        } catch (Exception ex) {
            logger().log(java.util.logging.Level.WARNING, "QQ 验证处理失败", ex);
            return GroupResult.reply(msg("group.unavailable"));
        }
    }

    <T> T work(java.util.concurrent.Callable<T> task) throws Exception {
        var future = new java.util.concurrent.FutureTask<T>(task);
        if (!submit(future)) throw new java.util.concurrent.RejectedExecutionException("认证队列已满");
        // Wait for this accepted mutation: do not timeout and later silently commit it.
        try { return future.get(); }
        catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("认证任务失败");
        }
    }
    boolean submit(Runnable task) {
        try { workers.execute(task); return true; }
        catch (RejectedExecutionException ex) { return false; }
    }
    boolean authenticated(Player p) { return ready && bound.contains(p.getUniqueId()) && granted.get(p.getUniqueId()) == p && auth.isAuthenticated(p.getUniqueId()); }
    String ip(Player p) { return p.getAddress() == null ? "unknown" : p.getAddress().getAddress().getHostAddress(); }
    void sync(Runnable task) { if (active) runTask(() -> { if (active) task.run(); }); }
    void kick(UUID uuid, String reason) {
        if (auth != null) auth.logout(uuid);
        sync(() -> { Player p = Bukkit.getPlayer(uuid); if (p != null) p.kick(Messages.render(reason)); });
    }
    @Override public void onReload() { throw new IllegalStateException(msg("lifecycle.reload-instructions")); }
    @Override public void onDisable() {
        synchronized (this) { active = false; ready = false; epoch++; }
        movement.close();
        if (transport != null) try { transport.close(); } catch (Exception ex) { logger().warning("关闭 QQ 桥接失败"); }
        if (workers != null) {
            workers.shutdown();
            try { if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow(); }
            catch (InterruptedException ex) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        if (bindings != null) bindings.clear();
        busy.clear(); joined.clear(); granted.clear(); connections.clear(); bound.clear();
        synchronized (this) { groupAttempts.clear(); }
        for (Player p : Bukkit.getOnlinePlayers()) p.kick(componentMsg("kick.disabled"));
    }
}
