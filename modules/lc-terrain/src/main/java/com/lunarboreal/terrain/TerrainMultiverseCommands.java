package com.lunarboreal.terrain;

import com.lunarboreal.api.world.TerrainWorldLifecycle;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TerrainMultiverseCommands implements Listener {
    private final TerrainModule module;

    TerrainMultiverseCommands(TerrainModule module) {
        this.module = module;
    }

    record Request(String operation, String world, List<String> options) {
        void validate() {
            boolean environment = false;
            for (String option : options) {
                if (option.equals("--skip-folder-check")) continue;
                if (operation.equals("import") && !environment && option.equalsIgnoreCase("normal")) {
                    environment = true;
                    continue;
                }
                throw new IllegalArgumentException("原生地形使用已保存的生成设置，不接受参数：" + option);
            }
        }
    }

    static Request request(String command) {
        if (command == null) return null;
        String text = command.strip();
        if (text.startsWith("/")) text = text.substring(1);
        String[] args = text.split("\\s+");
        if (args.length < 3 || !Set.of("mv", "multiverse", "multiverse-core:mv",
                "multiverse-core:multiverse").contains(args[0].toLowerCase(Locale.ROOT))) return null;
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (!Set.of("load", "import", "unload").contains(operation)) return null;
        return new Request(operation, args[2], List.of(args).subList(3, args.length));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerCommand(PlayerCommandPreprocessEvent event) {
        if (handles(event.getMessage())) {
            event.setCancelled(true);
            execute(event.getPlayer(), event.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void serverCommand(ServerCommandEvent event) {
        if (handles(event.getCommand())) {
            event.setCancelled(true);
            execute(event.getSender(), event.getCommand());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void remoteCommand(RemoteServerCommandEvent event) {
        serverCommand(event);
    }

    private boolean handles(String command) {
        Request request = request(command);
        if (request == null) return false;
        TerrainWorldLifecycle terrain = module.api().services().get(TerrainWorldLifecycle.class).orElse(null);
        try {
            return terrain != null && terrain.manages(request.world());
        } catch (IllegalArgumentException ambiguous) {
            return true;
        }
    }

    private void execute(CommandSender sender, String command) {
        Request request = request(command);
        if (!sender.hasPermission("multiverse.core." + request.operation())) {
            module.msg(sender, "multiverse.no-permission");
            return;
        }
        try {
            request.validate();
            TerrainWorldLifecycle terrain = module.api().services().get(TerrainWorldLifecycle.class).orElseThrow();
            if (request.operation().equals("unload")) {
                terrain.unload(request.world(), sender.getName());
                module.msg(sender, "multiverse.unloaded", "world", request.world());
            } else {
                terrain.load(request.world(), sender.getName());
                module.msg(sender, "multiverse.loading", "world", request.world());
            }
        } catch (Exception | LinkageError failure) {
            module.msg(sender, "multiverse.failed", "error",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }
}
