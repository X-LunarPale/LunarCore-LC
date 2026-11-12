package com.lunarboreal.qqauth;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MovementFreezeTest {
    static final class Actor {
        UUID id=UUID.randomUUID(); float walk=.31f,fly=.17f;
        boolean allowed=true,flying=true,sprint=true,glide=true,insideVehicle=false,leftVehicle=false;
        GameMode mode=GameMode.CREATIVE;
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(p,m,a)->switch(m.getName()) {
            case "getUniqueId"->id; case "getWalkSpeed"->walk; case "getFlySpeed"->fly;
            case "getAllowFlight"->allowed; case "isFlying"->flying; case "isSprinting"->sprint;
            case "getGameMode"->mode; case "isInsideVehicle"->insideVehicle;
            case "leaveVehicle"->{insideVehicle=false;leftVehicle=true;yield true;}
            case "setWalkSpeed"->{walk=(float)a[0];yield null;}
            case "setFlySpeed"->{fly=(float)a[0];yield null;}
            case "setAllowFlight"->{allowed=(boolean)a[0];yield null;}
            case "setFlying"->{flying=(boolean)a[0];yield null;}
            case "setSprinting"->{sprint=(boolean)a[0];yield null;}
            case "setGliding"->{glide=(boolean)a[0];yield null;}
            default->null;
        });
    }
    @Test void repeatedFreezeKeepsOriginalAndAuthRestoresCapabilities() {
        var f=new MovementFreeze();var a=new Actor();
        f.update(a.player,true);f.update(a.player,true);
        assertEquals(0,a.walk);assertEquals(0,a.fly);assertFalse(a.allowed);assertFalse(a.flying);assertFalse(a.sprint);assertFalse(a.glide);
        f.update(a.player,false);
        assertEquals(.31f,a.walk);assertEquals(.17f,a.fly);assertTrue(a.allowed);assertTrue(a.flying);assertTrue(a.sprint);assertFalse(a.glide);assertEquals(0,f.size());
    }
    @Test void externalChangesRelinquishOwnershipEvenWhenValueLaterMatchesFreeze() {
        var f=new MovementFreeze();var a=new Actor();f.update(a.player,true);
        a.walk=.6f;a.fly=.5f;a.allowed=true;a.flying=true;a.sprint=true;f.update(a.player,true);
        a.walk=0;a.fly=0;a.allowed=false;a.flying=false;a.sprint=false;
        f.close();assertEquals(0,a.walk);assertEquals(0,a.fly);assertFalse(a.allowed);assertFalse(a.flying);assertFalse(a.sprint);
    }
    @Test void staleQuitCannotRestoreNewConnectionAndCloseReleasesAll() {
        var f=new MovementFreeze();var old=new Actor();var next=new Actor();next.id=old.id;
        f.update(old.player,true);f.update(next.player,true);assertEquals(.31f,old.walk);
        f.release(old.player);assertEquals(0,next.walk);assertEquals(1,f.size());
        f.close();assertEquals(.31f,next.walk);assertEquals(0,f.size());f.close();
    }
    @Test void alreadyMountedPlayerLeavesVehicleOnLogoutOrInvalidation() {
        var f=new MovementFreeze();var a=new Actor();a.insideVehicle=true;
        f.update(a.player,true);
        assertTrue(a.leftVehicle);assertFalse(a.insideVehicle);
        f.close();
    }
    @Test void gameModeChangePreservesNewFlightOwnerButRestoresUnchangedSpeeds() {
        var f=new MovementFreeze();var a=new Actor();f.update(a.player,true);a.mode=GameMode.SURVIVAL;
        f.release(a.player);assertFalse(a.allowed);assertFalse(a.flying);assertEquals(.31f,a.walk);
    }
}
