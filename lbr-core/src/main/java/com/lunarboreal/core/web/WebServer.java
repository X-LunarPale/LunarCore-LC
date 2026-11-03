package com.lunarboreal.core.web;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebHandler;
import com.lunarboreal.api.web.WebPanel;
import com.lunarboreal.api.web.WebRegistry;
import com.lunarboreal.api.web.WebRouter;
import com.lunarboreal.core.LunarCorePlugin;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.websocket.WsConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * LunarCore 琉月 Web 管理面板服务器（Javalin）。
 * <ul>
 *   <li>构造 new WebServer(plugin)：在模块加载之前创建，此时即可接受 routes/panel 注册；</li>
 *   <li>start()：在 database 就绪后调用（读取 config 的 web 段），失败只打日志不抛出；</li>
 *   <li>stop()：插件关闭时调用；</li>
 *   <li>handleCommand / tabComplete：处理 "/lc web ..." 子命令（args 不含 "web"）。</li>
 * </ul>
 */
public class WebServer implements WebRegistry {

    record PanelEntry(String namespace, WebPanel panel, Object owner) {}

    record StaticEntry(ClassLoader loader, String root, Object owner) {}

    record PermissionEntry(String node, String description, Object owner, String ownerName) {}

    private final LunarCorePlugin plugin;
    final RouteTable routes = new RouteTable();
    private final List<PanelEntry> panels = new CopyOnWriteArrayList<>();
    private final Map<String, StaticEntry> statics = new ConcurrentHashMap<>();
    private final Map<String, PermissionEntry> permissions = new ConcurrentHashMap<>();
    final AuthManager auth = new AuthManager();
    final WsHub ws;
    final ConsoleCapture console;
    private final StaticFiles staticFiles = new StaticFiles();
    private final CoreApi coreApi;
    private final WebCommand command;

    private volatile Javalin app;
    private volatile WebConfig config;
    private volatile boolean authReady;
    private volatile List<Cidr> whitelist = List.of();
    private volatile List<Cidr> trustedProxies = List.of();

    public WebServer(LunarCorePlugin plugin) {
        this.plugin = plugin;
        this.ws = new WsHub(this::topicPermission);
        this.console = new ConsoleCapture(line -> broadcast("console", line));
        this.coreApi = new CoreApi(this, plugin);
        this.command = new WebCommand(this, plugin);
        coreApi.register();
    }

    LunarCorePlugin plugin() {
        return plugin;
    }

    WebConfig config() {
        return config;
    }

    boolean authReady() {
        return authReady;
    }

    // =================================================================== 生命周期

    public synchronized void start() {
        try {
            WebConfig cfg = WebConfig.from(plugin.getConfig());
            this.config = cfg;
            if (!cfg.enabled()) {
                plugin.getLogger().info("Web 面板已在配置中禁用（web.enabled=false）。");
                return;
            }
            String secret = cfg.jwtSecret();
            if (secret == null || secret.isBlank() || secret.length() < 16) {
                secret = AuthManager.randomSecret();
                plugin.getConfig().set("web.jwt-secret", secret);
                try {
                    plugin.saveConfig();
                } catch (Throwable t) {
                    plugin.getLogger().warning("无法写回 web.jwt-secret：" + t.getMessage());
                }
            }
            String generated = auth.init(LBR.get().database(), cfg, secret);
            authReady = true;
            if (generated != null) printAdminBanner("admin", generated, true);
            whitelist = cfg.ipWhitelist().stream().map(Cidr::parse).filter(java.util.Objects::nonNull).toList();
            trustedProxies = cfg.trustedProxies().stream().map(Cidr::parse).filter(java.util.Objects::nonNull).toList();
            ws.start();
            console.install();
            coreApi.start();
            startJavalin(cfg);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Web 面板启动失败：" + t.getMessage(), t);
        }
    }

    private void startJavalin(WebConfig cfg) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(plugin.getClass().getClassLoader());
        Javalin created = null;
        try {
            created = Javalin.create(c -> {
                c.showJavalinBanner = false;
                c.startupWatcherEnabled = false;
                c.router.contextPath = cfg.basePath().isEmpty() ? "/" : cfg.basePath();
                c.router.ignoreTrailingSlashes = true;
                c.router.treatMultipleSlashesAsSingleSlash = true;
                c.http.maxRequestSize = 8L * 1024 * 1024;
                c.jetty.defaultHost = cfg.host();
                c.jetty.defaultPort = cfg.port();
                if (cfg.httpsEnabled()) {
                    File ks = new File(plugin.getDataFolder(), cfg.keystore());
                    c.jetty.addConnector((server, httpConfig) -> {
                        SslContextFactory.Server ssl = new SslContextFactory.Server();
                        ssl.setKeyStorePath(ks.getAbsolutePath());
                        ssl.setKeyStorePassword(cfg.keystorePassword());
                        ServerConnector connector = new ServerConnector(server, ssl);
                        connector.setHost(cfg.host());
                        connector.setPort(cfg.port());
                        return connector;
                    });
                }
                c.router.mount(r -> {
                    r.before(this::beforeAll);
                    r.ws("/api/ws", this::configureWs);
                    for (HandlerType t : new HandlerType[]{HandlerType.GET, HandlerType.POST, HandlerType.PUT,
                            HandlerType.DELETE, HandlerType.PATCH}) {
                        r.addHttpHandler(t, "/api/<path>", this::dispatchApi);
                    }
                    r.addHttpHandler(HandlerType.OPTIONS, "/<path>", ctx -> ctx.status(204));
                    r.get("/ext/<path>", this::serveExtStatic);
                    r.get("/", this::serveSpa);
                    r.get("/<path>", this::serveSpa);
                });
            });
            if (cfg.httpsEnabled()) created.start();
            else created.start(cfg.host(), cfg.port());
            app = created;
            plugin.getLogger().info("Web 面板已启动：" + url());
        } catch (Throwable t) {
            app = null;
            if (created != null) {
                try { created.stop(); } catch (Throwable ignored) {}
            }
            plugin.getLogger().severe("Web 面板启动失败（端口 " + cfg.port() + " 可能被占用）：" + rootMessage(t));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public synchronized void stop() {
        Javalin a = app;
        app = null;
        if (a != null) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
            try {
                a.stop();
            } catch (Throwable t) {
                plugin.getLogger().warning("停止 Web 面板时出错：" + t.getMessage());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
        coreApi.stop();
        console.uninstall();
        ws.stop();
        staticFiles.clear();
    }

    /** 重新读取配置并重启（在异步线程调用）。 */
    synchronized void restart() {
        stop();
        start();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    static final String PASSWORD_FILE = "web-admin-password.txt";

    /**
     * 输出管理员密码。密码会注册到 {@link ConsoleCapture} 的脱敏列表，Web 控制台中显示为 ******；
     * 同时写入 plugins/LunarCore/web-admin-password.txt，提示服主查看后删除。
     */
    void printAdminBanner(String user, String password, boolean first) {
        console.addSecret(password);
        File file = new File(plugin.getDataFolder(), PASSWORD_FILE);
        boolean saved = false;
        try {
            java.nio.file.Files.writeString(file.toPath(), "LunarCore 琉月 Web 面板管理员账号\n地址：" + url()
                    + "\n账号：" + user + "\n密码：" + password + "\n生成时间：" + new java.util.Date()
                    + "\n\n请登录后立即修改密码，并删除本文件！\n", StandardCharsets.UTF_8);
            saved = true;
        } catch (Exception e) {
            plugin.getLogger().warning("无法写入 " + PASSWORD_FILE + "：" + e.getMessage());
        }
        String line = "==================================================================";
        var log = plugin.getLogger();
        log.warning(line);
        log.warning(first ? " LC Web 面板已创建初始管理员账号" : " LC Web 面板管理员密码已重置");
        log.warning("   地址：" + url());
        log.warning("   账号：" + user);
        log.warning("   密码：" + password);
        if (saved) log.warning("   已同时保存到 plugins/" + plugin.getName() + "/" + PASSWORD_FILE + "（查看后请删除）");
        log.warning(" 请登录后立即修改密码！忘记密码可在控制台执行 /lc web resetadmin");
        log.warning(line);
    }

    String url() {
        WebConfig cfg = config;
        if (cfg == null) cfg = WebConfig.from(plugin.getConfig());
        if (cfg.publicUrl() != null && !cfg.publicUrl().isBlank()) return cfg.publicUrl();
        String host = cfg.host();
        if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) {
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                host = "127.0.0.1";
            }
        }
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return (cfg.httpsEnabled() ? "https://" : "http://") + host + ":" + cfg.port() + cfg.basePath() + "/";
    }

    // =================================================================== HTTP 处理

    /**
     * 计算客户端 IP：仅当直连来源属于 web.trusted-proxies（默认 127.0.0.1、::1）时才解析 X-Forwarded-For，
     * 并从右往左取第一个不受信任的地址（客户端自行伪造的左侧值会被忽略）；只接受数字 IP 字面量。
     */
    String clientIp(Context ctx) {
        String remote = ctx.ip();
        List<Cidr> trusted = trustedProxies;
        if (remote == null || trusted.stream().noneMatch(c -> c.matches(remote))) return remote;
        String fwd = ctx.header("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String ip = parts[i].trim();
                if (!Cidr.isIpLiteral(ip)) return remote;
                if (trusted.stream().noneMatch(c -> c.matches(ip))) return ip;
            }
            return parts[0].trim();
        }
        String real = ctx.header("X-Real-IP");
        if (real != null && Cidr.isIpLiteral(real.trim())) return real.trim();
        return remote;
    }

    private void beforeAll(Context ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "SAMEORIGIN");
        ctx.header("Referrer-Policy", "same-origin");
        WebConfig cfg = config;
        if (cfg != null && !cfg.corsOrigins().isEmpty()) {
            String origin = ctx.header("Origin");
            if (origin != null && (cfg.corsOrigins().contains("*") || cfg.corsOrigins().contains(origin))) {
                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Vary", "Origin");
                ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
                ctx.header("Access-Control-Max-Age", "600");
            }
        }
        List<Cidr> wl = whitelist;
        if (!wl.isEmpty()) {
            String ip = clientIp(ctx);
            if (wl.stream().noneMatch(c -> c.matches(ip))) {
                throw new io.javalin.http.ForbiddenResponse("IP 不在白名单中");
            }
        }
    }

    /** 去掉 contextPath 后的路径，如 /api/players。 */
    private static String relativePath(Context ctx) {
        String path = ctx.path();
        String cp = ctx.contextPath();
        if (cp != null && !cp.equals("/") && path.startsWith(cp)) path = path.substring(cp.length());
        return RouteTable.normalize(path);
    }

    private void dispatchApi(Context ctx) {
        String path = relativePath(ctx);
        String method = ctx.method().name();
        RouteTable.Match m = routes.match(method, path);
        if (m == null) {
            error(ctx, 404, "接口不存在：" + path);
            return;
        }
        if (m.route() == null) {
            error(ctx, 405, "不支持的请求方法：" + method);
            return;
        }
        RouteTable.Route route = m.route();
        String ip = clientIp(ctx);
        WebUserImpl user = null;
        if (!route.publicRoute()) {
            user = authenticate(ctx);
            if (user == null) {
                error(ctx, 401, "未登录或登录已过期");
                return;
            }
            if (route.permission() != null && !user.hasPermission(route.permission())) {
                error(ctx, 403, "权限不足：需要 " + route.permission());
                return;
            }
        }
        WebContextImpl wc = new WebContextImpl(ctx, plugin, path, m.params(), user, ip);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            WebHandler h = route.handler();
            if (route.owner() instanceof LbrModule mod) {
                Thread.currentThread().setContextClassLoader(mod.moduleClassLoader());
            } else if (route.owner() instanceof Plugin p) {
                Thread.currentThread().setContextClassLoader(p.getClass().getClassLoader());
            }
            h.handle(wc);
        } catch (HttpException e) {
            error(ctx, e.status(), e.getMessage());
        } catch (IllegalArgumentException e) {
            error(ctx, 400, e.getMessage());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Web 接口 " + method + " " + path + " 出错", t);
            error(ctx, 500, "服务器内部错误，详情请查看服务器控制台日志");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    WebUserImpl authenticate(Context ctx) {
        String h = ctx.header("Authorization");
        String token = null;
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) token = h.substring(7).trim();
        // 仅接受 Authorization 头；查询参数 token 只用于 WebSocket 握手，避免进入访问日志/Referer
        return auth.verify(token);
    }

    static void error(Context ctx, int status, String message) {
        ctx.status(status).contentType("application/json; charset=utf-8")
                .result(Json.GSON.toJson(Map.of("error", message == null ? "错误" : message)));
    }

    private void serveExtStatic(Context ctx) {
        String path = relativePath(ctx); // /ext/ns/...
        String rest = path.length() > 5 ? path.substring(5) : "";
        int slash = rest.indexOf('/');
        String ns = slash < 0 ? rest : rest.substring(0, slash);
        String file = slash < 0 ? "" : rest.substring(slash + 1);
        StaticEntry entry = statics.get(ns.toLowerCase(Locale.ROOT));
        if (entry == null) {
            ctx.status(404).result("Not Found");
            return;
        }
        String safe = StaticFiles.safePath(decode(file));
        if (safe == null) {
            ctx.status(400).result("Bad Request");
            return;
        }
        if (safe.isEmpty() || !StaticFiles.looksLikeFile(safe)) safe = safe.isEmpty() ? "index.html" : safe + "/index.html";
        StaticFiles.Resource r = staticFiles.load(entry.loader(), entry.root(), safe, false);
        if (r == null) {
            ctx.status(404).result("Not Found");
            return;
        }
        ctx.contentType(r.contentType()).header("Cache-Control", "no-cache").result(r.data());
    }

    private void serveSpa(Context ctx) {
        String path = relativePath(ctx);
        if (path.startsWith("/api/") || path.equals("/api")) {
            error(ctx, 404, "接口不存在");
            return;
        }
        String safe = StaticFiles.safePath(decode(path));
        if (safe == null) {
            ctx.status(400).result("Bad Request");
            return;
        }
        ClassLoader loader = plugin.getClass().getClassLoader();
        if (!safe.isEmpty() && StaticFiles.looksLikeFile(safe) && !safe.equals("index.html")) {
            StaticFiles.Resource r = staticFiles.load(loader, "web", safe, true);
            if (r != null) {
                boolean immutable = safe.startsWith("assets/");
                ctx.contentType(r.contentType())
                        .header("Cache-Control", immutable ? "public, max-age=31536000, immutable" : "no-cache")
                        .result(r.data());
                return;
            }
            if (safe.startsWith("assets/")) {
                ctx.status(404).result("Not Found");
                return;
            }
        }
        StaticFiles.Resource index = staticFiles.load(loader, "web", "index.html", true);
        String base = config == null ? "" : config.basePath();
        String html;
        if (index == null) {
            html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>LunarCore</title></head>"
                    + "<body style=\"font-family:sans-serif;padding:40px\"><h2>LunarCore 琉月</h2>"
                    + "<p>Web 前端未打包进插件（缺少 web/index.html）。请先在 web-ui 目录执行 npm ci && npm run build 后重新构建插件。</p>"
                    + "<p>API 仍可用：<code>" + base + "/api/auth/info</code></p></body></html>";
        } else {
            html = new String(index.data(), StandardCharsets.UTF_8);
            String inject = "<base href=\"" + base + "/\"><script>window.__LBR_BASE__=" + Json.GSON.toJson(base) + ";</script>";
            int head = html.indexOf("<head>");
            html = head >= 0 ? html.substring(0, head + 6) + inject + html.substring(head + 6) : inject + html;
        }
        ctx.contentType("text/html; charset=utf-8").header("Cache-Control", "no-cache").result(html);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "\0";
        }
    }

    // =================================================================== WebSocket

    private void configureWs(WsConfig wsc) {
        wsc.onConnect(ctx -> {
            WebUserImpl user = auth.verify(ctx.queryParam("token"));
            if (user == null) {
                ctx.closeSession(4001, "unauthorized");
                return;
            }
            ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(10));
            ws.add(ctx, user);
            ws.send(ws.get(ctx), Json.of("topic", "_hello", "data", Json.of("user", user.username())));
        });
        wsc.onMessage(ctx -> {
            WsHub.Client c = ws.get(ctx);
            if (c == null) return;
            WebUserImpl current = auth.refresh(c.user);
            if (current == null) {
                ws.remove(ctx);
                ctx.closeSession(4001, "unauthorized");
                return;
            }
            c.user = current;
            Map<String, Object> msg;
            try {
                msg = Json.map(ctx.message());
            } catch (RuntimeException e) {
                return;
            }
            String op = Json.str(msg, "op");
            String topic = Json.str(msg, "topic");
            if ("ping".equals(op)) {
                ws.send(c, Json.of("topic", "_pong", "data", System.currentTimeMillis()));
            } else if ("sub".equals(op) && topic != null) {
                if (!ws.canSubscribe(c.user, topic)) {
                    ws.send(c, Json.of("topic", "_error", "data", "无权订阅 " + topic));
                    return;
                }
                c.topics.add(topic);
                if (topic.equals("console")) {
                    ws.send(c, "console", Json.of("topic", "_backlog", "data", Json.of("topic", "console", "items", console.history())));
                }
            } else if ("unsub".equals(op) && topic != null) {
                c.topics.remove(topic);
            }
        });
        wsc.onClose(ws::remove);
        wsc.onError(ws::remove);
    }

    /** 用户/角色/token 版本变更后刷新已连接 WebSocket 的权限（被删除、改密、登出的连接会被断开）。 */
    void refreshWsUsers() {
        ws.refreshUsers(auth::refresh);
    }

    /**
     * WebSocket 主题订阅所需权限：取 topic 第一段（按 "." 分割）+ ".view"，
     * 例如 ban.punish → ban.view、antixray.alert → antixray.view、chat → chat.view。
     * 特例：console → core.console.view，dashboard → core.dashboard。
     * 若该节点没有被任何模块 registerPermission 声明，则返回 "*"（仅管理员可订阅）。
     */
    String topicPermission(String topic) {
        if (topic.equals("console")) return "core.console.view";
        if (topic.equals("dashboard")) return "core.dashboard";
        int dot = topic.indexOf('.');
        String node = (dot < 0 ? topic : topic.substring(0, dot)) + ".view";
        return permissions.containsKey(node) ? node : "*";
    }

    // =================================================================== 命令

    public boolean handleCommand(CommandSender sender, String[] args) {
        return command.handle(sender, args);
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        return command.tabComplete(sender, args);
    }

    // =================================================================== WebRegistry

    @Override
    public void routes(String namespace, Object owner, Consumer<WebRouter> builder) {
        String ns = checkNamespace(namespace);
        String prefix = "/api/ext/" + ns;
        builder.accept(new WebRouter() {
            private WebRouter add(String method, String path, String permission, WebHandler handler) {
                if (handler == null) throw new IllegalArgumentException("handler 不能为空");
                String p = path == null ? "" : path.trim();
                if (!p.isEmpty() && !p.startsWith("/")) p = "/" + p;
                routes.add(method, prefix + p, permission, false, handler, owner, ns);
                return this;
            }

            @Override public WebRouter get(String path, String permission, WebHandler handler) { return add("GET", path, permission, handler); }

            @Override public WebRouter post(String path, String permission, WebHandler handler) { return add("POST", path, permission, handler); }

            @Override public WebRouter put(String path, String permission, WebHandler handler) { return add("PUT", path, permission, handler); }

            @Override public WebRouter delete(String path, String permission, WebHandler handler) { return add("DELETE", path, permission, handler); }
        });
    }

    @Override
    public void registerPanel(String namespace, WebPanel panel, Object owner) {
        String ns = checkNamespace(namespace);
        panel.namespace(ns);
        panels.removeIf(p -> p.namespace.equals(ns) && p.panel.id().equals(panel.id()));
        panels.add(new PanelEntry(ns, panel, owner));
    }

    @Override
    public void registerStatic(String namespace, ClassLoader loader, String resourceRoot, Object owner) {
        String ns = checkNamespace(namespace);
        String root = resourceRoot == null ? "" : resourceRoot.replace('\\', '/');
        while (root.startsWith("/")) root = root.substring(1);
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        statics.put(ns, new StaticEntry(loader, root, owner));
    }

    @Override
    public void registerPermission(String node, String description, Object owner) {
        if (node == null || node.isBlank()) return;
        permissions.put(node, new PermissionEntry(node, description == null ? node : description, owner, ownerName(owner)));
    }

    @Override
    public void unregisterAll(Object owner) {
        if (owner == null) return;
        routes.removeOwner(owner);
        panels.removeIf(p -> p.owner == owner);
        statics.values().removeIf(s -> s.owner == owner);
        permissions.values().removeIf(p -> p.owner == owner);
        staticFiles.clear();
    }

    @Override
    public void broadcast(String topic, Object payload) {
        if (topic == null || app == null) return;
        ws.broadcast(topic, payload);
    }

    @Override
    public boolean isRunning() {
        return app != null;
    }

    @Override
    public int port() {
        WebConfig cfg = config;
        return cfg != null ? cfg.port() : plugin.getConfig().getInt("web.port", 8765);
    }

    private static String checkNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_\\-.]{1,64}")) {
            throw new IllegalArgumentException("非法的 web 命名空间：" + namespace);
        }
        return namespace.toLowerCase(Locale.ROOT);
    }

    static String ownerName(Object owner) {
        if (owner instanceof LbrModule m) return m.id();
        if (owner instanceof Plugin p) return p.getName();
        if (owner instanceof WebServer) return "core";
        return owner == null ? "?" : owner.getClass().getSimpleName();
    }

    // =================================================================== 内部访问

    /** 用户可见的面板（按 permission 过滤、按 order 排序）。 */
    List<WebPanel> panelsFor(WebUserImpl user) {
        return panels.stream()
                .map(PanelEntry::panel)
                .filter(p -> p.permission() == null || user.hasPermission(p.permission()))
                .sorted(Comparator.<WebPanel>comparingInt(WebPanel::order).thenComparing(WebPanel::title))
                .collect(Collectors.toList());
    }

    int panelCount() {
        return panels.size();
    }

    List<PermissionEntry> permissionList() {
        List<PermissionEntry> list = new ArrayList<>(permissions.values());
        list.sort(Comparator.comparing(PermissionEntry::ownerName).thenComparing(PermissionEntry::node));
        return list;
    }

    /** 审计：actor 为 "web:<用户名>"。 */
    void audit(WebContextImpl ctx, String action, String detail) {
        try {
            String actor = ctx.user() == null ? "web:anonymous" : "web:" + ctx.user().username();
            LBR.get().audit().log("web", actor, action, detail + " (IP " + ctx.ip() + ")");
        } catch (Throwable t) {
            plugin.getLogger().warning("写入审计日志失败：" + t.getMessage());
        }
    }

    void auditSender(CommandSender sender, String action, String detail) {
        try {
            String actor = sender instanceof Player p ? p.getName() : "console";
            LBR.get().audit().log("web", actor, action, detail);
        } catch (Throwable ignored) {
        }
    }

    void runAsync(Runnable r) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
    }

    // =================================================================== CIDR

    record Cidr(byte[] network, int bits) {
        private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
        private static final java.util.regex.Pattern IPV6 = java.util.regex.Pattern.compile("^\\[?[0-9a-fA-F:.]+(%[0-9A-Za-z]+)?]?$");

        /** 只接受数字 IP 字面量（避免 InetAddress.getByName 触发 DNS 解析）。 */
        static boolean isIpLiteral(String s) {
            if (s == null || s.isEmpty() || s.length() > 64) return false;
            return IPV4.matcher(s).matches() || (s.contains(":") && IPV6.matcher(s).matches());
        }

        static Cidr parse(String s) {
            try {
                String[] parts = s.trim().split("/");
                if (!isIpLiteral(parts[0])) throw new IllegalArgumentException();
                byte[] addr = InetAddress.getByName(parts[0]).getAddress();
                int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : addr.length * 8;
                if (bits < 0 || bits > addr.length * 8) throw new IllegalArgumentException();
                return new Cidr(addr, bits);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[LunarCore] 无效的 IP/CIDR 配置项：" + s);
                return null;
            }
        }

        boolean matches(String ip) {
            if (!isIpLiteral(ip)) return false;
            try {
                byte[] a = InetAddress.getByName(ip).getAddress();
                if (a.length != network.length) {
                    // 仅 IPv4 映射的 IPv6（::ffff:a.b.c.d）才与 IPv4 规则比较
                    if (a.length == 16 && network.length == 4 && isV4Mapped(a)) a = java.util.Arrays.copyOfRange(a, 12, 16);
                    else return false;
                }
                int full = bits / 8, rem = bits % 8;
                for (int i = 0; i < full && i < a.length; i++) if (a[i] != network[i]) return false;
                if (rem == 0 || full >= a.length) return true;
                int mask = (0xFF << (8 - rem)) & 0xFF;
                return (a[full] & mask) == (network[full] & mask);
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isV4Mapped(byte[] a) {
            for (int i = 0; i < 10; i++) if (a[i] != 0) return false;
            return a[10] == (byte) 0xFF && a[11] == (byte) 0xFF;
        }
    }
}
