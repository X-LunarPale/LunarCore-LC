package com.lunarboreal.essentials;

import com.lunarboreal.api.command.LcCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 基于 lambda 的轻量命令，减少每个命令单独建类的样板代码。
 */
public final class Cmd extends LcCommand {

    @FunctionalInterface
    public interface Exec {
        void run(CommandSender sender, String label, String[] args);
    }

    @FunctionalInterface
    public interface Tab {
        List<String> complete(CommandSender sender, String[] args);
    }

    private final EssentialsModule m;
    private final String permKey;
    private final Exec exec;
    private Tab tab;

    public Cmd(EssentialsModule m, String name, String permKey, String[] aliases, Exec exec) {
        super(name, "lc.essentials." + permKey, aliases);
        this.m = m;
        this.permKey = permKey;
        this.exec = exec;
    }

    public Cmd tab(Tab tab) {
        this.tab = tab;
        return this;
    }

    public String permKey() {
        return permKey;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        try {
            exec.run(sender, label, args);
        } catch (Usage u) {
            m.msg(sender, "usage", "usage", u.getMessage() == null || u.getMessage().isEmpty()
                    ? "/" + label + " " + usage() : u.getMessage());
        } catch (Stop ignored) {
            // 已发送提示
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (tab == null) return super.tabComplete(sender, alias, args);
        List<String> r = tab.complete(sender, args);
        return r == null ? Collections.emptyList() : r;
    }

    // ---------------- 控制流异常 ----------------

    /** 抛出则显示用法。 */
    public static final class Usage extends RuntimeException {
        public Usage() { super(null, null, false, false); }
        public Usage(String usage) { super(usage, null, false, false); }
    }

    /** 抛出则静默终止（调用方已发送消息）。 */
    public static final class Stop extends RuntimeException {
        public Stop() { super(null, null, false, false); }
    }

    // ---------------- 静态工具 ----------------

    public static List<String> players(String prefix) {
        return onlinePlayers(prefix);
    }

    public static List<String> opts(String prefix, List<String> options) {
        return filter(options, prefix);
    }

    public static Player findOnline(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        String low = name.toLowerCase(Locale.ROOT);
        Player best = null;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(low)) {
                if (best != null) return null; // 有歧义
                best = pl;
            }
        }
        return best;
    }
}
