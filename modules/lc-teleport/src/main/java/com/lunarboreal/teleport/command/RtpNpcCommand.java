package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.RtpManager;
import com.lunarboreal.teleport.TeleportModule;
import com.lunarboreal.teleport.WorldReturnLocations;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NPC travel returns to a saved position before choosing a random destination. */
public final class RtpNpcCommand {

    private RtpNpcCommand() {}

    public static void register(TeleportModule module) {
        Set<UUID> searching = ConcurrentHashMap.newKeySet();
        module.registerCommand(new SimpleCommand("rtpnpc", "lc.teleport.rtp", (sender, label, args) -> {
            if (args.length < 1 || args.length > 3 || (!(sender instanceof Player) && args.length < 2)) {
                module.msg(sender, "usage.rtpnpc");
                return;
            }
            Player self = sender instanceof Player player ? player : null;
            boolean selfRadius = self != null && args.length == 2 && args[1].matches("[+-]?[0-9].*");
            String radiusText = args.length == 3 ? args[2] : selfRadius ? args[1] : null;
            int radius = 1000;
            if (radiusText != null) {
                try {
                    radius = Integer.parseInt(radiusText);
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
            String playerName = args.length == 1 || selfRadius ? self.getName() : args[1];
            if (playerName.equalsIgnoreCase("@s") && self != null) playerName = self.getName();
            if (playerName.startsWith("@")) {
                module.msg(sender, "rtp.npc-player-required");
                return;
            }
            boolean privileged = sender.hasPermission("lc.teleport.rtp.npc");
            Player player = self != null && self.getName().equalsIgnoreCase(playerName)
                    ? self : Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline() || !module.canSee(sender, player)) {
                module.msg(sender, "common.player-not-found", "player", playerName);
                return;
            }
            boolean ownTravel = self != null && self.getUniqueId().equals(player.getUniqueId());
            if (!privileged && (!ownTravel || !sender.hasPermission("lc.teleport.rtp"))) {
                module.msg(sender, "common.no-permission");
                return;
            }
            if (!privileged && module.rtp().worlds().stream().noneMatch(name -> world == world(name))) {
                module.msg(sender, "rtp.world-disabled");
                return;
            }
            if (searching.contains(player.getUniqueId())) {
                module.msg(player, "rtp.searching");
                return;
            }
            boolean enteringWorld = !world.equals(player.getWorld());
            Location previous = enteringWorld ? WorldReturnLocations.get(player, world) : null;
            if (enteringWorld && previous == null) {
                Location back = module.teleports().back(player.getUniqueId());
                if (back != null && world.equals(back.getWorld())) previous = back;
            }
            if (previous != null) {
                module.teleports().submit(module.teleports().request(player, "world-return")
                        .warmup(false).cooldown(false).charge(false)
                        .to(previous).success(null)
                        .onSuccess(location -> {
                            module.msg(player, "rtp.returned", "world", world.getName());
                            module.audit(module.actor(sender), "rtp-npc-return",
                                    player.getName() + " @ " + world.getKey());
                        }));
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
            if (args.length == 2) {
                if (sender instanceof Player && !sender.hasPermission("lc.teleport.rtp.npc"))
                    return SimpleCommand.filterList(List.of("1000", "2000", "5000"), args[1]);
                return SimpleCommand.players(args[1]);
            }
            if (args.length == 3) return SimpleCommand.filterList(List.of("1000", "2000", "5000"), args[2]);
            return List.of();
        }).usage("/rtpnpc <世界> [玩家] [半径]").description("NPC 返回或随机传送"));
    }

    private static World world(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null && name.contains(":")) {
            NamespacedKey key = NamespacedKey.fromString(name);
            if (key != null) world = Bukkit.getWorld(key);
        }
        if (world == null) {
            for (World candidate : Bukkit.getWorlds()) {
                NamespacedKey key = candidate.getKey();
                if (key != null && (name.equalsIgnoreCase(key.toString())
                        || name.equalsIgnoreCase(key.getNamespace() + "_" + key.getKey().replace('/', '_')))) {
                    return candidate;
                }
            }
        }
        return world;
    }
}
