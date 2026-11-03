package com.lunarboreal.playertitleweb;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InputsTest {
    @Test void rejectsMalformedNumbersAndIdentity() {
        for (Object value : new Object[]{1.5, Double.NaN, Double.POSITIVE_INFINITY, "1", -1, 2147483648L})
            assertThrows(IllegalArgumentException.class, () -> Inputs.integer(Map.of("id", value), "id", 1, Integer.MAX_VALUE));
        assertEquals(7, Inputs.integer(Map.of("id", 7.0), "id", 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Inputs.uuid("1-1-1-1-1"));
        assertDoesNotThrow(() -> Inputs.uuid("00000000-0000-0000-0000-000000000001"));
    }
    @Test void validatesTextAndBooleanWithoutExecutingMarkup() {
        assertEquals("<script>alert(1)</script>", Inputs.text(Map.of("name", "<script>alert(1)</script>"), "name", 256, false));
        assertThrows(IllegalArgumentException.class, () -> Inputs.text(Map.of("name", "x\u0000"), "name", 256, false));
        assertThrows(IllegalArgumentException.class, () -> Inputs.text(Map.of("name", " "), "name", 256, false));
        assertThrows(IllegalArgumentException.class, () -> Inputs.bool(Map.of("prefix", "true"), "prefix"));
    }
}
