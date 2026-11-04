package com.lunarboreal.land;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LandIntersectionServiceTest {
    @Test void unavailableProtectionIsDifferentFromAVerifiedEmptyArea() throws Exception {
        var module = new LandModule();
        var service = new LandServiceImpl(module);
        assertTrue(service.intersecting("world",-16,0,-16,16,20,16).isEmpty());
        var loaded = LandModule.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(module,true);
        assertTrue(service.intersecting("world",-16,0,-16,16,20,16).orElseThrow().isEmpty());
        var claim = new Land("protected",UUID.randomUUID(),"owner","world",16,16,32,32,1);
        claim.minY=10;claim.maxY=30;module.manager().add(claim);
        assertEquals(1,service.intersecting("world",-16,10,-16,16,20,16).orElseThrow().size());
        assertTrue(service.intersecting("world",-16,31,-16,16,40,16).orElseThrow().isEmpty());
        assertTrue(service.intersecting("other",16,10,16,32,30,32).orElseThrow().isEmpty());
        loaded.setBoolean(module,false);
        assertTrue(service.intersecting("world",16,10,16,32,30,32).isEmpty());
    }
}
