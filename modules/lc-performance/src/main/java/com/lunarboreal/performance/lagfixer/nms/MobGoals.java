/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Original LagFixer algorithms: Copyright lychee and LagFixer contributors.
 * Upstream build151: 0dede033f7dc2950a08f8a461bfcff84f64c7d3c (nms/v26_2/MobAiReducer).
 * Modified for LunarCore: bounded tracking, conservative entity selection and ownership-aware restoration.
 */
package com.lunarboreal.performance.lagfixer.nms;

import com.lunarboreal.performance.lagfixer.PortContext;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Animal;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftCreature;
import org.bukkit.entity.Entity;
import java.util.*;

/** GPL-3.0-only, derived from LagFixer build151 v26_2 MobAiReducer; adds reversible lifecycle. */
public final class MobGoals implements AutoCloseable {
    private record Changed(PathfinderMob mob,List<WrappedGoal> removed,List<Goal> added,Map<Goal,Integer> installedGoals,
                           boolean collision,boolean silent,boolean appliedCollision,boolean appliedSilent) {}
    private final PortContext c;
    private final Map<UUID,Changed> changed=new HashMap<>();
    private final ArrayDeque<UUID> maintenance=new ArrayDeque<>();
    public MobGoals(PortContext c){this.c=c;}
    public void optimize(Entity e) {
        if(!(e instanceof CraftCreature craft)||changed.containsKey(e.getUniqueId())||e.customName()!=null||!e.getPersistentDataContainer().isEmpty())return;
        ConfigurationSection cfg=c.section("mob-ai");
        if(cfg==null||changed.size()>=Math.clamp(cfg.getInt("scan.cache-limit",4096),1,4096))return;
        String category=e instanceof org.bukkit.entity.Tameable?"tameable":e instanceof org.bukkit.entity.Animals?"animals":e instanceof org.bukkit.entity.Monster?"monsters":e instanceof org.bukkit.entity.AbstractVillager?"villagers":"others";
        if(!cfg.getBoolean("entities."+category,!category.equals("tameable")))return;
        List<String> reasons=cfg.getStringList("spawn_reasons");
        if(!reasons.isEmpty()&&!reasons.contains(e.getEntitySpawnReason().name()))return;
        List<String> list=cfg.getStringList("list");
        if(!list.isEmpty() && list.contains(e.getType().name())!=cfg.getBoolean("list_mode",false))return;
        PathfinderMob mob=craft.getHandle();
        List<WrappedGoal> removed=new ArrayList<>();List<Goal> added=new ArrayList<>();
        boolean collision=mob.collides,silent=mob.isSilent();
        for(WrappedGoal old:List.copyOf(mob.getGoalSelector().getAvailableGoals())) {
            Goal original=old.getGoal();Class<?> type=original.getClass();Goal replacement=null;
            if(cfg.getBoolean("pathfinder.keep_dedicated",true)&&!type.getName().startsWith("net.minecraft.world.entity.ai.goal."))continue;
            if(type==BreedGoal.class&&mob instanceof Animal animal&&cfg.getBoolean("animals.breed.enabled",true))replacement=new ReducedBreedGoal(animal,cfg);
            else if(type==TemptGoal.class&&mob instanceof Animal animal&&cfg.getBoolean("animals.tempt.enabled",true))replacement=new ReducedTemptGoal(animal,cfg);
            boolean remove=cfg.getStringList("pathfinder.list").stream().anyMatch(type.getSimpleName()::contains)==cfg.getBoolean("pathfinder.list_mode",true);
            if(replacement==null&&!remove)continue;
            mob.getGoalSelector().removeGoal(original);removed.add(old);
            if(replacement!=null){mob.getGoalSelector().addGoal(old.getPriority(),replacement);added.add(replacement);}
        }
        mob.collides=cfg.getBoolean("collides",true);mob.setSilent(cfg.getBoolean("silent",false));
        Map<Goal,Integer> installed=new IdentityHashMap<>();
        for(WrappedGoal goal:mob.getGoalSelector().getAvailableGoals())installed.put(goal.getGoal(),goal.getPriority());
        changed.put(e.getUniqueId(),new Changed(mob,removed,added,installed,collision,silent,mob.collides,mob.isSilent()));maintenance.addLast(e.getUniqueId());c.count("mob-ai");
    }
    public void restore(Entity e){restore(e.getUniqueId());}
    private void restore(UUID id) {
        Changed state=changed.remove(id);maintenance.remove(id);if(state==null)return;
        List<Goal> current=state.mob.getGoalSelector().getAvailableGoals().stream().map(WrappedGoal::getGoal).toList();
        boolean unchanged=current.size()==state.installedGoals.size()&&state.mob.getGoalSelector().getAvailableGoals().stream()
                .allMatch(goal->Objects.equals(state.installedGoals.get(goal.getGoal()),goal.getPriority()));
        // Remove only our own goal instances. If another plugin changed the selector, do not
        // reinsert removed vanilla goals that could conflict with its new priorities/behaviour.
        for(Goal goal:state.added)if(current.stream().anyMatch(existing->existing==goal))state.mob.getGoalSelector().removeGoal(goal);
        if(unchanged)for(WrappedGoal goal:state.removed)state.mob.getGoalSelector().addGoal(goal.getPriority(),goal.getGoal());
        else c.count("mob-ai-restore-external-change");
        if(state.mob.collides==state.appliedCollision)state.mob.collides=state.collision;
        if(state.mob.isSilent()==state.appliedSilent)state.mob.setSilent(state.silent);
    }
    public int reconcile(int budget){
        int used=0,count=Math.min(budget,maintenance.size());
        while(used<count){UUID id=maintenance.removeFirst();Changed state=changed.get(id);used++;if(state==null)continue;
            Entity e=state.mob.getBukkitEntity();
            if(!e.isValid()||!c.enabled("mob-ai")||!c.allows("mob-ai",e.getWorld()))restore(id);else maintenance.addLast(id);
        }return used;
    }
    public void restoreWorld(org.bukkit.World world){for(var entry:List.copyOf(changed.entrySet()))if(entry.getValue().mob.getBukkitEntity().getWorld().equals(world))restore(entry.getKey());}
    @Override public void close(){for(UUID id:List.copyOf(changed.keySet()))restore(id);maintenance.clear();}
}
