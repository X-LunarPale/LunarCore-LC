package com.lunarboreal.teleport.command;

import com.lunarboreal.api.command.LcCommand;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/** 基于 lambda 的命令，便于集中注册大量小命令。 */
public final class SimpleCommand extends LcCommand {

    @FunctionalInterface
    public interface Exec {
        void run(CommandSender sender, String label, String[] args);
    }

    @FunctionalInterface
    public interface Tab {
        List<String> complete(CommandSender sender, String[] args);
    }

    private final Exec exec;
    private Tab tab;

    public SimpleCommand(String name, String permission, Exec exec, String... aliases) {
        super(name, permission, aliases);
        this.exec = exec;
    }

    public SimpleCommand tab(Tab tab) {
        this.tab = tab;
        return this;
    }

    /** 不做补全。 */
    public SimpleCommand noTab() {
        this.tab = (s, a) -> Collections.emptyList();
        return this;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        exec.run(sender, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (tab != null) {
            List<String> r = tab.complete(sender, args);
            return r == null ? Collections.emptyList() : r;
        }
        return super.tabComplete(sender, alias, args);
    }

    public static List<String> filterList(List<String> options, String prefix) {
        return filter(options, prefix);
    }

    public static List<String> players(String prefix) {
        return onlinePlayers(prefix);
    }
}
