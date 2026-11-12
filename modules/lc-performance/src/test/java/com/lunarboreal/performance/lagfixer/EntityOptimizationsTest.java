/* SPDX-License-Identifier: GPL-3.0-only
 * Tests for the LunarCore port of https://github.com/lajczik/lagfixer modules.
 */
package com.lunarboreal.performance.lagfixer;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityOptimizationsTest {
    @Test void afkModesMatchUpstreamTruthTable() {
        assertTrue(EntityOptimizations.afkByMode(true, false, 0));
        assertFalse(EntityOptimizations.afkByMode(true, false, 1));
        assertTrue(EntityOptimizations.afkByMode(true, true, 1));
        assertTrue(EntityOptimizations.afkByMode(true, false, 2));
        assertFalse(EntityOptimizations.afkByMode(true, false, 3));
        assertFalse(EntityOptimizations.afkByMode(true, true, 99));
    }
    @Test void budgetsAreBoundedForExtremeConfiguration() {
        assertEquals(1, EntityOptimizations.bounded(Integer.MIN_VALUE, 1, 16));
        assertEquals(16, EntityOptimizations.bounded(Integer.MAX_VALUE, 1, 16));
        assertEquals(2, EntityOptimizations.bounded(2, 1, 16));
    }
    @Test void protectsNamesPdcPassengersAndPets() {
        PersistentDataContainer empty = stub(PersistentDataContainer.class, Map.of("isEmpty", true));
        PersistentDataContainer data = stub(PersistentDataContainer.class, Map.of("isEmpty", false));
        Entity normal = stub(Entity.class, Map.of("getPersistentDataContainer", empty, "getPassengers", List.of()));
        assertFalse(EntityOptimizations.protectedEntity(normal));
        Entity named = stub(Entity.class, Map.of("customName", Component.text("protected")));
        assertTrue(EntityOptimizations.protectedEntity(named));
        Entity pluginEntity = stub(Entity.class, Map.of("getPersistentDataContainer", data));
        assertTrue(EntityOptimizations.protectedEntity(pluginEntity));
        Entity occupied = stub(Entity.class, Map.of("getPersistentDataContainer", empty, "getPassengers", List.of(normal)));
        assertTrue(EntityOptimizations.protectedEntity(occupied));
        Tameable pet = stub(Tameable.class, Map.of("getPersistentDataContainer", empty, "getPassengers", List.of(), "isTamed", true));
        assertTrue(EntityOptimizations.protectedEntity(pet));
    }
    @SuppressWarnings("unchecked") private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (values.containsKey(method.getName())) return values.get(method.getName());
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
    }
}
