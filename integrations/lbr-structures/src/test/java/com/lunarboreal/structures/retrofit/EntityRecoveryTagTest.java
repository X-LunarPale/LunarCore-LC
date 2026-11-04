package com.lunarboreal.structures.retrofit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityRecoveryTagTest {
    @Test void passengersReceiveFreshIdentityAndRollbackOwnershipWithoutLosingExistingData() {
        var root = new CompoundTag(); root.putString("UUID", "old-root");
        var passenger = new CompoundTag(); passenger.putString("UUID", "old-passenger");
        var pdc = new CompoundTag(); pdc.putIntArray("lunarcorestructures:pharaoh_origin", new int[]{10,40,50});
        passenger.put("BukkitValues", pdc);
        var list = new ListTag(); list.add(passenger); root.put("Passengers", list);
        RetrofitManager.markEntityTree(root, "operation");
        for (var entity : java.util.List.of(root, passenger)) {
            assertFalse(entity.contains("UUID"));
            assertEquals("operation", entity.getCompoundOrEmpty("BukkitValues").getStringOr("lunarcorestructures:retrofit_job", ""));
        }
        assertArrayEquals(new int[]{10,40,50}, passenger.getCompoundOrEmpty("BukkitValues").getIntArray("lunarcorestructures:pharaoh_origin").orElseThrow());
    }
}
