/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Original LagFixer algorithms: Copyright lychee and LagFixer contributors.
 * Upstream build151: 0dede033f7dc2950a08f8a461bfcff84f64c7d3c (nms/v26_2/OptimizedTemptGoal).
 * Modified for LunarCore: species food predicates, bounded config values and explicit lifecycle.
 */
package com.lunarboreal.performance.lagfixer.nms;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;
import java.util.EnumSet;

/** GPL-3.0-only, derived from LagFixer build151 OptimizedTemptGoal. Uses actual species food rules. */
final class ReducedTemptGoal extends Goal {
    private final Animal mob;
    private final ConfigurationSection cfg;
    private final TargetingConditions targeting;
    private Player target;
    private int cooldown;
    ReducedTemptGoal(Animal mob,ConfigurationSection cfg){this.mob=mob;this.cfg=cfg;targeting=TargetingConditions.forNonCombat().ignoreLineOfSight().range(Math.clamp(cfg.getDouble("animals.tempt.range",10),1,32))
        .selector((entity,level)->mob.isFood(entity.getMainHandItem())||(cfg.getBoolean("animals.tempt.trigger_both_hands",true)&&mob.isFood(entity.getOffhandItem())));setFlags(EnumSet.of(Flag.MOVE));}
    @Override public boolean canUse(){
        if(cooldown>0){cooldown--;return false;}
        target=getServerLevel(mob).getNearestPlayer(targeting,mob);
        if(target==null)return false;
        return !cfg.getBoolean("animals.tempt.event",true)||!CraftEventFactory.callEntityTargetLivingEvent(mob,target,EntityTargetEvent.TargetReason.TEMPT).isCancelled();
    }
    @Override public void start(){tick();}
    @Override public boolean canContinueToUse(){return false;}
    @Override public void tick(){
        if(target==null||!target.isAlive())return;
        if(cfg.getBoolean("animals.tempt.teleport",false))mob.teleportTo(target.getX(),target.getY(),target.getZ());
        else if(mob.distanceToSqr(target)>=6.25)mob.getNavigation().moveTo(target,Math.clamp(cfg.getDouble("animals.tempt.speed",1),0.1,4));
        else mob.getNavigation().stop();
        cooldown=Math.clamp(cfg.getInt("animals.tempt.cooldown",10),0,1200);
    }
    @Override public void stop(){target=null;}
}
