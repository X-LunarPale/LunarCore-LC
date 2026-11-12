package com.lunarboreal.vote;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoteStateTest {
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),d=UUID.randomUUID();
    VoteState vote(){return new VoteState(UUID.randomUUID(),"speed",1000,60,Set.of(a,b,c,d),Map.of(),.5,.6);}
    @Test void oneImmutableBallotAndSnapshot(){VoteState v=vote(),next=v.cast(a,true,1);assertTrue(v.ballots().isEmpty());assertThrows(IllegalArgumentException.class,()->next.cast(a,false,2));assertThrows(IllegalArgumentException.class,()->next.cast(UUID.randomUUID(),true,2));assertThrows(UnsupportedOperationException.class,()->next.electorate().clear());}
    @Test void thresholdQuorumAndTie(){assertFalse(vote().cast(a,true,1).passed());assertTrue(vote().cast(a,true,1).cast(b,true,1).passed());assertFalse(vote().cast(a,true,1).cast(b,false,1).passed());assertTrue(vote().cast(a,true,1).cast(b,true,1).cast(c,false,1).passed());}
    @Test void deadlineExclusive(){assertThrows(IllegalArgumentException.class,()->vote().cast(a,true,1000));}
    @Test void invalidAndNanRejected(){assertThrows(IllegalArgumentException.class,()->new VoteState(UUID.randomUUID(),"x",100,60,Set.of(a),Map.of(b,true),.5,.6));assertThrows(IllegalArgumentException.class,()->new VoteState(UUID.randomUUID(),"x",100,60,Set.of(a),Map.of(),Double.NaN,.6));}
}
