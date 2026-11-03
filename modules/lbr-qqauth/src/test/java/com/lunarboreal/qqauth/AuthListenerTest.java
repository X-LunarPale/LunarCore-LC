package com.lunarboreal.qqauth;

import net.kyori.adventure.text.Component;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real Bukkit event objects against the listener without loading a server. */
class AuthListenerTest {
    private final QQAuthModule module = new QQAuthModule();
    private final AuthListener listener = new AuthListener(module);
    private final List<Object> sent = new ArrayList<>();
    private final UUID uuid = UUID.randomUUID();
    private final Player player = proxy(Player.class, (method, args) -> switch (method) {
        case "getUniqueId" -> uuid;
        case "getName" -> "Player";
        case "isOp", "hasPermission", "isOnline" -> true;
        case "sendMessage" -> { if (args != null) sent.addAll(List.of(args)); yield null; }
        default -> null;
    });

    @Test void notReadyFailsClosedEvenForOperatorBlockAndItemActions() {
        assertFalse(module.ready);
        assertNull(module.auth);
        assertTrue(player.isOp());
        var breaking = new BlockBreakEvent(proxy(Block.class, (m, a) -> null), player);
        listener.breakBlock(breaking);
        assertTrue(breaking.isCancelled());
        var dropping = new PlayerDropItemEvent(player, proxy(Item.class, (m, a) -> null));
        listener.drop(dropping);
        assertTrue(dropping.isCancelled());
    }

    @Test void movementRestoresExactPositionButKeepsLookDirection() {
        World world = proxy(World.class, (m, a) -> null);
        Location from = new Location(world, 10.25, 64, -3.75, 5, 10);
        Location to = new Location(world, 10.35, 64, -3.75, 90, 20);
        var event = new PlayerMoveEvent(player, from, to);
        listener.move(event);
        assertEquals(from.getX(), event.getTo().getX());
        assertEquals(from.getY(), event.getTo().getY());
        assertEquals(from.getZ(), event.getTo().getZ());
        assertEquals(90f, event.getTo().getYaw());
        assertEquals(20f, event.getTo().getPitch());
        assertEquals(5f, from.getYaw());
        assertTrue(event.isCancelled());
        assertEquals(90f, event.getFrom().getYaw());
        assertEquals(20f, event.getFrom().getPitch());
    }

    @Test void unauthenticatedPlayerMayFallVerticallyWithoutHorizontalDrift() {
        World world = proxy(World.class, (m, a) -> null);
        Location from = new Location(world, 10, 64, -3, 5, 10);
        Location to = new Location(world, 10, 63.5, -3, 90, 20);
        var falling = new PlayerMoveEvent(player, from, to);
        listener.move(falling);
        assertFalse(falling.isCancelled());
        assertEquals(63.5, falling.getTo().getY());
    }

    @Test void unauthenticatedFallClampsHorizontalDriftButKeepsGravity() {
        World world = proxy(World.class, (m, a) -> null);
        Location from = new Location(world, 10, 64, -3, 5, 10);
        Location to = new Location(world, 10.4, 63.5, -3.2, 90, 20);
        var falling = new PlayerMoveEvent(player, from, to);
        listener.move(falling);
        assertFalse(falling.isCancelled());
        assertEquals(10, falling.getTo().getX());
        assertEquals(63.5, falling.getTo().getY());
        assertEquals(-3, falling.getTo().getZ());
        assertEquals(90f, falling.getTo().getYaw());
        assertEquals(20f, falling.getTo().getPitch());
    }

    @Test void lookOnlyMovementStaysAllowedAndTeleportIsBlocked() {
        World world = proxy(World.class, (m, a) -> null);
        Location from = new Location(world, 10, 64, -3);
        Location to = new Location(world, 10, 64, -3, 90, 20);
        var look = new PlayerMoveEvent(player, from, to);
        listener.move(look);
        assertSame(to, look.getTo());
        assertFalse(look.isCancelled());
        var teleport = new PlayerTeleportEvent(player, from, new Location(world, 20, 65, -3));
        listener.teleport(teleport);
        assertTrue(teleport.isCancelled());
    }

    @Test void unauthenticatedMovementModesCannotStartButCanStop() {
        var flight=new PlayerToggleFlightEvent(player,true); listener.flight(flight);assertTrue(flight.isCancelled());
        var sprint=new PlayerToggleSprintEvent(player,true);listener.sprint(sprint);assertTrue(sprint.isCancelled());
        var glide=new org.bukkit.event.entity.EntityToggleGlideEvent(player,true);listener.glide(glide);assertTrue(glide.isCancelled());
        var stop=new PlayerToggleFlightEvent(player,false);listener.flight(stop);assertFalse(stop.isCancelled());
        var velocity=new PlayerVelocityEvent(player,new org.bukkit.util.Vector(1,2,3));listener.velocity(velocity);assertTrue(velocity.isCancelled());
    }

    @Test void secretCommandsAreCancelledAndRedactedBeforeNotReadyResponse() {
        for (String label : AuthCommands.SECRET_COMMANDS) {
            var event = new PlayerCommandPreprocessEvent(player, "/" + label + " secret-one secret-two", Set.of());
            listener.command(event);
            assertTrue(event.isCancelled(), label);
            assertEquals("/qqauth-redacted", event.getMessage(), label);
        }
        assertFalse(sent.isEmpty());
        assertFalse(sent.toString().contains("secret-one"));
        assertFalse(sent.toString().contains("secret-two"));
    }

    @Test void previouslyCancelledSecretCommandRemainsRedactedWithoutExecuting() {
        var event = new PlayerCommandPreprocessEvent(player, "/login private-password", Set.of());
        event.setCancelled(true);
        listener.command(event);
        assertTrue(event.isCancelled());
        assertEquals("/qqauth-redacted", event.getMessage());
        assertTrue(sent.isEmpty());
    }

    @Test void namespacedSecretCommandIsRedactedAndNeverDispatched() {
        var event = new PlayerCommandPreprocessEvent(player, "/qqauth:login private-password", Set.of());
        listener.command(event);
        assertTrue(event.isCancelled());
        assertEquals("/qqauth-redacted", event.getMessage());
        assertFalse(sent.toString().contains("private-password"));
    }

    @Test void preloginRejectsNotReadyAndInvalidNamesBeforeTouchingServices() throws Exception {
        var readyCheck = prelogin("Player");
        listener.prelogin(readyCheck);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, readyCheck.getLoginResult());
        module.ready = true; // Null services prove invalid names are rejected before service access.
        for (String name : List.of("A", "AB", "invalid-name", "a".repeat(17))) {
            var invalid = prelogin(name);
            listener.prelogin(invalid);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, invalid.getLoginResult());
        }
    }

    @Test void preloginKeepsPriorBanResultAndMessage() throws Exception {
        var event = prelogin("Player");
        Component original = Component.text("Previously banned");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, original);
        listener.prelogin(event);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
        assertEquals(original, event.kickMessage());
    }

    private AsyncPlayerPreLoginEvent prelogin(String name) throws Exception {
        // Explicit profile avoids the legacy constructor's global Bukkit.createProfile dependency.
        PlayerProfile profile = proxy(PlayerProfile.class, (method, args) -> switch (method) {
            case "getName" -> name;
            case "getId", "getUniqueId" -> uuid;
            default -> null;
        });
        return new AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), uuid, false, profile);
    }

    @FunctionalInterface private interface Answer { Object answer(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
            if (m.getName().equals("equals")) return p == args[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return "Test" + type.getSimpleName();
            Object result = answer.answer(m.getName(), args);
            if (result != null || !m.getReturnType().isPrimitive()) return result;
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == long.class) return 0L;
            if (r == float.class) return 0f;
            if (r == double.class) return 0d;
            if (r == byte.class) return (byte) 0;
            if (r == short.class) return (short) 0;
            if (r == char.class) return '\0';
            if (r == void.class) return null;
            return 0;
        });
    }
}
