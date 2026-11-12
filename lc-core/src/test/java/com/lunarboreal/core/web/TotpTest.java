package com.lunarboreal.core.web;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    @Test void matchesRfc6238Sha1Vectors() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        long[] timestamps = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < timestamps.length; i++) assertEquals(expected[i], Totp.code(key, timestamps[i] / 30, 8));
        assertArrayEquals(key, Totp.decode(Totp.base32(key)));
    }

    @Test void rejectsMalformedExpiredAndReplayedCodes() {
        String secret = Totp.newSecret();
        long now = 1_800_000_000_000L, step = now / 30_000;
        String code = Totp.code(Totp.decode(secret), step, 6);
        assertEquals(step, Totp.matchingStep(secret, code, now, step - 1));
        assertEquals(-1, Totp.matchingStep(secret, code, now, step));
        assertEquals(-1, Totp.matchingStep(secret, code, now + 90_000, -1));
        assertEquals(-1, Totp.matchingStep(secret, null, now, -1));
        assertEquals(-1, Totp.matchingStep(secret, "1234567", now, -1));
        assertEquals(-1, Totp.matchingStep(secret, "<1234>", now, -1));
        assertEquals(step, Totp.matchingStep(secret, code, now + 30_000, -1));
    }
}
