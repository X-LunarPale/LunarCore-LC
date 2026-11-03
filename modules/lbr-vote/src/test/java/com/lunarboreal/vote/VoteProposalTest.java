package com.lunarboreal.vote;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoteProposalTest {
    @Test void everyWhitelistedProposalHasFixedBenefitAndCost(){
        assertEquals(List.of("SPEED","HUNGER"),VoteProposal.speed.effects);
        assertEquals(List.of("JUMP_BOOST","WEAKNESS"),VoteProposal.jump.effects);
        assertEquals(List.of("HASTE","SLOWNESS"),VoteProposal.slow.effects);
        for(VoteProposal p:VoteProposal.values()){
            assertFalse(p.benefit.isBlank());assertFalse(p.drawback.isBlank());
            assertTrue(p.description().contains("收益："));assertTrue(p.description().contains("代价："));
            assertThrows(UnsupportedOperationException.class,()->p.effects.clear());
        }
        assertTrue(VoteProposal.find("kill @a").isEmpty());
    }
    @Test void externalEffectOnEitherHalfBlocksWholeBundle(){
        for(VoteProposal p:VoteProposal.values())for(String type:p.effects){
            assertFalse(p.canLease(Set.of(type),Set.of()));
            assertFalse(p.canLease(Set.copyOf(p.effects),Set.of(p.effects.get(1-p.effects.indexOf(type)))));
        }
    }
    @Test void ownPairMayRenewAndEmptyPairMayStart(){
        for(VoteProposal p:VoteProposal.values()){
            assertTrue(p.canLease(Set.of(),Set.of()));
            assertTrue(p.canLease(Set.copyOf(p.effects),Set.copyOf(p.effects)));
            assertTrue(p.canLease(Set.of("NIGHT_VISION"),Set.of()));
        }
    }
}
