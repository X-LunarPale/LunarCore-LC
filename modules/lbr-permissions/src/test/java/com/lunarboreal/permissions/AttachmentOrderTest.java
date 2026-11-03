package com.lunarboreal.permissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AttachmentOrderTest {
    @Test void descendantsOverrideParentsDespiteLexicalOrderAndUnconfiguredIntermediate() {
        var result=AttachmentOrder.sort(Set.of("z.parent","a.child"),Map.of("z.parent",Set.of("mid"),"mid",Set.of("a.child")));
        assertEquals(List.of("z.parent","a.child"),result);
    }
    @Test void rejectsCyclesBeforeMutatingAttachment() {
        assertThrows(IllegalArgumentException.class,()->AttachmentOrder.sort(Set.of("a"),Map.of("a",Set.of("b"),"b",Set.of("a"))));
    }
}
