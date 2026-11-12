/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Original LagFixer algorithms: Copyright lychee and LagFixer contributors.
 * Upstream build151: 0dede033f7dc2950a08f8a461bfcff84f64c7d3c (nms/v26_2/VehicleMotionReducer, VehicleWrapper).
 * Modified for LunarCore: payload-free selection, bounded tracking, NBT/UUID preservation and fresh-instance rollback.
 */
package com.lunarboreal.performance.lagfixer.nms;

import com.lunarboreal.performance.lagfixer.PortContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.boat.Raft;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.craftbukkit.entity.CraftEntity;
import java.util.*;

/** GPL-3.0-only, derived from LagFixer build151 VehicleMotionReducer/VehicleWrapper.
 * Restricts replacement to payload-free ordinary vehicles, preserves NBT and UUID, and restores on close.
 */
public final class VehicleOptimization implements AutoCloseable {
    private record Replaced(Entity wrapper,boolean silent) {}
    private final PortContext c;
    private final Map<UUID,Replaced> changed=new HashMap<>();
    private final ArrayDeque<UUID> maintenance=new ArrayDeque<>();
    private boolean replacing;
    public VehicleOptimization(PortContext c){this.c=c;}
    public void optimize(org.bukkit.entity.Entity bukkit) {
        if(replacing||changed.containsKey(bukkit.getUniqueId())||!(bukkit instanceof CraftEntity craft))return;
        Entity old=craft.getHandle();
        boolean boat=old.getClass()==Boat.class||old.getClass()==Raft.class;
        boolean minecart=old.getClass()==Minecart.class;
        if(!boat&&!minecart)return;
        var cfg=c.section("vehicle");if(cfg==null||changed.size()>=Math.clamp(cfg.getInt("scan.cache-limit",4096),1,4096)||!cfg.getBoolean(boat?"boat.enabled":"minecart.enabled",true))return;
        if(!bukkit.getPassengers().isEmpty()||bukkit.isInsideVehicle()||bukkit.customName()!=null||!bukkit.getPersistentDataContainer().isEmpty()||!bukkit.getScoreboardTags().isEmpty())return;
        Entity replacement=old.getClass()==Boat.class?new ReducedBoat((Boat)old,c):old.getClass()==Raft.class?new ReducedRaft((Raft)old,c):new ReducedMinecart((Minecart)old,c);
        boolean silent=old.isSilent();
        if(swap(old,replacement)){
            replacement.setSilent(cfg.getBoolean(boat?"boat.silent":"minecart.silent",false));
            changed.put(replacement.getUUID(),new Replaced(replacement,silent));maintenance.addLast(replacement.getUUID());c.count("vehicle");
        }
    }
    private static CompoundTag save(Entity entity) {
        TagValueOutput output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,entity.registryAccess());
        entity.saveWithoutId(output);return output.buildResult();
    }
    private static void load(Entity entity,CompoundTag data){entity.load(TagValueInput.create(ProblemReporter.DISCARDING,entity.registryAccess(),data));}
    private static Entity freshVanilla(Entity source) {
        Entity result=source.getType().create(source.level(),net.minecraft.world.entity.EntitySpawnReason.LOAD);
        if(result==null)throw new IllegalStateException("vehicle factory returned null");
        return result;
    }
    private boolean swap(Entity from,Entity to) {
        ServerLevel level=(ServerLevel)from.level();
        CompoundTag state=save(from);
        List<Entity> passengers=List.copyOf(from.getPassengers());
        // Allocate rollback before removing anything. Removed entities have retired schedulers and
        // MUST NOT be resurrected with unsetRemoved(), even when their NBT and UUID still match.
        Entity rollback=freshVanilla(from);
        load(rollback,state);
        replacing=true;
        boolean removed=false;
        try {
            load(to,state);
            if(!to.getUUID().equals(from.getUUID())||!rollback.getUUID().equals(from.getUUID()))throw new IllegalStateException("vehicle UUID changed during state copy");
            from.ejectPassengers();
            from.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);removed=true;
            if(!level.addWithUUID(to))throw new IllegalStateException("vehicle replacement rejected");
            for(Entity passenger:passengers)if(!passenger.startRiding(to,true,true))throw new IllegalStateException("passenger restoration rejected");
            return true;
        } catch(RuntimeException ex) {
            if(to.valid)to.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            Entity destination=from;
            if(removed){
                destination=rollback;
                if(!level.addWithUUID(rollback))throw new IllegalStateException("Failed to restore vehicle " +from.getUUID(),ex);
            }
            for(Entity passenger:passengers)passenger.startRiding(destination,true,true);
            c.logger().warning("Vehicle optimization rolled back to fresh vanilla instance: "+ex.getMessage());return false;
        } finally {replacing=false;}
    }
    public void restore(org.bukkit.entity.Entity e){restore(e.getUniqueId());}
    private void restore(UUID id){
        if(replacing)return;
        Replaced entry=changed.remove(id);maintenance.remove(id);if(entry==null)return;
        if(entry.wrapper.isRemoved()||!entry.wrapper.valid)return;
        Entity vanilla=freshVanilla(entry.wrapper);
        if(swap(entry.wrapper,vanilla))vanilla.setSilent(entry.silent);
        else {
            // swap already inserted a fresh vanilla rollback; restore original sound state on it too.
            org.bukkit.entity.Entity live=entry.wrapper.getBukkitEntity().getWorld().getEntity(id);
            if(live!=null)live.setSilent(entry.silent);
        }
    }
    public int reconcile(int budget){
        int used=0,count=Math.min(budget,maintenance.size());
        while(used<count){UUID id=maintenance.removeFirst();Replaced entry=changed.get(id);used++;if(entry==null)continue;
            Entity wrapper=entry.wrapper;
            if(wrapper.isRemoved()||!wrapper.valid){changed.remove(id);continue;}
            if(!c.enabled("vehicle")||!c.allows("vehicle",wrapper.getBukkitEntity().getWorld()))restore(id);else maintenance.addLast(id);
        }return used;
    }
    public void restoreWorld(org.bukkit.World world){for(var entry:List.copyOf(changed.entrySet()))if(entry.getValue().wrapper.getBukkitEntity().getWorld().equals(world))restore(entry.getKey());}
    @Override public void close(){for(UUID id:List.copyOf(changed.keySet()))restore(id);changed.clear();maintenance.clear();}
    private interface Reduced {
        PortContext context();
        String category();
        default boolean flag(String name,boolean fallback){var cfg=context().section("vehicle");return cfg==null?fallback:cfg.getBoolean(category()+"."+name,fallback);}
    }
    private static final class ReducedBoat extends Boat implements Reduced {
        private final PortContext c;
        @SuppressWarnings("unchecked") ReducedBoat(Boat original,PortContext c){super((EntityType<Boat>)original.getType(),original.level(),()->original.getPickResult().getItem());this.c=c;}
        public PortContext context(){return c;} public String category(){return "boat";}
        @Override public boolean canCollideWith(Entity entity){return c==null?super.canCollideWith(entity):flag("collides",false)&&super.canCollideWith(entity);}
        @Override public boolean canBeCollidedWith(Entity entity){return c==null?super.canBeCollidedWith(entity):flag("collides",false)&&super.canBeCollidedWith(entity);}
        @Override public boolean isPushable(){return c==null?super.isPushable():flag("pushable",false)&&super.isPushable();}
    }
    private static final class ReducedRaft extends Raft implements Reduced {
        private final PortContext c;
        @SuppressWarnings("unchecked") ReducedRaft(Raft original,PortContext c){super((EntityType<Raft>)original.getType(),original.level(),()->original.getPickResult().getItem());this.c=c;}
        public PortContext context(){return c;} public String category(){return "boat";}
        @Override public boolean canCollideWith(Entity entity){return c==null?super.canCollideWith(entity):flag("collides",false)&&super.canCollideWith(entity);}
        @Override public boolean canBeCollidedWith(Entity entity){return c==null?super.canBeCollidedWith(entity):flag("collides",false)&&super.canBeCollidedWith(entity);}
        @Override public boolean isPushable(){return c==null?super.isPushable():flag("pushable",false)&&super.isPushable();}
    }
    private static final class ReducedMinecart extends Minecart implements Reduced {
        private final PortContext c;
        ReducedMinecart(Minecart original,PortContext c){super(original.getType(),original.level());this.c=c;}
        public PortContext context(){return c;} public String category(){return "minecart";}
        @Override public boolean canCollideWith(Entity entity){return c==null?super.canCollideWith(entity):flag("collides",false)&&super.canCollideWith(entity);}
        @Override public boolean canBeCollidedWith(Entity entity){return c==null?super.canBeCollidedWith(entity):flag("collides",false)&&super.canBeCollidedWith(entity);}
        @Override public boolean isPushable(){return c==null?super.isPushable():flag("pushable",false)&&super.isPushable();}
    }
}
