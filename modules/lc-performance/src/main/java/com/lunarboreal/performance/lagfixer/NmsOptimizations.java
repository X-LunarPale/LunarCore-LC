/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Original LagFixer algorithms: Copyright lychee and LagFixer contributors.
 * Upstream build151: 0dede033f7dc2950a08f8a461bfcff84f64c7d3c (nms/v26_2).
 * Modified for LunarCore: PortContext lifecycle, bounded scans and reversible state ownership.
 */
package com.lunarboreal.performance.lagfixer;

import com.lunarboreal.performance.lagfixer.nms.MobGoals;
import com.lunarboreal.performance.lagfixer.nms.VehicleOptimization;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.*;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** GPL-3.0-only: source port of LagFixer build151 v26_2 AI/vehicle algorithms to Paper 26.3.
 * Work is limited to loaded chunks. Chunk#getEntities allocates one chunk snapshot; oversized
 * snapshots are conservatively skipped, never partially retained or processed in an event handler.
 */
public final class NmsOptimizations implements Listener, AutoCloseable {
    private final PortContext c;
    private final MobGoals mobs;
    private final VehicleOptimization vehicles;
    private BukkitTask task;
    private boolean active;
    private int worldCursor,chunkCursor;
    private UUID currentWorld;
    private Chunk[] chunks=new Chunk[0];
    public NmsOptimizations(PortContext c) { this.c=c; mobs=new MobGoals(c);vehicles=new VehicleOptimization(c); }
    public void start() {
        if(active||(!c.enabled("mob-ai")&&!c.enabled("vehicle")))return;
        active=true;c.listen(this);task=c.timer(this::scan,1,1);
    }
    private int limit(String key,int fallback,int ceiling) {
        int result=fallback;
        for(String id:List.of("mob-ai","vehicle")) {
            var cfg=c.section(id);if(cfg!=null)result=Math.min(result,Math.clamp(cfg.getInt(key,fallback),1,ceiling));
        }
        return result;
    }
    private void scan() {
        if(!active)return;
        boolean ai=c.enabled("mob-ai"),vehicle=c.enabled("vehicle");
        if(!ai&&!vehicle){close();return;}
        int budget=limit("scan.entities-per-tick",128,1024);
        // Maintenance shares the same entity budget with candidate scanning.
        int maintenance=budget/4;
        int used=mobs.reconcile(maintenance)+vehicles.reconcile(maintenance);
        budget=Math.max(0,budget-used);
        int chunkBudget=limit("scan.chunks-per-tick",2,16);
        for(int i=0;i<chunkBudget&&budget>0;i++) {
            Chunk chunk=nextChunk();if(chunk==null)break;
            if(!chunk.isLoaded())continue;
            World world=chunk.getWorld();
            if(!(ai&&c.allows("mob-ai",world))&&!(vehicle&&c.allows("vehicle",world)))continue;
            Entity[] entities=chunk.getEntities();
            // Avoid an unbounded event/scan loop in farms. API snapshot allocation itself is unavoidable.
            if(entities.length>budget){c.count("nms-scan-skipped-chunk");continue;}
            for(Entity entity:entities) {
                budget--;
                if(!entity.isValid())continue;
                if(ai&&c.allows("mob-ai",world))mobs.optimize(entity);
                if(vehicle&&c.allows("vehicle",world))vehicles.optimize(entity);
            }
        }
    }
    private Chunk nextChunk() {
        if(chunkCursor<chunks.length)return chunks[chunkCursor++];
        chunks=new Chunk[0];chunkCursor=0;
        List<World> worlds=Bukkit.getWorlds();if(worlds.isEmpty())return null;
        for(int i=0;i<worlds.size();i++) {
            World world=worlds.get(Math.floorMod(worldCursor++,worlds.size()));
            if(!(c.enabled("mob-ai")&&c.allows("mob-ai",world))&&!(c.enabled("vehicle")&&c.allows("vehicle",world)))continue;
            currentWorld=world.getUID();chunks=world.getLoadedChunks();
            if(chunks.length>0)return chunks[chunkCursor++];
        }
        return null;
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void unload(EntitiesUnloadEvent e) { for(Entity entity:e.getEntities()){mobs.restore(entity);vehicles.restore(entity);} }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    public void worldUnload(WorldUnloadEvent e) {
        mobs.restoreWorld(e.getWorld());vehicles.restoreWorld(e.getWorld());
        if(e.getWorld().getUID().equals(currentWorld)){chunks=new Chunk[0];chunkCursor=0;}
    }
    @Override public void close() { active=false;if(task!=null)task.cancel();HandlerList.unregisterAll(this);chunks=new Chunk[0];chunkCursor=0;mobs.close();vehicles.close(); }
}
