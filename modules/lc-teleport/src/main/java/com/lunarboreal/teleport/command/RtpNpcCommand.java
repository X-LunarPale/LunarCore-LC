package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.RtpManager;
import com.lunarboreal.teleport.TeleportModule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Console action for NPC random teleports. */
public final class RtpNpcCommand {

    private RtpNpcCommand() {}

    public static void register(TeleportModule module) {
        Set<UUID> searching = ConcurrentHashMap.newKeySet();
        module.registerCommand(new SimpleCommand("rtpnpc", "lc.teleport.rtp.npc", (sender, label, args) -> {
            if (args.length < 2 || args.length > 3) {
                module.msg(sender, "usage.rtpnpc");
                return;
            }
            int radius = 1000;
            if (args.length == 3) {
                try {
                    radius = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    module.msg(sender, "rtp.invalid-radius", "max", RtpManager.MAX_RADIUS);
                    return;
                }
                if (radius < 1 || radius > RtpManager.MAX_RADIUS) {
                    module.msg(sender, "rtp.invalid-radius", "max", RtpManager.MAX_RADIUS);
                    return;
                }
            }
            World world = world(args[0]);
            if (world == null) {
                module.msg(sender, "common.world-not-found", "world", args[0]);
                return;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null || !player.isOnline() || !module.canSee(sender, player)) {
                module.msg(sender, "common.player-not-found", "player", args[1]);
                return;
            }
            if (searching.contains(player.getUniqueId())) {
                module.msg(player, "rtp.searching");
                return;
            }
            int distance = radius;
            module.teleports().submit(module.teleports().request(player, "rtp")
                    .warmup(false)
                    .charge(false)
                    .toAsync(() -> {
                        module.msg(player, "rtp.searching");
                        UUID id = player.getUniqueId();
                        searching.add(id);
                        try {
                            return module.rtp().findCircle(world, 0, 0, distance)
                                    .whenComplete((location, error) -> searching.remove(id));
                        } catch (RuntimeException error) {
                            searching.remove(id);
                            throw error;
                        }
                    })
                    .targetFail("rtp.failed")
                    .success(null)
                    .onSuccess(location -> {
                        module.msg(player, "rtp.success", "world", world.getName(),
                                "x", location.getBlockX(), "y", location.getBlockY(), "z", location.getBlockZ());
                        module.audit(module.actor(sender), "rtp-npc",
                                player.getName() + " @ " + world.getName() + " radius=" + distance);
                    }));
        }).tab((sender, args) -> {
            if (args.length == 1) {
                return SimpleCommand.filterList(Bukkit.getWorlds().stream().map(World::getName).toList(), args[0]);
            }
            if (args.length == 2) return SimpleCommand.players(args[1]);
            if (args.length == 3) return SimpleCommand.filterList(List.of("1000", "2000", "5000"), args[2]);
            return List.of();
        }).usage("/rtpnpc <世界> <玩家> [半径]").description("NPC 随机传送"));
    }

    private static World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null && name.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(name);
            if (key != null) world = Bukkit.getWorld(key);
        }
        return world;
    }
}
