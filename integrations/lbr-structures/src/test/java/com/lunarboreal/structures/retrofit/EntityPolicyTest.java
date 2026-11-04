package com.lunarboreal.structures.retrofit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityPolicyTest {
    @Test void generatedEntitiesMatchByTypeNearTheirReplayedPosition() {
        var cart = new EntityPolicy.Fixed("CHEST_MINECART", 10, 40, 10);
        assertTrue(cart.matches("CHEST_MINECART", 10.5, 40, 12));
        assertFalse(cart.matches("CHEST_MINECART", 10, 40, 14));
        assertFalse(cart.matches("MINECART", 10, 40, 10));
    }
}
