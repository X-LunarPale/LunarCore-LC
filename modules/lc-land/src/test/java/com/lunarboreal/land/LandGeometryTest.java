package com.lunarboreal.land;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LandGeometryTest {
    private Land claim(String name, int low, int high) {
        Land l = new Land(name, UUID.randomUUID(), name, "world", -16, -16, 16, 16, 1);
        l.minY = low;
        l.maxY = high;
        return l;
    }

    @Test void stackedClaimsUseInclusiveThreeDimensionalBounds() {
        LandManager manager = new LandManager();
        Land lower = claim("lower", -64, 0), upper = claim("upper", 1, 20);
        manager.add(lower);
        manager.add(upper);
        assertSame(lower, manager.at("world", -16, -64, -16));
        assertSame(lower, manager.at("world", 16, 0, 16));
        assertSame(upper, manager.at("world", 0, 1, 0));
        assertNull(manager.at("world", 0, 21, 0));
        assertNull(manager.at("other", 0, 0, 0));
        assertTrue(manager.overlapping("world", -16, 21, -16, 16, 40, 16, null).isEmpty());
        assertEquals(2, manager.overlapping("world", -16, 0, -16, 16, 1, 16, null).size());
        assertEquals(1, manager.overlapping("world", -16, 0, -16, 16, 1, 16, lower).size());
        manager.remove(lower);
        assertNull(manager.at("world", 0, 0, 0));
        assertSame(upper, manager.at("world", 0, 1, 0));
    }

    @Test void volumePricingClampsLegacyHeightAndMatchesExpansionDelta() {
        java.math.BigDecimal price = new java.math.BigDecimal("0.125");
        assertEquals(new java.math.BigDecimal("12.50"), LandGeometry.cost(price,100,false,10,19,-64,320));
        assertEquals(new java.math.BigDecimal("125.00"), LandGeometry.cost(price,100,true,10,19,-64,320));
        assertEquals(new java.math.BigDecimal("4800.00"), LandGeometry.cost(price,100,true,Integer.MIN_VALUE,Integer.MAX_VALUE,-64,320));
        assertEquals(LandGeometry.cost(price,120,true,10,19,-64,320),
                LandGeometry.cost(price,100,true,10,19,-64,320).add(LandGeometry.cost(price,20,true,10,19,-64,320)));
        assertEquals(384,LandGeometry.height(Integer.MIN_VALUE,Integer.MAX_VALUE,-64,320));
        assertEquals(0,LandGeometry.height(400,500,-64,320));
    }

    @Test void spongeFiltersBothDirectionsAcrossAllSixFaces() {
        LandManager manager = new LandManager();
        Land inside = claim("inside",10,20), stacked=claim("stacked",21,30);
        manager.add(inside); manager.add(stacked);
        int[][] faces={{-17,15,0},{17,15,0},{0,15,-17},{0,15,17},{0,9,0},{0,21,0}};
        for(int[] xyz:faces) {
            Land source=manager.at("world",xyz[0],xyz[1],xyz[2]);
            Land target = manager.at("world",0,15,0);
            assertTrue(LandGeometry.spongeProtected(source,target));
            assertTrue(LandGeometry.spongeProtected(target,source));
        }
        assertFalse(LandGeometry.spongeProtected(inside,inside));
        assertTrue(LandGeometry.spongeProtected(inside,null));
        assertFalse(LandGeometry.spongeProtected(null,null));
        assertTrue(LandGeometry.spongeProtected(inside,stacked));
    }

    @Test void permissionIsRequiredEvenWithDefaultOrStaleToggle() {
        for(boolean defaults:new boolean[]{false,true}) {
            assertFalse(LandGeometry.bypass(false,defaults,true,false));
            assertFalse(LandGeometry.bypass(false,defaults,false,false));
            assertFalse(LandGeometry.bypass(true,defaults,true,true));
        }
        assertTrue(LandGeometry.bypass(true,true,false,false));
        assertTrue(LandGeometry.bypass(true,false,true,false));
        assertFalse(LandGeometry.bypass(true,false,false,false));
    }

    @Test void queuedDoubleClicksCanDispatchExactlyOncePerMenu() {
        MenuClickGate confirm = new MenuClickGate();
        int settlements = 0;
        for (int i = 0; i < 20; i++) if (confirm.consume()) settlements++;
        assertEquals(1, settlements);
        assertTrue(new MenuClickGate().consume());
        assertFalse(confirm.consume());
    }

    @Test void legacyClaimsRemainFullHeightAndExportHeight() {
        Land old = new Land("old", UUID.randomUUID(), "old", "world", 0, 0, 4, 4, 1);
        assertTrue(old.contains("world", 0, -1000, 0));
        assertTrue(old.contains("world", 4, 1000, 4));
        Land modern = claim("modern", 10, 20);
        assertTrue(modern.info().contains("world", 0, 10, 0));
        assertFalse(modern.info().contains("world", 0, 9, 0));
    }
}
