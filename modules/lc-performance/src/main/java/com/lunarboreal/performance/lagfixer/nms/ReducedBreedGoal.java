/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Original LagFixer algorithms: Copyright lychee and LagFixer contributors.
 * Upstream build151: 0dede033f7dc2950a08f8a461bfcff84f64c7d3c (nms/v26_2/OptimizedBreedGoal).
 * Modified for LunarCore: bounded config values, one breeding attempt per run and explicit lifecycle.
 */
package com.lunarboreal.performance.lagfixer.nms;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;
import java.util.*;

/** GPL-3.0-only, derived from LagFixer build151 OptimizedBreedGoal. */
final class ReducedBreedGoal extends Goal {
    private final Animal animal;
    private final ConfigurationSection cfg;
    private final TargetingConditions targeting;
    private Animal partner;
    private boolean bred;
    ReducedBreedGoal(Animal animal,ConfigurationSection cfg){this.animal=animal;this.cfg=cfg;targeting=TargetingConditions.forNonCombat().ignoreLineOfSight().range(range());setFlags(EnumSet.of(Flag.MOVE));}
    private double range(){return Math.clamp(cfg.getDouble("animals.breed.range",8),1,32);}
    @Override public boolean canUse(){
        if(!animal.isInLove())return false;
        partner=getServerLevel(animal).getNearbyEntities(animal.getClass(),targeting,animal,animal.getBoundingBox().inflate(range())).stream()
                .filter(animal::canMate).min(Comparator.comparingDouble(animal::distanceToSqr)).orElse(null);
        if(partner==null)return false;
        if(cfg.getBoolean("animals.breed.event",true)&&CraftEventFactory.callEntityTargetLivingEvent(animal,partner,EntityTargetEvent.TargetReason.CUSTOM).isCancelled())return false;
        return true;
    }
    @Override public void start(){bred=false;}
    @Override public boolean canContinueToUse(){return !bred&&partner!=null&&partner.isAlive()&&animal.isInLove()&&partner.isInLove();}
    @Override public void tick(){
        if(partner==null||bred)return;
        if(cfg.getBoolean("animals.breed.teleport",false))animal.teleportTo(partner.getX(),partner.getY(),partner.getZ());
        else animal.getNavigation().moveTo(partner,Math.clamp(cfg.getDouble("animals.breed.speed",1),0.1,4));
        // Preserve vanilla breeding event/offspring inheritance rather than constructing a Bukkit baby.
        animal.spawnChildFromBreeding(getServerLevel(animal),partner);bred=true;
    }
    @Override public void stop(){partner=null;animal.getNavigation().stop();}
}
