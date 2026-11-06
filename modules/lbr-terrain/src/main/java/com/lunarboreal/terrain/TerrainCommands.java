package com.lunarboreal.terrain;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.terrain.nms.TerrainQuery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Native terrain diagnostics and the upstream eight terrain-region locators. */
final class TerrainCommands extends LbrCommand {
    private final TerrainModule module;
    private final Set<String> searching = new HashSet<>();
    private volatile boolean closed;

    TerrainCommands(TerrainModule module) {
        super("tectonic", "lbr.terrain.command");
        this.module = module;
        description("Tectonic 地形调试与查找");
        usage("/tectonic debug [世界] | /tectonic locate <地形> [世界]");
    }

    void close() {
        closed = true;
        searching.clear();
    }

    @Override public void execute(CommandSender sender, String label, String[] args) {
        if (closed) return;
        if (args.length == 0) { module.msg(sender, "command.usage"); return; }
        String operation = args[0].toLowerCase(Locale.ROOT);
        boolean locate = operation.equals("locate");
        if ((!locate && !operation.equals("debug")) || args.length < (locate ? 2 : 1)
                || args.length > (locate ? 3 : 2)) {
            module.msg(sender, "command.usage");
            return;
        }
        String requestedWorld = args.length == (locate ? 3 : 2) ? args[args.length - 1] : null;
        Location origin = location(sender, requestedWorld);
        if (origin == null) { module.msg(sender, "command.world-required"); return; }
        World world = origin.getWorld();
        TerrainOptions options = module.optionsFor(world);
        var level = ((CraftWorld) world).getHandle();
        if (options == null || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            module.msg(sender, "command.not-terrain");
            return;
        }
        TerrainTargets.Target target;
        try {
            target = locate ? TerrainTargets.find(args[1].toLowerCase(Locale.ROOT)) : null;
        } catch (IllegalArgumentException e) {
            module.msg(sender, "command.unknown-target", "targets", String.join(", ", TerrainTargets.names()));
            return;
        }
        if (target != null && !target.enabled(options)) {
            module.msg(sender, "command.disabled-target", "target", target.id());
            return;
        }
        String actor = sender instanceof Player player ? player.getUniqueId().toString() : sender.getName();
        if (!searching.add(actor)) { module.msg(sender, "command.busy"); return; }
        var registries = level.registryAccess();
        var settings = generator.generatorSettings().value();
        long seed = level.getSeed();
        UUID worldId = world.getUID();
        String worldName = world.getName();
        String worldKey = world.getKey().toString();
        if (locate) module.msg(sender, "command.searching", "target", target.id());
        module.runAsync(() -> {
            try {
                var random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
                TerrainQuery query = locate ? TerrainQuery.locator(registries, random, options, target)
                        : TerrainQuery.debugger(registries, random, options);
                if (locate) {
                    var found = query.locate(target, origin.getBlockX(), origin.getBlockZ(),
                            () -> closed || Thread.currentThread().isInterrupted());
                    complete(actor, () -> {
                        if (!currentWorld(sender, worldId)) return;
                        if (found.isEmpty()) { module.msg(sender, "command.not-found", "target", target.id()); return; }
                        var position = found.orElseThrow();
                        int distance = (int) Math.hypot((double) position.x() - origin.getBlockX(),
                                (double) position.z() - origin.getBlockZ());
                        module.msg(sender, "command.found", "target", target.id(), "world", worldName,
                                "distance", distance);
                        sender.sendMessage(Component.text("[" + position.x() + ", ~, " + position.z() + "]",
                                        NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.suggestCommand("/execute in " + worldKey
                                        + " run tp @s " + position.x() + " ~ " + position.z()))
                                .hoverEvent(HoverEvent.showText(Component.text(
                                        module.raw("command.coordinates-hover")))));
                    });
                } else {
                    var debug = query.debug(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
                    complete(actor, () -> {
                        if (!currentWorld(sender, worldId)) return;
                        module.msg(sender, "command.debug", "world", worldName, "region", debug.region(),
                                "erosion", debug.erosion(), "selector", debug.selector(), "cutoff", debug.depthCutoff());
                    });
                }
            } catch (Exception | LinkageError e) {
                if (closed || !module.isEnabled()) return;
                complete(actor, () -> {
                    if (!currentWorld(sender, worldId)) return;
                    module.msg(sender, "command.failed", "error",
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                });
            }
        });
    }

    private void complete(String actor, Runnable reply) {
        if (closed) return;
        module.runTask(() -> {
            searching.remove(actor);
            if (!closed && module.isEnabled()) reply.run();
        });
    }

    private Location location(CommandSender sender, String requestedWorld) {
        Location source = sender instanceof Entity entity ? entity.getLocation()
                : sender instanceof BlockCommandSender command ? command.getBlock().getLocation() : null;
        if (requestedWorld == null) return source;
        World world = Bukkit.getWorld(requestedWorld);
        if (world == null) {
            var key = NamespacedKey.fromString(requestedWorld.contains(":")
                    ? requestedWorld : "lbr_terrain:" + requestedWorld);
            if (key != null) world = Bukkit.getWorld(key);
        }
        if (world == null) return null;
        return source != null && source.getWorld().equals(world) ? source : world.getSpawnLocation();
    }

    private boolean currentWorld(CommandSender sender, UUID worldId) {
        if (!module.isEnabled() || Bukkit.getWorld(worldId) == null) return false;
        return !(sender instanceof Player player) || player.isOnline();
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("debug", "locate"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("locate")) return filter(TerrainTargets.names(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")
                || args.length == 3 && args[0].equalsIgnoreCase("locate")) {
            return filter(Bukkit.getWorlds().stream().filter(world -> module.optionsFor(world) != null)
                    .map(world -> world.getKey().toString()).toList(), args[args.length - 1]);
        }
        return List.of();
    }
}
