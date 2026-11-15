package com.lunarboreal.teleport;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class WorldReturnLocationsTest {
    @Test void retainsPreciseCoordinatesAndDirectionInPlayerData() {
        var data = TestPersistentData.container();
        Player player = player(data);
        World world = world("lc_terrain:jixian", "lc_terrain_jixian");
        Location original = new Location(world, 124.25, 73, -25.75, 86.5f, 12f);
        WorldReturnLocations.remember(player, original);
        assertEquals(original, WorldReturnLocations.get(player, world));

        World reloadedWorld = world("lc_terrain:jixian", "renamed_world");
        Location restored = WorldReturnLocations.get(player(data), reloadedWorld);
        assertSame(reloadedWorld, restored.getWorld());
        assertEquals(original.getX(), restored.getX());
        assertEquals(original.getZ(), restored.getZ());
        assertEquals(original.getYaw(), restored.getYaw());
        assertEquals(original.getPitch(), restored.getPitch());
    }

    @Test void worldsWithTheSamePathUseTheirFullDimensionKeys() {
        Player player = player(TestPersistentData.container());
        World first = world("lc_terrain:survival", "first");
        World second = world("other:survival", "second");
        Location firstPoint = new Location(first, 12, 73, 44);
        Location secondPoint = new Location(second, 90, 80, 20);
        WorldReturnLocations.remember(player, firstPoint);
        WorldReturnLocations.remember(player, secondPoint);
        assertEquals(firstPoint, WorldReturnLocations.get(player, first));
        assertEquals(secondPoint, WorldReturnLocations.get(player, second));
    }

    @Test void playersDoNotShareReturnLocations() {
        Player first = player(TestPersistentData.container());
        Player second = player(TestPersistentData.container());
        World world = world("minecraft:overworld", "world");
        WorldReturnLocations.remember(first, new Location(world, 10, 73, 20));
        assertNotNull(WorldReturnLocations.get(first, world));
        assertNull(WorldReturnLocations.get(second, world));
    }

    @Test void invalidCoordinatesCannotReplaceAnExistingPoint() {
        Player player = player(TestPersistentData.container());
        World world = world("minecraft:overworld", "world");
        Location original = new Location(world, 10, 73, 20);
        WorldReturnLocations.remember(player, original);
        for (Location invalid : new Location[]{
                new Location(world, Double.NaN, 73, 20),
                new Location(world, 10, -65, 20),
                new Location(world, 10, 320, 20),
                new Location(world, 31_000_000, 73, 20),
                new Location(world, 10, 73, 20, Float.POSITIVE_INFINITY, 0)
        }) {
            WorldReturnLocations.remember(player, invalid);
            assertEquals(original, WorldReturnLocations.get(player, world));
        }
    }

    private static Player player(PersistentDataContainer data) {
        return proxy(Player.class, (name, args) -> name.equals("getPersistentDataContainer") ? data : null);
    }

    private static World world(String key, String name) {
        var border = proxy(WorldBorder.class, (method, args) -> method.equals("isInside")
                ? Math.abs(((Location) args[0]).getX()) <= 30_000_000
                && Math.abs(((Location) args[0]).getZ()) <= 30_000_000 : null);
        return proxy(World.class, (method, args) -> switch (method) {
            case "getKey" -> NamespacedKey.fromString(key);
            case "getName" -> name;
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getWorldBorder" -> border;
            default -> null;
        });
    }

    @FunctionalInterface private interface Answer { Object answer(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            return answer.answer(method.getName(), args);
        });
    }
}
