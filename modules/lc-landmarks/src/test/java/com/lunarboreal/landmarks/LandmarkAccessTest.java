package com.lunarboreal.landmarks;

import com.lunarboreal.api.service.LandService.LandInfo;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkAccessTest {
    private final UUID owner = UUID.randomUUID();
    private final Landmark entry = new Landmark(1, owner, "Builder", 8, "world", 10, 70, -5,
            "Garden", "", 100, 100, 1, Landmark.Status.APPROVED, "mod", "");
    private LandInfo claim(long id, UUID holder, String world, int lowY, int highY, int maxX) {
        return new LandInfo(id, "Plot", holder, "Builder", world, -10, -10, maxX, 10, lowY, highY, 1);
    }
    @Test void claimIdOwnerWorldAndAllThreeDimensionsMustStillMatch() {
        assertTrue(LandmarkAccess.ownedClaim(entry, List.of(claim(8, owner, "world", 70, 70, 10))).isPresent());
        assertTrue(LandmarkAccess.ownedClaim(entry, List.of()).isEmpty());
        for (LandInfo changed : List.of(claim(9, owner, "world", 0, 100, 10),
                claim(8, UUID.randomUUID(), "world", 0, 100, 10),
                claim(8, owner, "other", 0, 100, 10), claim(8, owner, "world", 71, 100, 10),
                claim(8, owner, "world", 0, 69, 10), claim(8, owner, "world", 0, 100, 9)))
            assertTrue(LandmarkAccess.ownedClaim(entry, List.of(changed)).isEmpty());
    }
}
