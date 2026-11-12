package com.lunarboreal.landmarks;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkRequestsTest {
    @Test void moderationRequiresMatchingPathAndRevisionIgnoresForgedCoordinates() {
        var request = LandmarkRequests.review("1", Map.of("row", Map.of("id", 1D, "revision", 4D,
                "owner", "attacker", "x", 123456), "reason", "More detail"), true);
        assertEquals(1, request.id()); assertEquals(4, request.revision());
        assertEquals("More detail", request.reason());
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of("row", Map.of("id", 2, "revision", 1)),
                Map.of("row", Map.of("id", 1)),
                Map.of("row", Map.of("id", 1, "revision", -1)),
                Map.of("row", Map.of("id", 1, "revision", 1.1)),
                Map.of("row", Map.of("id", 1, "revision", 1), "owner", "forged")))
            assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.review("1", bad, false));
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkRequests.review("1", Map.of("row", Map.of("id", 1, "revision", 1)), true));
    }
    @Test void badPaginationAndFiltersAreRejectedInsteadOfSilentlyDefaulting() {
        assertEquals(20, LandmarkRequests.query(null, null, null, null, null, null).size());
        for (String bad : java.util.List.of("0", "-1", "1.5", "NaN", "1000001", ""))
            assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(bad, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(null, "101", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(null, null, null, "unknown", null, null));
        assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(null, null, "x".repeat(121), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(null, null, null, null, "owner", null));
        assertThrows(IllegalArgumentException.class, () -> LandmarkRequests.query(null, null, null, null, null, "random"));
    }
    @Test void malformedJsonStatusIsPreservedAndRevisionConflictsUse409() {
        HttpException malformed = HttpException.badRequest("Malformed JSON");
        assertSame(malformed, assertThrows(HttpException.class, () -> LandmarksWeb.handle(() -> { throw malformed; })));
        HttpException conflict = assertThrows(HttpException.class,
                () -> LandmarksWeb.handle(() -> { throw Landmark.problem("conflict"); }));
        assertEquals(409, conflict.status());
        HttpException storage = assertThrows(HttpException.class,
                () -> LandmarksWeb.handle(() -> { throw new java.io.IOException("disk"); }));
        assertEquals(503, storage.status());
    }
}
