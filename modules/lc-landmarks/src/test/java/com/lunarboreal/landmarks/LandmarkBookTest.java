package com.lunarboreal.landmarks;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkBookTest {
    private final UUID owner = UUID.randomUUID();
    private LandmarkBook submit(LandmarkBook book, int limit) {
        return book.submit(owner, "Builder", 8, "world", 1, 64, 2, "Glass garden", "An open greenhouse", limit, 100);
    }
    @Test void editsInvalidateOldReviewAndPublication() {
        LandmarkBook pending = submit(LandmarkBook.empty(), 3);
        LandmarkBook approved = pending.moderate(1, 1, Landmark.Status.APPROVED, "mod", "", 200);
        assertEquals(Landmark.Status.APPROVED, approved.find(1).status());
        LandmarkBook changed = approved.edit(1, owner, 2, "New garden", "Updated", 3, 300);
        assertEquals(Landmark.Status.PENDING, changed.find(1).status());
        assertEquals(3, changed.find(1).revision());
        assertEquals("", changed.find(1).reviewer());
        assertEquals("conflict", assertThrows(IllegalArgumentException.class,
                () -> changed.moderate(1, 2, Landmark.Status.APPROVED, "mod", "", 400)).getMessage());
        assertEquals("New garden", changed.find(1).title());
        assertEquals("Glass garden", pending.find(1).title());
    }
    @Test void ownerIsCheckedForBothEditsAndWithdrawals() {
        LandmarkBook pending = submit(LandmarkBook.empty(), 3);
        UUID other = UUID.randomUUID();
        assertEquals("owner", assertThrows(IllegalArgumentException.class,
                () -> pending.edit(1, other, 1, "Stolen", "", 3, 200)).getMessage());
        assertEquals("owner", assertThrows(IllegalArgumentException.class,
                () -> pending.withdraw(1, other, 1, 200)).getMessage());
    }
    @Test void withdrawalFreesQuotaButReactivationCannotBypassIt() {
        LandmarkBook pending = submit(LandmarkBook.empty(), 1);
        assertEquals("limit", assertThrows(IllegalArgumentException.class, () -> submit(pending, 1)).getMessage());
        LandmarkBook withdrawn = pending.withdraw(1, owner, 1, 200);
        LandmarkBook replacement = submit(withdrawn, 1);
        assertEquals(2, replacement.entries().size());
        assertEquals("limit", assertThrows(IllegalArgumentException.class,
                () -> replacement.edit(1, owner, 2, "Return", "", 1, 300)).getMessage());
        LandmarkBook cleared = replacement.withdraw(2, owner, 1, 300);
        assertEquals(Landmark.Status.PENDING, cleared.edit(1, owner, 2, "Return", "", 1, 400).find(1).status());
    }
    @Test void reviewNeedsExpectedStateAndRejectionReason() {
        LandmarkBook pending = submit(LandmarkBook.empty(), 3);
        assertEquals("reason", assertThrows(IllegalArgumentException.class,
                () -> pending.moderate(1, 1, Landmark.Status.REJECTED, "mod", " ", 200)).getMessage());
        LandmarkBook rejected = pending.moderate(1, 1, Landmark.Status.REJECTED, "mod", "Please add detail", 200);
        assertEquals("Please add detail", rejected.find(1).reviewNote());
        assertEquals("state", assertThrows(IllegalArgumentException.class,
                () -> rejected.moderate(1, 2, Landmark.Status.APPROVED, "mod", "", 300)).getMessage());
    }
    @Test void rejectsFormattingAndControlSequencesInPlayerText() {
        for (String text : new String[]{"<click:run_command:/op>", "hello\u0000world", "a\u202Eb", "\u00A7aTitle", "x".repeat(49)})
            assertThrows(IllegalArgumentException.class, () -> LandmarkBook.empty().submit(owner,
                    "Builder", 8, "world", 0, 64, 0, text, "", 3, 100));
    }
}
