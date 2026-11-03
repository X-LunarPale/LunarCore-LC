package com.lunarboreal.api.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * LC 命令基类。core 动态注册 Bukkit 命令，命名空间前缀为 "lc"。
 * <p>
 * override = true 时会抢占同名的原版/其他插件命令（如 /gamemode、/tp）。
 */
public abstract class LbrCommand {

    private final String name;
    private final List<String> aliases;
    private final String permission;
    private String description = "";
    private String usage = "";
    private boolean override = false;
    private boolean playerOnly = false;

    protected LbrCommand(String name, String permission, String... aliases) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.permission = permission;
        this.aliases = List.of(aliases);
    }

    /** 执行命令。权限与 playerOnly 已由 core 预先检查。 */
    public abstract void execute(CommandSender sender, String label, String[] args);

    /** Tab 补全，默认补全在线玩家名。 */
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 0) return Collections.emptyList();
        return onlinePlayers(args[args.length - 1]);
    }

    // ---------------- 配置链式方法 ----------------

    public LbrCommand description(String description) { this.description = description; return this; }

    public LbrCommand usage(String usage) { this.usage = usage; return this; }

    public LbrCommand override(boolean override) { this.override = override; return this; }

    public LbrCommand playerOnly(boolean playerOnly) { this.playerOnly = playerOnly; return this; }

    public String name() { return name; }

    public List<String> aliases() { return aliases; }

    /** 可为 null 表示不需要权限。 */
    public String permission() { return permission; }

    public String description() { return description; }

    public String usage() { return usage; }

    public boolean isOverride() { return override; }

    public boolean isPlayerOnly() { return playerOnly; }

    // ---------------- 工具方法 ----------------

    public static List<String> onlinePlayers(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }

    public static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
