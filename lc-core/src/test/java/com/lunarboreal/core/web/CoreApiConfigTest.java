package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CoreApiConfigTest {
    @Test
    void rejectsStaleConfigEvenWhenOnlyAMaskedFieldChanged() {
        String original = "web:\n  token: original-placeholder\n";
        String updated = "web:\n  token: new-placeholder\n";
        String originalFingerprint = CoreApi.configFingerprint(original);

        assertEquals(SecretMask.mask(original), SecretMask.mask(updated));
        assertDoesNotThrow(() -> CoreApi.requireConfigFingerprint(originalFingerprint, original));
        HttpException conflict = assertThrows(HttpException.class,
                () -> CoreApi.requireConfigFingerprint(originalFingerprint, updated));
        assertEquals(409, conflict.status());
        assertDoesNotThrow(() -> CoreApi.requireConfigFingerprint(CoreApi.configFingerprint(updated), updated));
    }
}
