package com.lunarboreal.core.web;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.module.ModuleManager;
import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebHandler;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 核心 REST API（/api/...）。 */
final class CoreApi {

    private final WebServer web;
    private final LunarCorePlugin plugin;
    private final BrandingManager branding;
    private final long startTime = System.currentTimeMillis();

    private BukkitTask sampler;
    private BukkitTask tickTask;
    private final long[] tickTimes = new long[100];
    private int tickIndex;
    private int tickFilled;
    private volatile Map<String, Object> snapshot = Map.of();
    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();

    CoreApi(WebServer web, LunarCorePlugin plugin) {
        this.web = web;
        this.plugin = plugin;
        this.branding = new BrandingManager(plugin.getDataFolder().toPath());
    }

    private static LunarBorealAPI api() {
        return LBR.get();
    }

    private void route(String method, String path, String permission, WebHandler h) {
        web.routes.add(method, path, permission, false, h, web, "core");
    }

    private void open(String method, String path, WebHandler h) {
        web.routes.add(method, path, null, true, h, web, "core");
    }

    private void perm(String node, String desc) {
        web.registerPermission(node, desc, web);
    }

    // =================================================================== 注册

    void register() {
        perm("core.dashboard", "查看仪表盘");
        perm("core.players.view", "查看玩家");
        perm("core.players.manage", "管理玩家（踢出/私信/执行命令）");
        perm("core.console.view", "查看控制台日志");
        perm("core.console.execute", "执行控制台命令");
        perm("core.modules.view", "查看模块");
        perm("core.modules.manage", "管理模块与模块市场");
        perm("core.plugins.view", "查看插件列表");
        perm("core.audit.view", "查看操作日志");
        perm("core.config.edit", "管理服务器与核心配置");
        perm("core.users.manage", "管理 Web 账号与角色");
        perm("core.server.manage", "服务器操作（广播/保存/白名单）");

        // ---------- auth
        open("GET", "/api/auth/info", this::authInfo);
        open("GET", "/api/branding/logo", ctx -> {
            BrandingManager.Logo logo = branding.logo();
            if (logo == null) throw new HttpException(404, "Logo 不存在");
            ((WebContextImpl) ctx).image(logo.bytes(), logo.contentType());
        });
        route("PUT", "/api/branding", "core.config.edit", ctx -> {
            ctx.json(branding.saveName(((WebContextImpl) ctx).required("name")));
            web.audit((WebContextImpl) ctx, "branding.name", "更新 Web 面板名称");
        });
        route("DELETE", "/api/branding", "core.config.edit", ctx -> {
            ctx.json(branding.resetName());
            web.audit((WebContextImpl) ctx, "branding.name.reset", "恢复默认 Web 面板名称");
        });
        route("POST", "/api/branding/logo", "core.config.edit", ctx -> {
            ctx.json(branding.saveLogo(((WebContextImpl) ctx).required("dataUrl")));
            web.audit((WebContextImpl) ctx, "branding.logo", "更新 Web 面板 Logo");
        });
        route("DELETE", "/api/branding/logo", "core.config.edit", ctx -> {
            ctx.json(branding.resetLogo());
            web.audit((WebContextImpl) ctx, "branding.logo.reset", "恢复默认 Web 面板 Logo");
        });
        open("POST", "/api/auth/login", this::login);
        open("POST", "/api/auth/game", this::gameLogin);
        open("POST", "/api/auth/totp/complete", this::completeTotpLogin);
        route("GET", "/api/auth/me", null, ctx -> ctx.json(me(((WebContextImpl) ctx).userImpl())));
        route("POST", "/api/auth/logout", null, ctx -> {
            // 递增 token 版本：该账号在所有设备上的登录全部失效（持久化，重启后仍有效）
            web.auth.invalidateTokens(ctx.user().username());
            web.refreshWsUsers();
            ctx.ok("已退出登录");
        });
        route("POST", "/api/auth/password", null, this::changePassword);
        route("GET", "/api/auth/totp", null, ctx -> ctx.json(Json.of("enabled", web.auth.totpEnabled(ctx.user().username()))));
        route("POST", "/api/auth/totp/setup", null, this::setupTotp);
        route("POST", "/api/auth/totp/enable", null, this::enableTotp);
        route("POST", "/api/auth/totp/disable", null, this::disableTotp);
        route("POST", "/api/auth/bind", null, this::bind);
        route("POST", "/api/auth/unbind", null, this::unbind);

        // ---------- panels
        route("GET", "/api/panels", null, ctx -> ctx.json(web.panelsFor(((WebContextImpl) ctx).userImpl())));

        // ---------- dashboard
        route("GET", "/api/dashboard", "core.dashboard", ctx -> {
            Map<String, Object> out = new LinkedHashMap<>(snapshot);
            synchronized (history) {
                out.put("history", new ArrayList<>(history));
            }
            ctx.json(out);
        });

        // ---------- players
        route("GET", "/api/players/online", "core.players.view", this::onlinePlayers);
        route("GET", "/api/players/history", "core.players.view", this::historyPlayers);
        route("POST", "/api/players/{uuid}/kick", "core.players.manage", this::kick);
        route("POST", "/api/players/{uuid}/message", "core.players.manage", this::message);
        route("POST", "/api/players/{uuid}/command", "core.players.manage", this::playerCommand);

        // ---------- console
        route("GET", "/api/console/history", "core.console.view", ctx -> ctx.json(web.console.history()));
        route("POST", "/api/console/exec", "core.console.execute", this::exec);

        // ---------- modules
        route("GET", "/api/modules", "core.modules.view", this::modules);
        route("POST", "/api/modules/{id}/enable", "core.modules.manage", ctx -> moduleOp(ctx, "enable"));
        route("POST", "/api/modules/{id}/disable", "core.modules.manage", ctx -> moduleOp(ctx, "disable"));
        route("POST", "/api/modules/{id}/reload", "core.modules.manage", ctx -> moduleOp(ctx, "reload"));
        route("GET", "/api/market", "core.modules.view", ctx -> ctx.json(plugin.moduleMarket().snapshot()));
        route("POST", "/api/market/{id}/install", "core.modules.manage", ctx -> {
            plugin.moduleMarket().install(ctx.pathParam("id"));
            web.audit((WebContextImpl) ctx, "market.install", ctx.pathParam("id"));
            ctx.status(202);
            ctx.ok("下载任务已开始 / Download started");
        });
        route("POST", "/api/market/{id}/remove", "core.modules.manage", ctx -> {
            plugin.moduleMarket().remove(ctx.pathParam("id"));
            web.audit((WebContextImpl) ctx, "market.remove", ctx.pathParam("id"));
            ctx.ok("将在重启后卸载 / Will be removed after restart");
        });
        route("DELETE", "/api/market/pending", "core.modules.manage", ctx -> {
            plugin.moduleMarket().cancel();
            web.audit((WebContextImpl) ctx, "market.cancel", "取消待应用变更");
            ctx.ok("已取消待应用变更 / Pending changes cancelled");
        });

        // ---------- plugins
        route("GET", "/api/plugins", "core.plugins.view", this::plugins);

        // ---------- audit
        route("GET", "/api/audit", "core.audit.view", this::audit);

        // ---------- config
        new PaperConfigApi(java.nio.file.Path.of(".")).register(web);
        new TextPreviewApi().register(web);
        route("GET", "/api/config", "core.config.edit", this::getConfig);
        route("POST", "/api/config/validate", "core.config.edit", ctx -> {
            String err = validateYaml(((WebContextImpl) ctx).required("content"));
            ctx.json(Json.of("ok", err == null, "error", err));
        });
        route("POST", "/api/config", "core.config.edit", this::saveConfig);

        // ---------- users & roles
        route("GET", "/api/users", "core.users.manage", this::listUsers);
        route("POST", "/api/users", "core.users.manage", this::createUser);
        route("PUT", "/api/users/{name}", "core.users.manage", this::updateUser);
        route("DELETE", "/api/users/{name}", "core.users.manage", this::deleteUser);
        route("DELETE", "/api/users/{name}/totp", "core.users.manage", this::resetUserTotp);
        route("GET", "/api/roles", "core.users.manage", this::listRoles);
        route("GET", "/api/permissions", "core.users.manage", this::listPermissions);
        route("POST", "/api/roles", "core.users.manage", this::saveRole);
        route("PUT", "/api/roles/{name}", "core.users.manage", this::saveRole);
        route("DELETE", "/api/roles/{name}", "core.users.manage", this::deleteRole);

        // ---------- server
        route("POST", "/api/server/broadcast", "core.server.manage", this::broadcast);
        route("POST", "/api/server/save", "core.server.manage", this::saveWorlds);
        route("GET", "/api/server/whitelist", "core.server.manage", this::whitelist);
        route("POST", "/api/server/whitelist", "core.server.manage", this::whitelistToggle);
        route("POST", "/api/server/whitelist/add", "core.server.manage", ctx -> whitelistEdit(ctx, true));
        route("POST", "/api/server/whitelist/remove", "core.server.manage", ctx -> whitelistEdit(ctx, false));
    }

    // =================================================================== 采样

    void start() {
        stop();
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L, 100L);
        if (!isPaper()) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                tickTimes[tickIndex] = System.nanoTime();
                tickIndex = (tickIndex + 1) % tickTimes.length;
                if (tickFilled < tickTimes.length) tickFilled++;
            }, 1L, 1L);
        }
    }

    void stop() {
        if (sampler != null) sampler.cancel();
        if (tickTask != null) tickTask.cancel();
        sampler = null;
        tickTask = null;
    }

    private static boolean isPaper() {
        try {
            return LBR.get().isPaper();
        } catch (Throwable t) {
            return false;
        }
    }

    private double ownTps() {
        if (tickFilled < 2) return 20.0;
        int newest = (tickIndex - 1 + tickTimes.length) % tickTimes.length;
        int oldest = tickFilled < tickTimes.length ? 0 : tickIndex;
        long span = tickTimes[newest] - tickTimes[oldest];
        if (span <= 0) return 20.0;
        return Math.min(20.0, (tickFilled - 1) * 1_000_000_000.0 / span);
    }

    /** 主线程执行。 */
    private void sample() {
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            double[] tps;
            double mspt = -1;
            if (isPaper()) {
                tps = Bukkit.getServer().getTPS();
                mspt = Bukkit.getServer().getAverageTickTime();
            } else {
                double t = ownTps();
                tps = new double[]{t, t, t};
            }
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            int entities = 0, chunks = 0;
            List<Map<String, Object>> worlds = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) {
                int e, c;
                if (isPaper()) {
                    e = w.getEntityCount();
                    c = w.getChunkCount();
                } else {
                    e = w.getEntities().size();
                    c = w.getLoadedChunks().length;
                }
                entities += e;
                chunks += c;
                worlds.add(Json.of("name", w.getName(), "environment", w.getEnvironment().name(),
                        "players", w.getPlayers().size(), "entities", e, "chunks", c));
            }
            double cpuProcess = -1, cpuSystem = -1;
            try {
                if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                    cpuProcess = os.getProcessCpuLoad() * 100;
                    cpuSystem = os.getCpuLoad() * 100;
                }
            } catch (Throwable ignored) {
            }
            s.put("time", System.currentTimeMillis());
            s.put("tps", new double[]{round(tps[0]), round(tps.length > 1 ? tps[1] : tps[0]), round(tps.length > 2 ? tps[2] : tps[0])});
            s.put("mspt", round(mspt));
            s.put("online", Bukkit.getOnlinePlayers().size());
            s.put("maxPlayers", Bukkit.getMaxPlayers());
            s.put("memoryUsed", used);
            s.put("memoryMax", max);
            s.put("memoryTotal", rt.totalMemory());
            s.put("cpuProcess", round(cpuProcess));
            s.put("cpuSystem", round(cpuSystem));
            s.put("cores", rt.availableProcessors());
            s.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
            s.put("pluginUptime", System.currentTimeMillis() - startTime);
            s.put("worldCount", worlds.size());
            s.put("entities", entities);
            s.put("chunks", chunks);
            s.put("worlds", worlds);
            s.put("plugins", Bukkit.getPluginManager().getPlugins().length);
            s.put("modules", api().modules().loaded().size());
            s.put("serverVersion", Bukkit.getVersion());
            s.put("bukkitVersion", Bukkit.getBukkitVersion());
            s.put("coreVersion", plugin.getDescription().getVersion());
            s.put("javaVersion", System.getProperty("java.version"));
            s.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            s.put("paper", isPaper());
            snapshot = s;
            Map<String, Object> point = Json.of("time", s.get("time"), "tps", round(tps[0]), "mspt", round(mspt),
                    "online", s.get("online"), "memory", used, "cpu", round(cpuProcess));
            synchronized (history) {
                history.addLast(point);
                while (history.size() > 120) history.removeFirst();
            }
            web.broadcast("dashboard", point);
        } catch (Throwable t) {
            plugin.getLogger().fine("仪表盘采样失败：" + t);
        }
    }

    private static double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    // =================================================================== auth

    private void authInfo(com.lunarboreal.api.web.WebContext ctx) {
        Map<String, Object> info = new LinkedHashMap<>(branding.info());
        info.put("version", plugin.getDescription().getVersion());
        info.put("gameLogin", true);
        info.put("ready", web.authReady());
        ctx.json(info);
    }

    Map<String, Object> me(WebUserImpl u) {
        AuthManager.UserRow row = web.auth.user(u.username());
        AuthManager.RoleRow role = web.auth.role(u.role());
        return Json.of("username", u.username(), "role", u.role(),
                "roleName", role == null ? u.role() : role.display(),
                "permissions", u.permissions(),
                "mcUuid", row == null ? null : row.mcUuid(),
                "mcName", row == null ? null : row.mcName(),
                "totpEnabled", row != null && web.auth.totpEnabled(row.username()),
                "lastLogin", row == null ? 0 : row.lastLogin(),
                "expiresAt", u.expiresAt());
    }

    private void login(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String ip = ctx.ip();
        long locked = web.auth.lockRemaining(ip);
        if (locked > 0) throw new HttpException(429, "登录失败次数过多，请 " + (locked / 60_000 + 1) + " 分钟后再试");
        String username = ctx.required("username");
        String password = ctx.required("password");
        String userKey = "user:" + username.toLowerCase(Locale.ROOT);
        long userLocked = web.auth.lockRemaining(userKey);
        if (userLocked > 0) throw new HttpException(429, "该账号登录失败次数过多，请 " + (userLocked / 60_000 + 1) + " 分钟后再试");
        AuthManager.UserRow u = web.auth.checkCredentials(username, password);
        if (u == null) {
            // 按 IP 与账号分别计数：防止单 IP 爆破，也防止多 IP 针对同一账号爆破
            if (web.auth.user(username) != null) web.auth.recordFailure(userKey);
            boolean nowLocked = web.auth.recordFailure(ip);
            try {
                api().audit().log("web", "web:" + username, "login.fail", "IP " + ip + (nowLocked ? "（已锁定）" : ""));
            } catch (Throwable ignored) {
            }
            throw new HttpException(401, nowLocked ? "登录失败次数过多，IP 已被临时锁定" : "用户名或密码错误");
        }
        if (web.auth.totpEnabled(u.username())) {
            ctx.json(Json.of("totpRequired", true, "challenge", web.auth.totpChallenge(u, ip, "login", "密码登录")));
            return;
        }
        web.auth.recordSuccess(ip);
        web.auth.recordSuccess(userKey);
        loginResponse(ctx, web.auth.touch(u, ip), "login", "密码登录");
    }

    private void loginResponse(WebContextImpl ctx, AuthManager.UserRow u, String action, String how) {
        String token = web.auth.issue(u);
        WebUserImpl user = web.auth.verify(token);
        try {
            api().audit().log("web", "web:" + u.username(), action, how + " IP " + ctx.ip());
        } catch (Throwable ignored) {
        }
        ctx.json(Json.of("token", token, "user", me(user)));
    }

    private void gameLogin(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String ip = ctx.ip();
        long locked = web.auth.lockRemaining(ip);
        if (locked > 0) throw new HttpException(429, "尝试次数过多，请稍后再试");
        AuthManager.BindCode code = web.auth.consumeBindCode(ctx.required("code"));
        if (code == null) {
            web.auth.recordFailure(ip);
            throw new HttpException(401, "验证码无效或已过期，请在游戏内重新执行 /lc web bind");
        }
        AuthManager.UserRow u = web.auth.userByMc(code.uuid());
        if (u == null) {
            throw new HttpException(403, "游戏账号 " + code.name() + " 尚未绑定 Web 账号，请先用密码登录后在“个人设置”中绑定");
        }
        if (web.auth.totpEnabled(u.username())) {
            ctx.json(Json.of("totpRequired", true, "challenge",
                    web.auth.totpChallenge(u, ip, "login.game", "游戏账号 " + code.name() + " 登录")));
            return;
        }
        web.auth.recordSuccess(ip);
        loginResponse(ctx, web.auth.touch(u, ip), "login.game", "游戏账号 " + code.name() + " 登录");
    }

    private void completeTotpLogin(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        if (web.auth.lockRemaining(ctx.ip()) > 0) throw new HttpException(429, "尝试次数过多，请稍后再试");
        TotpManager.Login completed = web.auth.completeTotp(ctx.required("challenge"), ctx.ip(), ctx.required("code"));
        if (completed == null) throw new HttpException(401, "验证码或恢复码无效、已过期，请重新登录");
        loginResponse(ctx, completed.user(), completed.action(), completed.how() + "（两步验证）");
    }

    private void setupTotp(com.lunarboreal.api.web.WebContext c) {
        WebContextImpl ctx = (WebContextImpl) c;
        String key = "totp:" + ctx.user().username().toLowerCase(Locale.ROOT);
        if (web.auth.lockRemaining(key) > 0) throw new HttpException(429, "验证尝试过多，请稍后再试");
        AuthManager.UserRow user = web.auth.user(ctx.user().username());
        if (user == null || !AuthManager.verifyPassword(ctx.required("password"), user.hash())) {
            web.auth.recordFailure(key);
            throw HttpException.badRequest("密码错误");
        }
        web.auth.recordSuccess(key);
        ctx.json(web.auth.beginTotp(user));
    }

    private void enableTotp(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String key = "totp:" + ctx.user().username().toLowerCase(Locale.ROOT);
        if (web.auth.lockRemaining(key) > 0) throw new HttpException(429, "验证尝试过多，请稍后再试");
        AuthManager.UserRow user = web.auth.user(ctx.user().username());
        try {
            List<String> recovery = web.auth.enableTotp(user, ctx.userImpl().tokenVersion(), ctx.required("code"));
            web.auth.recordSuccess(key);
            web.refreshWsUsers();
            web.audit(ctx, "totp.enable", "启用登录两步验证");
            ctx.json(Json.of("enabled", true, "recoveryCodes", recovery, "token", web.auth.issue(web.auth.user(user.username()))));
        } catch (HttpException ex) {
            web.auth.recordFailure(key);
            throw ex;
        }
    }

    private void disableTotp(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String key = "totp:" + ctx.user().username().toLowerCase(Locale.ROOT);
        if (web.auth.lockRemaining(key) > 0) throw new HttpException(429, "验证尝试过多，请稍后再试");
        AuthManager.UserRow user = web.auth.user(ctx.user().username());
        if (user == null || !AuthManager.verifyPassword(ctx.required("password"), user.hash())
                || !web.auth.disableTotp(user, ctx.userImpl().tokenVersion(), ctx.required("code"))) {
            web.auth.recordFailure(key);
            throw HttpException.badRequest("密码或动态验证码无效");
        }
        web.auth.recordSuccess(key);
        web.refreshWsUsers();
        web.audit(ctx, "totp.disable", "停用登录两步验证");
        ctx.json(Json.of("enabled", false, "token", web.auth.issue(web.auth.user(user.username()))));
    }

    private void changePassword(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String old = ctx.required("oldPassword");
        String pw = ctx.required("newPassword");
        AuthManager.UserRow u = web.auth.user(ctx.user().username());
        if (u == null || !AuthManager.verifyPassword(old, u.hash())) throw HttpException.badRequest("原密码错误");
        AuthManager.UserRow n = web.auth.changePassword(u, ctx.userImpl().tokenVersion(), pw);
        web.refreshWsUsers();
        web.audit(ctx, "password.change", "修改自己的密码（其它设备的登录已失效）");
        ctx.json(Json.of("ok", true, "message", "密码已修改", "token", web.auth.issue(n)));
    }

    private void bind(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String userKey = "bind:" + ctx.user().username().toLowerCase(Locale.ROOT);
        if (web.auth.lockRemaining(ctx.ip()) > 0 || web.auth.lockRemaining(userKey) > 0) {
            throw new HttpException(429, "尝试次数过多，请稍后再试");
        }
        AuthManager.BindCode code = web.auth.consumeBindCode(ctx.required("code"));
        if (code == null) {
            web.auth.recordFailure(ctx.ip());
            web.auth.recordFailure(userKey);
            throw HttpException.badRequest("验证码无效或已过期，请在游戏内执行 /lc web bind 获取");
        }
        web.auth.bindMc(ctx.user().username(), code.uuid(), code.name());
        web.audit(ctx, "bind", "绑定游戏账号 " + code.name() + " (" + code.uuid() + ")");
        ctx.ok("已绑定游戏账号 " + code.name());
    }

    private void unbind(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        web.auth.bindMc(ctx.user().username(), null, null);
        web.audit(ctx, "unbind", "解除游戏账号绑定");
        ctx.ok("已解除绑定");
    }

    // =================================================================== players

    private void onlinePlayers(com.lunarboreal.api.web.WebContext ctx) throws Exception {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> list = ctx.sync(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!q.isEmpty() && !p.getName().toLowerCase(Locale.ROOT).contains(q)) continue;
                var loc = p.getLocation();
                Map<String, Object> m = Json.of(
                        "uuid", p.getUniqueId().toString(),
                        "name", p.getName(),
                        "world", loc.getWorld() == null ? "" : loc.getWorld().getName(),
                        "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ(),
                        "ping", p.getPing(),
                        "health", Math.round(p.getHealth() * 10) / 10.0,
                        "food", p.getFoodLevel(),
                        "level", p.getLevel(),
                        "gamemode", p.getGameMode().name(),
                        "op", p.isOp(),
                        "flying", p.isFlying(),
                        "ip", p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress());
                api().players().get(p.getUniqueId()).ifPresent(r -> {
                    m.put("firstJoin", r.firstJoin());
                    m.put("lastJoin", r.lastJoin());
                    m.put("playTime", r.playTimeMillis());
                });
                out.add(m);
            }
            return out;
        });
        ctx.page(list.size(), list);
    }

    private static final Map<String, String> PLAYER_SORT = Map.of(
            "name", "name_lower", "firstJoin", "first_join", "lastJoin", "last_join",
            "lastQuit", "last_quit", "playTime", "play_time", "lastIp", "last_ip");

    private void historyPlayers(com.lunarboreal.api.web.WebContext ctx) throws Exception {
        Database db = api().database();
        String table = db.table("players");
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
        String q = ctx.query("q", "").trim();
        String sort = PLAYER_SORT.getOrDefault(ctx.query("sort", "lastJoin"), "last_join");
        String order = "asc".equalsIgnoreCase(ctx.query("order", "desc")) ? "ASC" : "DESC";
        String where = "";
        List<Object> params = new ArrayList<>();
        if (!q.isEmpty()) {
            where = " WHERE name_lower LIKE ? OR uuid LIKE ? OR last_ip LIKE ?";
            String like = "%" + q.toLowerCase(Locale.ROOT).replace("%", "") + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        long total = db.queryOne("SELECT COUNT(*) FROM " + table + where, rs -> rs.getLong(1), params.toArray()).orElse(0L);
        List<Object> p2 = new ArrayList<>(params);
        p2.add(size);
        p2.add((page - 1) * size);
        PlayerCache cache = api().players();
        Set<UUID> online = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        List<Map<String, Object>> items = db.query("SELECT uuid, name, first_join, last_join, last_quit, last_ip, play_time FROM "
                + table + where + " ORDER BY " + sort + " " + order + " LIMIT ? OFFSET ?", rs -> {
            String uuid = rs.getString(1);
            Map<String, Object> m = Json.of("uuid", uuid, "name", rs.getString(2), "firstJoin", rs.getLong(3),
                    "lastJoin", rs.getLong(4), "lastQuit", rs.getLong(5), "lastIp", rs.getString(6),
                    "playTime", rs.getLong(7), "online", false);
            return m;
        }, p2.toArray());
        for (Map<String, Object> m : items) {
            try {
                UUID id = UUID.fromString(String.valueOf(m.get("uuid")));
                if (online.contains(id)) {
                    m.put("online", true);
                    cache.get(id).ifPresent(r -> m.put("playTime", r.playTimeMillis()));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        ctx.page(total, items);
    }

    private Player requireOnline(com.lunarboreal.api.web.WebContext ctx) {
        String id = ctx.pathParam("uuid");
        Player p;
        try {
            p = Bukkit.getPlayer(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            p = Bukkit.getPlayerExact(id);
        }
        if (p == null) throw HttpException.notFound("玩家不在线");
        return p;
    }

    private void kick(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String reason = ctx.fieldOrRow("reason");
        String name = ctx.sync(() -> {
            Player p = requireOnline(ctx);
            String r = reason == null || reason.isBlank() ? "你已被管理员踢出服务器" : reason;
            p.kick(api().messages().parse(r));
            return p.getName();
        });
        web.audit(ctx, "player.kick", name + "：" + (reason == null ? "" : reason));
        ctx.ok("已踢出 " + name);
    }

    private void message(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String text = ctx.required("message");
        String name = ctx.sync(() -> {
            Player p = requireOnline(ctx);
            p.sendMessage(api().messages().parse(text));
            return p.getName();
        });
        web.audit(ctx, "player.message", name + "：" + text);
        ctx.ok("已发送给 " + name);
    }

    private void playerCommand(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String cmd = stripSlash(ctx.required("command"));
        boolean asConsole = Json.bool(ctx.bodyMap(), "asConsole", false);
        // 以玩家身份执行会继承该玩家（可能是 OP）的全部权限，与控制台执行同样危险，统一要求 core.console.execute
        if (!ctx.user().hasPermission("core.console.execute")) {
            throw HttpException.forbidden("执行命令需要 core.console.execute 权限");
        }
        boolean superUser = ctx.userImpl().isSuper();
        String name = ctx.sync(() -> {
            Player p = requireOnline(ctx);
            if (!asConsole && !superUser && (p.isOp() || p.hasPermission("lbr.admin"))) {
                throw HttpException.forbidden("目标玩家是 OP/管理员，只有 Web 管理员可以代其执行命令");
            }
            if (asConsole) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()).replace("%player%", p.getName()));
            } else {
                p.performCommand(cmd);
            }
            return p.getName();
        });
        web.audit(ctx, "player.command", name + (asConsole ? " [控制台] " : " [玩家] ") + cmd);
        ctx.ok("已执行");
    }

    private static String stripSlash(String cmd) {
        String s = cmd.trim();
        while (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) throw HttpException.badRequest("命令不能为空");
        return s;
    }

    // =================================================================== console

    private void exec(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        // 注意：core.console.execute 等同于服务器完全控制权（可执行任意控制台命令），只应授予可信管理员
        String cmd = stripSlash(ctx.required("command"));
        web.audit(ctx, "console.exec", cmd);
        boolean ok = ctx.sync(() -> {
            plugin.getLogger().info("[Web] " + ctx.user().username() + " 执行控制台命令：/" + cmd);
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        });
        ctx.json(Json.of("ok", ok, "message", ok ? "已执行" : "未知命令或执行失败"));
    }

    // =================================================================== modules

    private void modules(com.lunarboreal.api.web.WebContext ctx) {
        ModuleManager mm = api().modules();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (ModuleDescription d : mm.discovered()) out.put(d.id(), describe(d, false, mm.isEnabled(d.id())));
        for (LbrModule m : mm.loaded()) {
            Map<String, Object> e = describe(m.description(), true, m.isEnabled());
            out.put(m.id(), e);
        }
        List<Map<String, Object>> list = new ArrayList<>(out.values());
        list.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
        ctx.page(list.size(), list);
    }

    private static Map<String, Object> describe(ModuleDescription d, boolean loaded, boolean enabled) {
        return Json.of("id", d.id(), "name", d.name(), "version", d.version(), "description", d.description(),
                "authors", d.authors(), "depends", d.depends(), "softDepends", d.softDepends(),
                "main", d.main(), "loaded", loaded, "enabled", enabled);
    }

    private void moduleOp(com.lunarboreal.api.web.WebContext c, String op) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String id = ctx.pathParam("id");
        ModuleManager mm = api().modules();
        boolean known = mm.discovered().stream().anyMatch(d -> d.id().equalsIgnoreCase(id)) || mm.get(id).isPresent();
        if (!known) throw HttpException.notFound("模块不存在：" + id);
        boolean ok = ctx.sync(() -> switch (op) {
            case "enable" -> mm.enable(id);
            case "disable" -> mm.disable(id);
            default -> mm.reload(id);
        });
        String label = switch (op) {
            case "enable" -> "启用";
            case "disable" -> "禁用";
            default -> "重载";
        };
        web.audit(ctx, "module." + op, id + (ok ? "" : "（失败）"));
        if (!ok) throw HttpException.badRequest(label + "模块 " + id + " 失败，请查看控制台日志");
        ctx.ok("已" + label + "模块 " + id);
    }

    // =================================================================== plugins

    @SuppressWarnings("deprecation")
    private void plugins(com.lunarboreal.api.web.WebContext ctx) throws Exception {
        List<Map<String, Object>> list = ctx.sync(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                var d = p.getDescription();
                out.add(Json.of("name", p.getName(), "version", d.getVersion(), "enabled", p.isEnabled(),
                        "authors", d.getAuthors(), "description", d.getDescription(), "website", d.getWebsite(),
                        "depend", d.getDepend(), "softDepend", d.getSoftDepend(), "main", d.getMain(),
                        "apiVersion", d.getAPIVersion()));
            }
            out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
            return out;
        });
        ctx.page(list.size(), list);
    }

    // =================================================================== audit

    private void audit(com.lunarboreal.api.web.WebContext ctx) throws Exception {
        Database db = api().database();
        String table = db.table("audit");
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
        String q = ctx.query("q", "").trim();
        String module = ctx.query("module", "").trim();
        List<String> conds = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (!module.isEmpty()) {
            conds.add("module = ?");
            params.add(module);
        }
        if (!q.isEmpty()) {
            conds.add("(actor LIKE ? OR action LIKE ? OR detail LIKE ?)");
            String like = "%" + q.replace("%", "") + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String where = conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
        String order = "asc".equalsIgnoreCase(ctx.query("order", "desc")) ? "ASC" : "DESC";
        long total = db.queryOne("SELECT COUNT(*) FROM " + table + where, rs -> rs.getLong(1), params.toArray()).orElse(0L);
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> items = db.query("SELECT id, time, module, actor, action, detail FROM " + table + where
                + " ORDER BY id " + order + " LIMIT ? OFFSET ?", rs -> Json.of("id", rs.getLong(1), "time", rs.getLong(2),
                "module", rs.getString(3), "actor", rs.getString(4), "action", rs.getString(5),
                "detail", rs.getString(6)), params.toArray());
        List<String> modules = db.query("SELECT DISTINCT module FROM " + table, rs -> rs.getString(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("items", items);
        out.put("modules", modules);
        ctx.json(out);
    }

    // =================================================================== config

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    private void getConfig(com.lunarboreal.api.web.WebContext ctx) throws IOException {
        File f = configFile();
        String content = f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
        ctx.json(Json.of("content", SecretMask.mask(content), "path", "plugins/" + plugin.getName() + "/config.yml",
                "modified", f.lastModified(), "masked", SecretMask.MASK));
    }

    static String validateYaml(String content) {
        try {
            new YamlConfiguration().loadFromString(content);
            return null;
        } catch (InvalidConfigurationException e) {
            return e.getMessage();
        }
    }

    private void saveConfig(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String submitted = ctx.required("content");
        String err = validateYaml(submitted);
        if (err != null) throw HttpException.badRequest("YAML 格式错误：" + err);
        File f = configFile();
        String original = f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
        // 敏感字段仍为脱敏占位符时还原原值
        String content = SecretMask.unmask(submitted, original);
        String oldWeb = webSection(original);
        if (f.exists()) Files.copy(f.toPath(), new File(plugin.getDataFolder(), "config.yml.bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File tmp = new File(plugin.getDataFolder(), "config.yml.tmp");
        Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        ctx.sync(() -> {
            plugin.reloadCore();
            return null;
        });
        web.audit(ctx, "config.save", "保存核心配置 config.yml（" + content.length() + " 字符）");
        boolean webChanged = !oldWeb.equals(webSection(content));
        ctx.json(Json.of("ok", true, "message", webChanged
                ? "已保存并重载核心配置。web 段已变更，请执行 /lc web reload 使其生效"
                : "已保存并重载核心配置", "webChanged", webChanged));
    }

    private static String webSection(String content) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(content);
            var s = y.getConfigurationSection("web");
            return s == null ? "" : String.valueOf(s.getValues(true));
        } catch (Exception e) {
            return "";
        }
    }

    // =================================================================== users & roles

    private Map<String, Object> userJson(AuthManager.UserRow u) {
        AuthManager.RoleRow r = web.auth.role(u.role());
        return Json.of("username", u.username(), "role", u.role(), "roleName", r == null ? u.role() : r.display(),
                "created", u.created(), "lastLogin", u.lastLogin(), "lastIp", u.lastIp(),
                "mcUuid", u.mcUuid(), "mcName", u.mcName(), "totpEnabled", web.auth.totpEnabled(u.username()));
    }

    /**
     * 权限提升防护：非超级管理员只能授予自己拥有的具体节点（不能授予通配符、"*"），
     * 拒绝项（-node）总是允许。
     */
    static boolean canGrant(WebUserImpl actor, Collection<String> perms) {
        if (actor.isSuper()) return true;
        for (String p : perms) {
            if (p == null || p.isBlank() || p.startsWith("-")) continue;
            if (p.contains("*") || !actor.hasPermission(p)) return false;
        }
        return true;
    }

    private void checkAssignRole(WebUserImpl actor, String roleName) {
        AuthManager.RoleRow r = web.auth.role(roleName);
        if (r == null) throw HttpException.badRequest("角色不存在：" + roleName);
        if (actor.isSuper()) return;
        if (r.name().equals("admin") || !canGrant(actor, r.permissions())) {
            throw HttpException.forbidden("不能分配权限高于自己的角色：" + r.display());
        }
    }

    private void checkManageUser(WebUserImpl actor, AuthManager.UserRow target) {
        if (actor.isSuper()) return;
        AuthManager.RoleRow r = web.auth.role(target.role());
        if (target.role().equalsIgnoreCase("admin") || (r != null && !canGrant(actor, r.permissions()))) {
            throw HttpException.forbidden("不能管理权限高于自己的账号");
        }
    }

    private void listUsers(com.lunarboreal.api.web.WebContext ctx) {
        List<Map<String, Object>> list = web.auth.users().stream().map(this::userJson).toList();
        ctx.page(list.size(), list);
    }

    private void createUser(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.required("username");
        String role = ctx.optional("role");
        if (role == null || role.isBlank()) role = "viewer";
        checkAssignRole(ctx.userImpl(), role);
        AuthManager.UserRow u = web.auth.createUser(name, ctx.required("password"), role);
        web.audit(ctx, "user.add", u.username() + " 角色 " + u.role());
        ctx.ok("已创建账号 " + u.username());
    }

    private void updateUser(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.pathParam("name");
        AuthManager.UserRow target = web.auth.user(name);
        if (target == null) throw HttpException.notFound("用户不存在：" + name);
        checkManageUser(ctx.userImpl(), target);
        String role = ctx.optional("role");
        String pw = ctx.optional("password");
        List<String> changes = new ArrayList<>();
        if (role != null && !role.isBlank() && !role.equalsIgnoreCase(target.role())) {
            checkAssignRole(ctx.userImpl(), role);
            web.auth.setRole(name, role);
            changes.add("角色→" + role);
        }
        if (pw != null && !pw.isBlank()) {
            web.auth.setPassword(name, pw);
            changes.add("重置密码");
        }
        if (changes.isEmpty()) throw HttpException.badRequest("没有需要修改的内容");
        web.refreshWsUsers();
        web.audit(ctx, "user.update", name + "：" + String.join("，", changes));
        ctx.ok("已更新账号 " + name);
    }

    private void deleteUser(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.pathParam("name");
        AuthManager.UserRow u = web.auth.user(name);
        if (u == null) throw HttpException.notFound("用户不存在：" + name);
        if (u.username().equalsIgnoreCase(ctx.user().username())) throw HttpException.badRequest("不能删除自己");
        checkManageUser(ctx.userImpl(), u);
        if (u.role().equalsIgnoreCase("admin") && web.auth.countAdmins() <= 1) throw HttpException.badRequest("至少需要保留一个管理员");
        web.auth.deleteUser(name);
        web.refreshWsUsers();
        web.audit(ctx, "user.remove", u.username());
        ctx.ok("已删除账号 " + u.username());
    }

    private void resetUserTotp(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        if (!ctx.userImpl().isSuper()) throw HttpException.forbidden("仅管理员可以重置两步验证");
        AuthManager.UserRow target = web.auth.user(ctx.pathParam("name"));
        if (target == null) throw HttpException.notFound("用户不存在");
        if (target.username().equalsIgnoreCase(ctx.user().username())) throw HttpException.badRequest("请从个人设置停用两步验证");
        AuthManager.UserRow actor = web.auth.user(ctx.user().username());
        String key = "totp:" + ctx.user().username().toLowerCase(Locale.ROOT);
        if (web.auth.lockRemaining(key) > 0) throw new HttpException(429, "验证尝试过多，请稍后再试");
        if (actor == null || !AuthManager.verifyPassword(ctx.required("password"), actor.hash())) {
            web.auth.recordFailure(key);
            throw HttpException.badRequest("密码错误");
        }
        web.auth.recordSuccess(key);
        web.auth.resetTotp(target.username());
        web.refreshWsUsers();
        web.audit(ctx, "totp.reset", "重置账号 " + target.username() + " 的两步验证");
        ctx.ok("已重置两步验证，旧登录已失效");
    }

    private void listRoles(com.lunarboreal.api.web.WebContext ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AuthManager.RoleRow r : web.auth.roles()) {
            long users = web.auth.users().stream().filter(u -> u.role().equalsIgnoreCase(r.name())).count();
            list.add(Json.of("name", r.name(), "display", r.display(), "permissions", r.permissions(),
                    "builtin", r.builtin(), "users", users));
        }
        ctx.page(list.size(), list);
    }

    private void listPermissions(com.lunarboreal.api.web.WebContext ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (WebServer.PermissionEntry p : web.permissionList()) {
            list.add(Json.of("node", p.node(), "description", p.description(), "owner", p.ownerName()));
        }
        ctx.json(list);
    }

    @SuppressWarnings("unchecked")
    private void saveRole(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.pathParam("name");
        boolean creating = name == null;
        if (creating) name = ctx.required("name");
        name = name.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_\\-]{2,32}")) throw HttpException.badRequest("角色标识只能包含小写字母、数字、_-，长度 2-32");
        AuthManager.RoleRow existing = web.auth.role(name);
        if (creating && existing != null) throw HttpException.badRequest("角色已存在：" + name);
        if (!creating && existing == null) throw HttpException.notFound("角色不存在：" + name);
        if (name.equals("admin")) throw HttpException.badRequest("内置管理员角色拥有全部权限，不能修改");
        String display = ctx.optional("display");
        if (display == null || display.isBlank()) display = existing == null ? name : existing.display();
        Object permsObj = ctx.bodyMap().get("permissions");
        Set<String> perms = new LinkedHashSet<>();
        if (permsObj instanceof Collection<?> col) {
            for (Object o : col) if (o != null && !String.valueOf(o).isBlank()) perms.add(String.valueOf(o).trim());
        } else if (existing != null) {
            perms.addAll(existing.permissions());
        }
        WebUserImpl actor = ctx.userImpl();
        if (existing != null && !canGrant(actor, existing.permissions())) {
            throw HttpException.forbidden("不能修改权限高于自己的角色");
        }
        if (!canGrant(actor, perms)) {
            throw HttpException.forbidden("只能授予自己拥有的具体权限节点；通配符与 * 仅 Web 管理员可授予");
        }
        if (existing != null && actor.role().equalsIgnoreCase(name) && !actor.isSuper()) {
            throw HttpException.forbidden("不能修改自己所属的角色");
        }
        web.auth.saveRole(new AuthManager.RoleRow(name, display, perms, existing != null && existing.builtin()));
        web.refreshWsUsers();
        web.audit(ctx, creating ? "role.add" : "role.update", name + " " + perms);
        ctx.ok(creating ? "已创建角色 " + name : "已保存角色 " + name);
    }

    private void deleteRole(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.pathParam("name");
        AuthManager.RoleRow r = web.auth.role(name);
        if (r == null) throw HttpException.notFound("角色不存在：" + name);
        if (r.builtin()) throw HttpException.badRequest("内置角色不能删除");
        if (!canGrant(ctx.userImpl(), r.permissions())) throw HttpException.forbidden("不能删除权限高于自己的角色");
        if (web.auth.roleInUse(r.name())) throw HttpException.badRequest("仍有账号使用该角色，请先修改这些账号的角色");
        web.auth.deleteRole(r.name());
        web.audit(ctx, "role.remove", r.name());
        ctx.ok("已删除角色 " + r.name());
    }

    // =================================================================== server

    private void broadcast(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String text = ctx.required("message");
        ctx.sync(() -> {
            for (Player player : Bukkit.getOnlinePlayers())
                player.sendMessage(api().messages().parse(TextPreviewApi.messageSource(text, player)));
            Bukkit.getConsoleSender().sendMessage(api().messages().parse(TextPreviewApi.messageSource(text, null)));
            return null;
        });
        web.audit(ctx, "server.broadcast", text);
        ctx.ok("已广播");
    }

    private void saveWorlds(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        int n = ctx.sync(() -> {
            Bukkit.savePlayers();
            for (World w : Bukkit.getWorlds()) w.save();
            return Bukkit.getWorlds().size();
        });
        web.audit(ctx, "server.save", "保存 " + n + " 个世界");
        ctx.ok("已保存 " + n + " 个世界与玩家数据");
    }

    private void whitelist(com.lunarboreal.api.web.WebContext ctx) throws Exception {
        Map<String, Object> out = ctx.sync(() -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                list.add(Json.of("uuid", p.getUniqueId().toString(), "name", p.getName() == null ? p.getUniqueId().toString() : p.getName(),
                        "online", p.isOnline()));
            }
            list.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
            return Json.of("enabled", Bukkit.hasWhitelist(), "enforced", Bukkit.isWhitelistEnforced(),
                    "total", list.size(), "items", list);
        });
        ctx.json(out);
    }

    private void whitelistToggle(com.lunarboreal.api.web.WebContext c) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        boolean enabled = Json.bool(ctx.bodyMap(), "enabled", false);
        ctx.sync(() -> {
            Bukkit.setWhitelist(enabled);
            return null;
        });
        web.audit(ctx, "server.whitelist", enabled ? "开启白名单" : "关闭白名单");
        ctx.ok(enabled ? "白名单已开启" : "白名单已关闭");
    }

    @SuppressWarnings("deprecation")
    private void whitelistEdit(com.lunarboreal.api.web.WebContext c, boolean add) throws Exception {
        WebContextImpl ctx = (WebContextImpl) c;
        String name = ctx.fieldOrRow("name");
        if (name == null || name.isBlank()) throw HttpException.badRequest("缺少玩家名");
        String n = name.trim();
        // getOfflinePlayer(String) 可能联网查询，放在当前 HTTP 线程以外的主线程会卡顿，这里先尝试缓存
        OfflinePlayer target = null;
        try {
            target = Bukkit.getOfflinePlayer(UUID.fromString(n));
        } catch (IllegalArgumentException ignored) {
        }
        if (target == null) {
            var rec = api().players().getByName(n);
            if (rec.isPresent()) target = Bukkit.getOfflinePlayer(rec.get().uuid());
        }
        if (target == null) target = Bukkit.getOfflinePlayer(n); // 在 HTTP 线程中执行，可能联网解析 UUID
        OfflinePlayer t = target;
        ctx.sync(() -> {
            t.setWhitelisted(add);
            return null;
        });
        web.audit(ctx, add ? "server.whitelist.add" : "server.whitelist.remove", n);
        ctx.ok((add ? "已加入白名单：" : "已移出白名单：") + n);
    }
}
