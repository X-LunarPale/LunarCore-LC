package com.lunarboreal.examples.hello;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最小 LC 模块示例（id: hello）。
 * <p>
 * 通过 LcModule 的快捷方法注册的监听器、命令、任务、Web 路由/面板、服务、变量、权限，
 * 都会在模块禁用 / 热重载（/lc module reload hello）时由 core 自动清理。
 */
public final class HelloModule extends LcModule implements Listener {

    private final Map<UUID, AtomicLong> counts = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();

    @Override
    public void onEnable() {
        // 普通玩家默认可用的权限（未声明的节点 Bukkit 默认仅 OP 拥有）
        defaultPermissions("lc.hello.use");
        registerPermission("lc.hello.admin", PermissionDefault.OP, "hello 模块管理");

        registerListener(this);
        registerCommand(new HelloCommand());

        // %lc_hello_count% / %lc_hello_total%
        placeholder((player, key) -> switch (key) {
            case "count" -> player == null ? null : String.valueOf(count(player.getUniqueId()));
            case "total" -> String.valueOf(total.get());
            default -> null;
        });

        // Web：权限 + 路由 + 面板（命名空间自动为模块 ID → /api/ext/hello/...）
        webPermission("hello.view", "查看 hello 模块");
        webPermission("hello.manage", "修改 hello 模块配置");
        webRoutes(r -> r
                .get("/stats", "hello.view", ctx -> ctx.json(List.of(
                        Map.of("label", "累计问候", "value", String.valueOf(total.get()), "icon", "hand"),
                        Map.of("label", "问候过的玩家", "value", String.valueOf(counts.size()), "icon", "users"))))
                .get("/config", "hello.manage", ctx -> ctx.json(Map.of(
                        "greeting", config().getString("greeting", ""),
                        "reward", config().getDouble("reward", 0))))
                .post("/config", "hello.manage", ctx -> {
                    Map<String, Object> body = ctx.bodyMap();
                    ctx.sync(() -> {
                        config().set("greeting", String.valueOf(body.getOrDefault("greeting", "")));
                        config().set("reward", Double.parseDouble(String.valueOf(body.getOrDefault("reward", 0))));
                        saveConfig();
                        onReload();
                        return null;
                    });
                    audit("web:" + ctx.user().username(), "config", body.toString());
                    ctx.ok("已保存");
                }));
        webPanel(WebPanel.of("hello", "你好示例").icon("hand").group("扩展").order(950)
                .permission("hello.view")
                .section(new WebPanel.Stats("统计", "stats").refresh(15))
                .section(new WebPanel.Form("配置", "config", "config")
                        .field(new WebPanel.Field("greeting", "问候语", "textarea").help("MiniMessage，{player} 为玩家名"))
                        .field(new WebPanel.Field("reward", "每次奖励金币", "number"))));
    }

    @Override
    public void onDisable() {
        // 自己创建的资源（线程池、缓存）在这里释放 / 写回数据库；本示例只有内存计数，无需处理
        counts.clear();
    }

    private long count(UUID uuid) {
        AtomicLong c = counts.get(uuid);
        return c == null ? 0 : c.get();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 延迟 2 秒问候（runTaskLater 注册的任务在模块禁用时自动取消）
        Player p = e.getPlayer();
        runTaskLater(() -> { if (p.isOnline()) greet(p); }, 40L);
    }

    private void greet(Player p) {
        String greeting = config().getString("greeting", "Hello {player}").replace("{player}", p.getName());
        // 占位符 String 值默认会被转义（防玩家输入注入标签）；服主配置的可信 MiniMessage 用 rich() 包装
        msg(p, "greet", "greeting", com.lunarboreal.api.message.MessageService.rich(greeting));
    }

    private final class HelloCommand extends LcCommand {
        HelloCommand() {
            super("hello", "lc.hello.use", "hi");
            description("打个招呼");
            usage("/hello");
            playerOnly(true);
        }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            Player p = (Player) sender;
            greet(p);
            long n = counts.computeIfAbsent(p.getUniqueId(), k -> new AtomicLong()).incrementAndGet();
            total.incrementAndGet();
            msg(p, "count", "count", n);

            double reward = config().getDouble("reward", 0);
            if (reward > 0) {
                // 跨模块通信只通过 lc-api 的服务接口；每次使用时 get，不要长期持有
                api().services().get(EconomyService.class).ifPresent(eco -> {
                    var r = eco.deposit(p.getUniqueId(), BigDecimal.valueOf(reward), "hello 模块奖励");
                    if (r.success()) msg(p, "reward", "amount", eco.format(BigDecimal.valueOf(reward)));
                });
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return List.of();
        }
    }
}
