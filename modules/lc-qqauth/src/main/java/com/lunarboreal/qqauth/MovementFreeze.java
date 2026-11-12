package com.lunarboreal.qqauth;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import java.util.*;

/** Main-thread, connection-identity owned ability freeze. Bukkit cannot remove client prediction.
 * Each field is restored only while it still equals our applied value. External changes relinquish
 * that field until this freeze ends; position/action cancellation remains the authoritative guard.
 */
final class MovementFreeze implements AutoCloseable {
    private final Map<UUID, State> frozen = new HashMap<>();
    void update(Player player, boolean blocked) {
        State state = frozen.get(player.getUniqueId());
        if (state != null && state.player != player) { release(state.player); state = null; }
        if (!blocked) { release(player); return; }
        if (state == null) {
            state = new State(player);
            frozen.put(player.getUniqueId(), state);
            state.apply();
        } else state.observe();
        // Logout/invalidation may occur while already riding; abilities alone cannot stop a vehicle.
        if (player.isInsideVehicle()) player.leaveVehicle();
    }
    void release(Player player) {
        State state = frozen.get(player.getUniqueId());
        if (state == null || state.player != player) return;
        frozen.remove(player.getUniqueId());
        state.restore();
    }
    int size() { return frozen.size(); }
    @Override public void close() {
        for (State state : List.copyOf(frozen.values())) release(state.player);
    }
    private static final class State {
        final Player player;
        final float walk, fly;
        final boolean allowed, flying, sprinting;
        final GameMode mode;
        boolean ownWalk=true, ownFly=true, ownAllowed=true, ownFlying=true, ownSprint=true;
        State(Player p) {
            player=p; walk=p.getWalkSpeed(); fly=p.getFlySpeed(); allowed=p.getAllowFlight();
            flying=p.isFlying(); sprinting=p.isSprinting(); mode=p.getGameMode();
        }
        void apply() {
            player.setWalkSpeed(0); player.setFlySpeed(0);
            player.setFlying(false); player.setAllowFlight(false); player.setSprinting(false);
            // Do not restore gliding/velocity later: replaying motion after login can be unsafe.
            player.setGliding(false);
        }
        void observe() {
            ownWalk &= Float.compare(player.getWalkSpeed(),0)==0;
            ownFly &= Float.compare(player.getFlySpeed(),0)==0;
            ownAllowed &= !player.getAllowFlight(); ownFlying &= !player.isFlying();
            ownSprint &= !player.isSprinting();
        }
        void restore() {
            observe();
            if (ownWalk) player.setWalkSpeed(walk);
            if (ownFly) player.setFlySpeed(fly);
            // A gamemode change is another owner of flight capabilities, even if values coincide.
            if (player.getGameMode()==mode) {
                if (ownAllowed) player.setAllowFlight(allowed);
                if (ownFlying && ownAllowed && allowed && player.getAllowFlight()) player.setFlying(flying);
            }
            if (ownSprint) player.setSprinting(sprinting);
        }
    }
}
