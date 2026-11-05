package com.lunarboreal.structures;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.structures.config.*;
import com.lunarboreal.structures.nms.WorldStructures;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class StructuresPlugin extends JavaPlugin implements Listener {
    private final ConfigStore store;
    private final StructuresConfig active;
    private final Map<String, String> worldErrors = new LinkedHashMap<>();

    public StructuresPlugin(ConfigStore store) {
        this.store = Objects.requireNonNull(store, "Bootstrap configuration missing");
        this.active = store.saved();
    }

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new com.lunarboreal.structures.end.EndIslandController(), this);
        var pharaohs = new com.lunarboreal.structures.nms.PharaohRuntime();
        Bukkit.getPluginManager().registerEvents(pharaohs, this);
        Bukkit.getScheduler().runTaskTimer(this, pharaohs::tickPlayers, 100, 100);
        var tridents = new com.lunarboreal.structures.nms.MonumentTridents();
        Bukkit.getPluginManager().registerEvents(tridents, this);
        Bukkit.getScheduler().runTaskTimer(this, tridents::tick, 20, 100);
        // Hot-enabling cannot safely change a world's generator or re-register its codecs.
        if (!Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("Structures must load before worlds; restart Paper completely");
        var web = LC.get().web();
        web.routes("structures", this, routes -> routes
                .get("/status", "core.server.manage", c -> c.json(c.sync(this::status)))
                .get("/config", "core.server.manage", c -> c.json(store.saved().map()))
                .post("/config", "core.server.manage", c -> {
                    try {
                        var replacement = StructuresConfig.parse(c.bodyMap());
                        var saved = store.save(replacement);
                        LC.get().audit().log("structures", "web:" + c.user().username(), "configure", "revision=" + saved.revision());
                        c.json(Map.of("ok", true, "saved", saved.map(), "restartRequired", !saved.sameRules(active)));
                    } catch (IllegalArgumentException e) { throw HttpException.badRequest(e.getMessage()); }
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void worldInit(WorldInitEvent event) {
        var world = event.getWorld();
        try { WorldStructures.install(world, active.forWorld(world.getKey().toString())); }
        catch (RuntimeException error) {
            worldErrors.put(world.getKey().toString(), error.getMessage());
            getLogger().severe("Cannot initialize structure generation for " + world.getKey() + ": " + error);
            // A failed adapter must not silently generate the wrong terrain.
            Bukkit.shutdown();
            throw error;
        }
    }

    private Map<String, Object> status() {
        var worlds = new ArrayList<Map<String, Object>>();
        Bukkit.getWorlds().forEach(world -> {
                    boolean supported = WorldStructures.supported(world);
                    worlds.add(Map.of("key", world.getKey().toString(), "name", world.getName(),
                            "environment", world.getEnvironment().name(), "supported", supported,
                            "active", (supported ? active.forWorld(world.getKey().toString()) : GenerationPolicy.vanilla()).map(),
                            "error", worldErrors.getOrDefault(world.getKey().toString(), "")));
                });
        var families = Arrays.stream(StructureFamily.values()).map(f -> Map.of("id", f.id, "label", f.label)).toList();
        var saved = store.saved();
        return Map.of("active", active.map(), "saved", saved.map(), "restartRequired", !saved.sameRules(active),
                "worlds", worlds, "families", families);
    }

    @Override public void onDisable() {
        com.lunarboreal.structures.nms.PharaohRuntime.clear();
        if (LC.isLoaded()) LC.get().web().unregisterAll(this);
    }
}
