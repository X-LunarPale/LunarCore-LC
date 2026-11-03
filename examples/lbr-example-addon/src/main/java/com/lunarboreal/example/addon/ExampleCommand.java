package com.lunarboreal.example.addon;

import com.lunarboreal.api.command.LbrCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /lcexample hello|click|top
 * <p>
 * LbrCommand 由 LunarCore 动态注册（命名空间前缀 "lc:"）。
 * 权限与 playerOnly 在调用 execute 前已由 core 检查。
 */
public final class ExampleCommand extends LbrCommand {

    private final ExampleAddonPlugin p;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    public ExampleCommand(ExampleAddonPlugin p) {
        super("lcexample", "lbr.example.use", "lexample", "lbrexample");
        this.p = p;
        description("LunarCore 扩展示例命令");
        usage("/lcexample <hello|click|top>");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "hello" -> p.msg(sender, "hello", "player", sender.getName());
            case "click" -> click(sender);
            case "top" -> {
                p.msg(sender, "top-header");
                int i = 0;
                for (ClickService.Entry e : p.store().top(10)) {
                    p.msg(sender, "top-entry", "rank", ++i, "player", e.name(), "points", e.points());
                }
            }
            default -> p.msg(sender, "usage");
        }
    }

    private void click(CommandSender sender) {
        if (!(sender instanceof Player pl)) {
            p.msg(sender, "usage");
            return;
        }
        long now = System.currentTimeMillis();
        long cd = p.getConfig().getLong("cooldown-seconds", 2) * 1000L;
        Long last = cooldown.get(pl.getUniqueId());
        if (last != null && now - last < cd) {
            p.msg(pl, "cooldown", "seconds", (cd - (now - last) + 999) / 1000);
            return;
        }
        cooldown.put(pl.getUniqueId(), now);
        long add = p.getConfig().getLong("points-per-click", 1);
        long points = p.store().add(pl.getUniqueId(), pl.getName(), add);
        p.msg(pl, "click", "points", points);

        // 实时推送给 Web 面板（订阅了 "example.click" 的浏览器会立即收到）
        p.api().web().broadcast("example.click", Map.of(
                "player", pl.getName(), "uuid", pl.getUniqueId().toString(), "points", points, "time", now));

        long every = p.getConfig().getLong("announce-every", 100);
        if (every > 0 && points % every == 0) {
            for (Player o : Bukkit.getOnlinePlayers()) p.msg(o, "announce", "player", pl.getName(), "points", points);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("hello", "click", "top"), args[0]);
        return List.of();
    }
}
