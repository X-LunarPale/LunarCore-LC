package com.lunarboreal.core.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CoreApiWhitelistTest {

    private static final UUID PLAYER_UUID = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    @Test
    void whitelistProjectionUsesCachedNameWhenPaperEntryHasOnlyUuid() {
        assertEquals("Core_Case", CoreApi.whitelistName(PLAYER_UUID, null, "Core_Case"));
        assertEquals("Core_Case", CoreApi.whitelistName(PLAYER_UUID, PLAYER_UUID.toString(), "Core_Case"));
    }

    @Test
    void whitelistProjectionPrefersPaperNameAndFallsBackToUuidForUnknownPlayers() {
        assertEquals("Paper_Case", CoreApi.whitelistName(PLAYER_UUID, "Paper_Case", "Core_Case"));
        assertEquals(PLAYER_UUID.toString(), CoreApi.whitelistName(PLAYER_UUID, null, PLAYER_UUID.toString()));
    }
}
