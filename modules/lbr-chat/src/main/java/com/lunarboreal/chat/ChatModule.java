package com.lunarboreal.chat;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.BanService;
import com.lunarboreal.api.service.EssentialsService;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebPanel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** 聊天与社交模块入口。 */
public final class ChatModule extends LbrModule implements Listener {

    ChatStore store;
    final ChatFilter filter = new ChatFilter();
    final ChatFormatter formatter = new ChatFormatter();
    AiChatService aiChat;
    volatile boolean globalMute;
    private final AtomicInteger announceIndex = new AtomicInteger();
    private boolean logEnabled;
    private final TabList tablist = new TabList(this);
    private volatile boolean closing;

    @Override
    public void onEnable() {
        store = new ChatStore(api().database(), logger());
        try {
            store.createTables();
        } catch (SQLException e) {
            logger().log(Level.SEVERE, "创建聊天数据表失败", e);
        }
        loadSettings();
        aiChat = new AiChatService(this);

        defaultPermissions("lbr.chat.msg", "lbr.chat.reply", "lbr.chat.ignore", "lbr.chat.msgtoggle", "lbr.chat.mail",
                "lbr.chat.mail.send", "lbr.chat.motd", "lbr.chat.rules", "lbr.chat.helpop", "lbr.chat.me", "lbr.chat.realname",
                "lbr.chat.url", "lbr.chat.item");
        registerPermission("lbr.chat.color", PermissionDefault.OP, "聊天中使用 & 颜色代码");
        registerPermission("lbr.chat.socialspy", PermissionDefault.OP, "社交监听");
        registerPermission("lbr.chat.staffchat", PermissionDefault.OP, "管理频道");
        registerPermission("lbr.chat.helpop.receive", PermissionDefault.OP, "接收求助");
        registerPermission("lbr.chat.ignore.exempt", PermissionDefault.OP, "不可被屏蔽");
        registerPermission("lbr.chat.bypass.filter", PermissionDefault.OP, "绕过全部聊天过滤");
        registerPermission("lbr.chat.bypass.spam", PermissionDefault.OP, "绕过刷屏检测");
        registerPermission("lbr.chat.bypass.ad", PermissionDefault.OP, "绕过广告拦截");
        registerPermission("lbr.chat.chatmute.bypass", PermissionDefault.OP, "全服禁言时仍可发言");

        registerListener(this);
        if (api().isPaper()) {
            registerListener(new PaperChatListener(this));
        } else {
            registerListener(new BukkitChatListener(this));
        }
        ChatCommands.registerAll(this);

        // 已在线玩家（热重载）
        for (Player p : Bukkit.getOnlinePlayers()) loadUser(p, false);

        runAsyncTimer(() -> store.flushLogs(), 100L, 100L);
        runAsyncTimer(() -> store.purgeLogs(config().getInt("log.keep-days", 30)), 1200L, 72_000L);
        runAsyncTimer(store::purgeAiMemories, 1200L, 72_000L);
        startAnnouncer();
        startTablist();

        placeholder((player, key) -> {
            if (player == null) return null;
            return switch (key) {
                case "nick" -> {
                    ChatUser u = store.peek(player.getUniqueId()).orElse(null);
                    yield u == null || u.nick == null ? player.getName() : ChatFormatter.plain(ChatFormatter.nick(u.nick));
                }
                case "mail_unread" -> {
                    try { yield String.valueOf(store.unread(player.getUniqueId())); } catch (SQLException e) { yield "0"; }
                }
                default -> null;
            };
        });

        registerWeb();
    }

    @Override
    public synchronized void onDisable() {
        closing = true;
        if (aiChat != null) aiChat.stop();
        tablist.stop();
        if (store != null) store.flushLogs();
    }

    @Override
    public void onReload() {
        super.onReload();
        loadSettings();
        if (aiChat != null) aiChat.reload();
        tablist.restart();
    }

    void loadSettings() {
        filter.load(config().getConfigurationSection("anti-spam"));
        formatter.load(config().getConfigurationSection("format"));
        logEnabled = config().getBoolean("log.enabled", true);
    }

    // ---------------- 公共工具 ----------------

    ChatUser user(Player p) {
        return store.cached(p.getUniqueId());
    }

    Map<String, String> vars(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }

    boolean isMuted(Player p) {
        return api().services().get(BanService.class).map(b -> b.isMuted(p.getUniqueId())).orElse(false);
    }

    boolean isVanished(Player p) {
        return api().services().get(EssentialsService.class).map(e -> e.isVanished(p.getUniqueId())).orElse(false);
    }

    boolean canSee(CommandSender viewer, Player target) {
        return api().services().get(EssentialsService.class).map(e -> e.canSee(viewer, target)).orElse(true);
    }

    /** 查找可见的在线玩家（精确名或前缀，隐身玩家对无权限者不可见）。 */
    Player findVisible(CommandSender viewer, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        Player p = exact != null ? exact : Bukkit.getPlayer(name);
        if (p == null || !canSee(viewer, p)) return null;
        return p;
    }

    void logChat(Player p, String channel, String message) {
        logChat(p == null ? null : p.getUniqueId(), p == null ? "CONSOLE" : p.getName(), channel, message);
    }

    void logVirtualChat(String name, String channel, String message) {
        logChat(null, name, channel, message);
    }

    private void logChat(UUID id, String name, String channel, String message) {
        if (logEnabled) store.queueLog(id, name, channel, message);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("time", System.currentTimeMillis());
            payload.put("player", name);
            payload.put("channel", channel);
            payload.put("message", message);
            api().web().broadcast("chat", payload);
        } catch (RuntimeException ignored) {
            // web 未启动
        }
    }

    void applyNick(Player p, ChatUser u) {
        Component display;
        if (u.nick == null || u.nick.isBlank()) {
            display = Component.text(p.getName());
        } else {
            String prefix = config().getString("nick.prefix", "");
            display = ChatFormatter.MM.deserialize(prefix == null ? "" : prefix).append(ChatFormatter.nick(u.nick));
        }
        p.displayName(display);
        if (config().getBoolean("tablist.enabled", true)) tablist.refresh();
    }

    void sendMentionSounds(List<Player> mentioned) {
        if (mentioned.isEmpty() || !config().getBoolean("format.mentions.sound", true)) return;
        runTask(() -> mentioned.forEach(m -> { if (m.isOnline()) m.playSound(m.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f); }));
    }

    void runCommands(List<String> commands) {
        if (commands.isEmpty()) return;
        runTask(() -> commands.forEach(c -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c)));
    }

    // ---------------- 加入/退出 ----------------

    private void loadUser(Player p, boolean join) {
        UUID id = p.getUniqueId();
        runAsync(() -> {
            try {
                ChatUser u = store.load(id);
                int unread = store.unread(id);
                synchronized (this) {
                    if (closing) return;
                    runTask(() -> {
                        if (closing || !p.isOnline()) return;
                        applyNick(p, u);
                        if (join && unread > 0) msg(p, "mail.unread", "count", unread);
                    });
                }
            } catch (SQLException e) {
                logger().warning("加载 " + p.getName() + " 的聊天数据失败: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadUser(p, true);
        if (config().getBoolean("join-quit.enabled", true) && e.joinMessage() != null) {
            String silent = config().getString("join-quit.silent-permission", "lbr.chat.silentjoin");
            if (isVanished(p) || (!silent.isEmpty() && p.hasPermission(silent) && !p.isOp())) {
                e.joinMessage(null);
            } else if (!p.hasPlayedBefore() && !config().getString("join-quit.first-join", "").isEmpty()) {
                int count = Bukkit.getOfflinePlayers().length;
                e.joinMessage(ChatFormatter.render(p, config().getString("join-quit.first-join"), vars("player", p.getName(), "count", count)));
            } else {
                e.joinMessage(ChatFormatter.render(p, config().getString("join-quit.join", ""), vars("player", p.getName())));
            }
        }
        if (config().getBoolean("motd.enabled", true)) {
            runTaskLater(() -> { if (p.isOnline()) sendMotd(p); }, 10L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (config().getBoolean("join-quit.enabled", true) && e.quitMessage() != null) {
            String silent = config().getString("join-quit.silent-permission", "lbr.chat.silentjoin");
            if (isVanished(p) || (!silent.isEmpty() && p.hasPermission(silent) && !p.isOp())) {
                e.quitMessage(null);
            } else {
                e.quitMessage(ChatFormatter.render(p, config().getString("join-quit.quit", ""), vars("player", p.getName())));
            }
        }
        tablist.forget(p);
        store.evict(p.getUniqueId());
        filter.forget(p.getUniqueId());
        for (ChatUser u : store.allCached()) if (p.getUniqueId().equals(u.lastMessaged)) u.lastMessaged = null;
    }

    void sendMotd(CommandSender to) {
        for (String line : config().getStringList("motd.lines")) {
            to.sendMessage(ChatFormatter.render(to instanceof Player pl ? pl : null, line,
                    vars("player", to.getName())));
        }
    }

    // ---------------- 公告与 Tab ----------------

    private void startAnnouncer() {
        ConfigurationSection a = config().getConfigurationSection("announcer");
        if (a == null || !a.getBoolean("enabled", true)) return;
        long period = Math.max(10, a.getLong("interval-seconds", 300)) * 20L;
        runTaskTimer(() -> {
            List<String> list = config().getStringList("announcer.messages");
            if (list.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
            String msg = "random".equalsIgnoreCase(config().getString("announcer.mode"))
                    ? list.get(ThreadLocalRandom.current().nextInt(list.size()))
                    : list.get(Math.floorMod(announceIndex.getAndIncrement(), list.size()));
            String prefix = config().getString("announcer.prefix", "");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(ChatFormatter.render(p, prefix + msg, vars("player", p.getName())));
            }
        }, period, period);
    }

    private void startTablist() {
        tablist.restart();
    }

    // ---------------- Web 面板 ----------------

    private void registerWeb() {
        webPermission("chat.view", "查看聊天记录");
        webPermission("chat.manage", "管理聊天（广播/禁言/配置）");
        webPermission("chat.ai.manage", "管理 AI 聊天及服务商密钥");

        webRoutes(r -> r
                .get("/stats", "chat.view", ctx -> {
                    long since = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    store.flushLogs();
                    List<Map<String, Object>> stats = new ArrayList<>();
                    stats.add(stat("今日聊天消息", store.countSince(since), "message-circle"));
                    stats.add(stat("邮件总数", store.totalMails(), "mail"));
                    stats.add(stat("全服禁言", globalMute ? "开启" : "关闭", "volume-x"));
                    ctx.json(stats);
                })
                .get("/logs", "chat.view", ctx -> {
                    store.flushLogs();
                    int page = Math.max(1, ctx.queryInt("page", 1));
                    int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
                    String q = ctx.query("q");
                    ctx.page(store.countLogs(q), store.logs(q, (page - 1) * size, size));
                })
                .post("/broadcast", "chat.manage", ctx -> {
                    Object m = ctx.bodyMap().get("message");
                    if (m == null || m.toString().isBlank()) throw HttpException.badRequest("消息不能为空");
                    String text = m.toString();
                    ctx.sync(() -> { broadcast(text); return null; });
                    audit("web:" + ctx.user().username(), "broadcast", text);
                    ctx.ok("已广播");
                })
                .post("/chatmute", "chat.manage", ctx -> {
                    globalMute = !globalMute;
                    String actor = "web:" + ctx.user().username();
                    ctx.sync(() -> { Bukkit.getOnlinePlayers().forEach(p -> msg(p, globalMute ? "chatmute.on" : "chatmute.off", "actor", actor)); return null; });
                    audit(actor, "chatmute", String.valueOf(globalMute));
                    ctx.ok(globalMute ? "已开启全服禁言" : "已关闭全服禁言");
                })
                .get("/config", "chat.manage", ctx -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("antiSpam", config().getBoolean("anti-spam.enabled"));
                    m.put("cooldownMs", config().getLong("anti-spam.cooldown-ms"));
                    m.put("antiAd", config().getBoolean("anti-spam.anti-ad.enabled"));
                    m.put("badWords", String.join("\n", config().getStringList("anti-spam.bad-words")));
                    m.put("announcer", config().getBoolean("announcer.enabled"));
                    m.put("announceInterval", config().getLong("announcer.interval-seconds"));
                    m.put("announcements", String.join("\n", config().getStringList("announcer.messages")));
                    m.put("motd", String.join("\n", config().getStringList("motd.lines")));
                    m.put("logEnabled", config().getBoolean("log.enabled"));
                    m.put("tabEnabled", config().getBoolean("tablist.enabled", true));
                    m.put("tabRefresh", config().getLong("tablist.refresh-seconds", 5));
                    m.put("tabHeader", String.join("\n", config().getStringList("tablist.header")));
                    m.put("tabFooter", String.join("\n", config().getStringList("tablist.footer")));
                    m.put("tabName", TabList.nameFormat(config()));
                    m.put("tabShowTitle", config().getBoolean("tablist.show-current-title", true));
                    m.put("defaultChatFormat", formatter.defaultFormat());
                    ctx.json(m);
                })
                .post("/config", "chat.manage", ctx -> {
                    Map<String, Object> b = ctx.bodyMap();
                    validateTabConfig(b);
                    ctx.sync(() -> {
                        setIf(b, "tabEnabled", "tablist.enabled", Boolean.class);
                        if (b.get("tabRefresh") instanceof Number n) config().set("tablist.refresh-seconds", n.longValue());
                        setLines(b, "tabHeader", "tablist.header");
                        setLines(b, "tabFooter", "tablist.footer");
                        setIf(b, "tabName", "tablist.player-name-format", String.class);
                        setIf(b, "tabShowTitle", "tablist.show-current-title", Boolean.class);
                        setIf(b, "defaultChatFormat", "format.groups.default.format", String.class);
                        setIf(b, "antiSpam", "anti-spam.enabled", Boolean.class);
                        if (b.get("cooldownMs") != null) config().set("anti-spam.cooldown-ms", ((Number) b.get("cooldownMs")).longValue());
                        setIf(b, "antiAd", "anti-spam.anti-ad.enabled", Boolean.class);
                        setLines(b, "badWords", "anti-spam.bad-words");
                        setIf(b, "announcer", "announcer.enabled", Boolean.class);
                        if (b.get("announceInterval") != null) config().set("announcer.interval-seconds", ((Number) b.get("announceInterval")).longValue());
                        setLines(b, "announcements", "announcer.messages");
                        setLines(b, "motd", "motd.lines");
                        setIf(b, "logEnabled", "log.enabled", Boolean.class);
                        saveConfig();
                        api().modules().reload(id());
                        return null;
                    });
                    audit("web:" + ctx.user().username(), "config", "更新聊天配置");
                    ctx.ok("已保存并重载聊天模块");
                })
                .get("/ai/settings", "chat.ai.manage", ctx -> ctx.json(aiChat.settings().publicView()))
                .post("/ai/settings", "chat.ai.manage", ctx -> {
                    Map<String, Object> body = ctx.bodyMap();
                    ctx.sync(() -> { aiChat.save(body); return null; });
                    audit("web:" + ctx.user().username(), "ai-settings", "更新 AI 聊天设置");
                    ctx.json(aiChat.settings().publicView());
                })
                .get("/ai/models", "chat.ai.manage", ctx -> {
                    try { ctx.json(aiChat.models(ctx.query("bot"))); }
                    catch (HttpException ex) { throw ex; }
                    catch (Exception ex) { throw new HttpException(502, "上游模型列表暂不可用（" + ex.getClass().getSimpleName() + "）"); }
                })
                .get("/ai/balance", "chat.ai.manage", ctx -> {
                    try { ctx.json(aiChat.balance(ctx.query("bot"))); }
                    catch (HttpException ex) { throw ex; }
                    catch (Exception ex) { throw new HttpException(502, "余额查询失败（" + ex.getClass().getSimpleName() + "）"); }
                })
                .post("/ai/memory/clear", "chat.ai.manage", ctx -> {
                    Map<String, Object> body = ctx.bodyMap();
                    if (body.keySet().stream().anyMatch(key -> !"playerUuid".equals(key))) throw HttpException.badRequest("未知字段");
                    UUID player = null;
                    if (body.containsKey("playerUuid")) {
                        try { player = UUID.fromString((String) body.get("playerUuid")); }
                        catch (RuntimeException ex) { throw HttpException.badRequest("玩家 UUID 无效"); }
                    }
                    int removed = store.clearAiMemories(player);
                    audit("web:" + ctx.user().username(), "ai-memory-clear", player == null ? "全部" : player.toString());
                    ctx.json(Map.of("removed", removed));
                }));

        webPanel(WebPanel.of("chat", "聊天管理").icon("message-circle").group("玩家").order(30).permission("chat.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(10))
                .section(new WebPanel.Table("聊天记录", "logs")
                        .column("time", "时间", "datetime")
                        .column("name", "玩家", "player")
                        .column("channel", "频道", "tag")
                        .column("message", "内容")
                        .toolbarAction(new WebPanel.Action("broadcast", "发送广播", "broadcast").style("primary").permission("chat.manage")
                                .field(new WebPanel.Field("message", "广播内容 (MiniMessage)", "textarea").required(true)))
                        .toolbarAction(new WebPanel.Action("chatmute", "切换全服禁言", "chatmute").style("warning").permission("chat.manage")
                                .confirm("确定切换全服禁言状态吗？")))
                .section(new WebPanel.Form("聊天设置", "config", "config")
                        .field(new WebPanel.Field("antiSpam", "启用反刷屏", "bool"))
                        .field(new WebPanel.Field("cooldownMs", "发言间隔(毫秒)", "number"))
                        .field(new WebPanel.Field("antiAd", "广告拦截", "bool"))
                        .field(new WebPanel.Field("badWords", "敏感词（每行一个）", "textarea"))
                        .field(new WebPanel.Field("announcer", "启用自动公告", "bool"))
                        .field(new WebPanel.Field("announceInterval", "公告间隔(秒)", "number"))
                        .field(new WebPanel.Field("announcements", "公告内容（每行一条，MiniMessage）", "textarea"))
                        .field(new WebPanel.Field("motd", "登录欢迎消息（每行一条）", "textarea"))
                        .field(new WebPanel.Field("logEnabled", "记录聊天到数据库", "bool"))
                        .field(new WebPanel.Field("defaultChatFormat", "默认玩家聊天格式", "text")
                                .help("支持 <displayname> 玩家显示名、<message> 消息、{player} 原名和 {world} 世界名；AI 回复沿用模板，但不会套用触发玩家的 %...% 占位符。"))
                        .field(new WebPanel.Field("tabEnabled", "启用 TAB", "bool"))
                        .field(new WebPanel.Field("tabRefresh", "TAB 刷新秒数（1–3600）", "number"))
                        .field(new WebPanel.Field("tabHeader", "玩家列表顶部文字（每行一条）", "textarea"))
                        .field(new WebPanel.Field("tabFooter", "玩家列表底部文字（每行一条）", "textarea"))
                        .field(new WebPanel.Field("tabShowTitle", "玩家名称前显示当前称号", "bool"))
                        .field(new WebPanel.Field("tabName", "玩家列表名称格式", "text").help("{title} 表示当前称号，{player} 表示玩家名称；未佩戴时不显示称号。检测到其他玩家列表插件或外部修改时让出对应显示。"))));

        webPanel(WebPanel.of("ai", "AI 聊天").icon("bot").group("玩家").order(31).permission("chat.ai.manage")
                .section(new WebPanel.Text("配置", "AI 在游戏公开聊天中以默认玩家格式回复；设置仅授权账号可见，API Key 不会返回浏览器。")));
    }

    static void validateTabConfig(Map<String, Object> body) {
        if (body.containsKey("tabEnabled") && !(body.get("tabEnabled") instanceof Boolean)) throw HttpException.badRequest("玩家列表开关必须为布尔值");
        if (body.containsKey("tabShowTitle") && !(body.get("tabShowTitle") instanceof Boolean)) throw HttpException.badRequest("称号显示开关必须为布尔值");
        if (body.containsKey("tabRefresh")) {
            Object value = body.get("tabRefresh");
            if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue() || n.longValue() < 1 || n.longValue() > 3600)
                throw HttpException.badRequest("TAB 刷新间隔必须为 1–3600 的整数");
        }
        for (String key : List.of("tabHeader", "tabFooter", "tabName")) {
            if (body.containsKey(key) && (!(body.get(key) instanceof String s) || s.length() > (key.equals("tabName") ? 1024 : 8192))) throw HttpException.badRequest(key + " 文本类型或长度无效");
        }
        if (body.containsKey("defaultChatFormat")) {
            Object value = body.get("defaultChatFormat");
            if (!(value instanceof String s) || s.isBlank() || s.length() > 1024
                    || !s.contains("<displayname>") || !s.contains("<message>")) {
                throw HttpException.badRequest("默认聊天格式需包含 <displayname> 与 <message>，长度不超过1024字符");
            }
        }
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    private void setIf(Map<String, Object> body, String key, String path, Class<?> type) {
        Object v = body.get(key);
        if (type.isInstance(v)) config().set(path, v);
    }

    private void setLines(Map<String, Object> body, String key, String path) {
        Object v = body.get(key);
        if (v == null) return;
        List<String> lines = new ArrayList<>();
        for (String s : v.toString().split("\\r?\\n")) if (!s.isBlank()) lines.add(s);
        config().set(path, lines);
    }

    void broadcast(String text) {
        Component c = ChatFormatter.render(null, config().getString("broadcast.format", "<message>"), null,
                Placeholder.component("message", ChatFormatter.legacyOrMini(text)));
        Bukkit.getServer().sendMessage(c);
        logChat(null, "broadcast", text);
    }
}
