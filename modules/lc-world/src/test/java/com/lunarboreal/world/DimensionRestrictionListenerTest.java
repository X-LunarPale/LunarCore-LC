package com.lunarboreal.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DimensionRestrictionListenerTest {
    private final WorldModule module = new WorldModule();
    private final DimensionRestrictionListener listener = new DimensionRestrictionListener(module);
    private final List<String> messages = new ArrayList<>();
    private final World source = world("survival", World.Environment.NORMAL);
    private final World nether = world("survival_nether", World.Environment.NETHER);
    private final World end = world("survival_the_end", World.Environment.THE_END);
    private final Player player = proxy(Player.class, (name, args) -> switch (name) {
        case "getName" -> "Player";
        case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
        case "sendMessage" -> {
            if (args != null) for (Object value : args) {
                if (value instanceof String text) messages.add(text);
                else if (value instanceof String[] texts) messages.addAll(Arrays.asList(texts));
            }
            yield null;
        }
        default -> null;
    });

    @Test void netherPortalIsCancelledWithADestinationSpecificMessage() {
        restrictNether();
        var event = portal(nether);
        listener.portal(event);
        assertTrue(event.isCancelled());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("下界"));
        assertFalse(messages.getFirst().contains("其他维度"));
    }

    @Test void regularNetherTeleportIsCancelledWithADestinationSpecificMessage() {
        restrictNether();
        var event = teleport(nether);
        listener.teleport(event);
        assertTrue(event.isCancelled());
        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().contains("下界"));
    }

    @Test void restrictingNetherKeepsEndPortalsAndTeleportsAvailable() {
        restrictNether();
        var portal = portal(end);
        var teleport = teleport(end);
        listener.portal(portal);
        listener.teleport(teleport);
        assertFalse(portal.isCancelled());
        assertFalse(teleport.isCancelled());
        assertTrue(messages.isEmpty());
    }

    @Test void sameEnvironmentTravelStaysAvailableEvenWhenAllDimensionsAreDisabled() {
        module.worlds.put("survival", WorldOptions.parse("survival",
                Map.of("allowOverworld", false, "allowNether", false, "allowEnd", false), null));
        World other = world("other_survival", World.Environment.NORMAL);
        var portal = portal(other);
        var teleport = teleport(other);
        listener.portal(portal);
        listener.teleport(teleport);
        assertFalse(portal.isCancelled());
        assertFalse(teleport.isCancelled());
        assertTrue(messages.isEmpty());
    }

    @Test void unmanagedSourceWorldDoesNotRestrictTravel() {
        var portal = portal(nether);
        var teleport = teleport(nether);
        listener.portal(portal);
        listener.teleport(teleport);
        assertFalse(portal.isCancelled());
        assertFalse(teleport.isCancelled());
        assertTrue(messages.isEmpty());
    }

    @Test void missingDestinationsAreLeftForOtherHandlers() {
        restrictNether();
        var portal = new PlayerPortalEvent(player, location(source), null);
        var teleport = new PlayerTeleportEvent(player, location(source), null);
        listener.portal(portal);
        listener.teleport(teleport);
        assertFalse(portal.isCancelled());
        assertFalse(teleport.isCancelled());
        assertNull(portal.getTo());
        assertNull(teleport.getTo());
        assertTrue(messages.isEmpty());
    }

    @Test void allowedTravelNeverClearsAnExistingCancellation() throws Exception {
        restrictNether();
        var portal = portal(end);
        var teleport = teleport(end);
        Location portalTarget = portal.getTo();
        Location teleportTarget = teleport.getTo();
        portal.setCancelled(true);
        teleport.setCancelled(true);
        listener.portal(portal);
        listener.teleport(teleport);
        assertTrue(portal.isCancelled());
        assertTrue(teleport.isCancelled());
        assertSame(portalTarget, portal.getTo());
        assertSame(teleportTarget, teleport.getTo());
        assertTrue(messages.isEmpty());
        assertTrue(DimensionRestrictionListener.class.getMethod("portal", PlayerPortalEvent.class)
                .getAnnotation(EventHandler.class).ignoreCancelled());
        assertTrue(DimensionRestrictionListener.class.getMethod("teleport", PlayerTeleportEvent.class)
                .getAnnotation(EventHandler.class).ignoreCancelled());
    }

    private void restrictNether() {
        module.worlds.put("survival", WorldOptions.parse("survival", Map.of("allowNether", false), null));
    }

    private PlayerPortalEvent portal(World destination) {
        return new PlayerPortalEvent(player, location(source), location(destination));
    }

    private PlayerTeleportEvent teleport(World destination) {
        return new PlayerTeleportEvent(player, location(source), location(destination));
    }

    private static Location location(World world) { return new Location(world, 0, 64, 0); }

    private static World world(String name, World.Environment environment) {
        return proxy(World.class, (method, args) -> switch (method) {
            case "getName" -> name;
            case "getEnvironment" -> environment;
            default -> null;
        });
    }

    @FunctionalInterface private interface Answer { Object answer(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object result = answer.answer(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive()) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == void.class) return null;
            return 0;
        });
    }
}
