package com.lunarboreal.vote;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VoteStartRequestTest {
    @Test void acceptsToolbarActionPayloadWithEmptyRow(){
        Map<String,Object> body=Map.of("row",Map.of(),"effect","speed","voteSeconds",60,"effectSeconds",45);
        assertSame(body,VoteModule.validateStartBody(body));
    }

    @Test void acceptsLegacyDirectPayloadWithoutRow(){
        assertDoesNotThrow(()->VoteModule.validateStartBody(Map.of("effect","jump")));
    }

    @Test void rejectsNonemptyRowUnknownFieldsAndMissingEffect(){
        assertThrows(IllegalArgumentException.class,()->VoteModule.validateStartBody(Map.of("row",Map.of("id","unexpected"),"effect","speed")));
        assertThrows(IllegalArgumentException.class,()->VoteModule.validateStartBody(Map.of("effect","speed","extra",true)));
        assertThrows(IllegalArgumentException.class,()->VoteModule.validateStartBody(Map.of("voteSeconds",60)));
    }
}
