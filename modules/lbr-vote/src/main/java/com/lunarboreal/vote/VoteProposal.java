package com.lunarboreal.vote;

import java.util.*;

/** Fixed bundles: configuration may enable a proposal, never remove its cost or supply commands. */
enum VoteProposal {
    speed("赶路更快：速度I", "消耗饱食度更快：饥饿I", "SPEED", "HUNGER"),
    jump("跳得更高：跳跃提升I", "近战伤害降低：虚弱I", "JUMP_BOOST", "WEAKNESS"),
    slow("挖掘更快：急迫I", "走得更慢：缓慢I", "HASTE", "SLOWNESS");

    final String benefit, drawback;
    final List<String> effects;
    VoteProposal(String benefit,String drawback,String... effects) {
        this.benefit=benefit;this.drawback=drawback;this.effects=List.of(effects);
    }
    String description(){return "收益："+benefit+"；代价："+drawback;}
    boolean canLease(Set<String> existing,Set<String> owned) {
        return effects.stream().allMatch(type->!existing.contains(type)||owned.contains(type));
    }
    static Optional<VoteProposal> find(String id){return Arrays.stream(values()).filter(p->p.name().equals(id)).findFirst();}
}
