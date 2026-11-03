package com.lunarboreal.vote;

import java.util.*;

/** Immutable proposal snapshot: electorate and thresholds never change mid-vote. */
public record VoteState(UUID id, String effect, long deadline, int effectSeconds, Set<UUID> electorate,
                        Map<UUID,Boolean> ballots, double quorum, double approval) {
    public VoteState {
        Objects.requireNonNull(id); Objects.requireNonNull(effect);
        electorate = Set.copyOf(electorate); ballots = Map.copyOf(ballots);
        if (electorate.isEmpty() || electorate.size()>200 || !electorate.containsAll(ballots.keySet())
                || effectSeconds<1 || effectSeconds>300 || !Double.isFinite(quorum) || quorum<=0 || quorum>1
                || !Double.isFinite(approval) || approval<=0 || approval>1) throw new IllegalArgumentException("无效投票快照");
    }
    public VoteState cast(UUID voter, boolean yes, long now) {
        if(now>=deadline) throw new IllegalArgumentException("投票已截止");
        if(!electorate.contains(voter)) throw new IllegalArgumentException("你不在本轮启动时的选民名单");
        if(ballots.containsKey(voter)) throw new IllegalArgumentException("每人只有一票，不能改票");
        Map<UUID,Boolean> next=new HashMap<>(ballots); next.put(voter,yes);
        return new VoteState(id,effect,deadline,effectSeconds,electorate,next,quorum,approval);
    }
    public long yes(){return ballots.values().stream().filter(Boolean::booleanValue).count();}
    public boolean passed(){return ballots.size()>=Math.ceil(electorate.size()*quorum)
            && yes()>ballots.size()-yes() && yes()>=Math.ceil(ballots.size()*approval);}
}
