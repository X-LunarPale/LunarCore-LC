package com.lunarboreal.playertitleweb;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicTitleApiTest {
    public static final class PublicOwnedDto {
        public Integer getId() { return 987; }
        public Integer getTitleId() { return 42; }
        public String getTitleName() { return "&6Owner"; }
        public String getDescription() { return "description"; }
        public Date getExpirationTime() { return new Date(1234L); }
        public Boolean getIsUseShow() { return true; }
        public UUID getPlayerUuid() { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
        public String getPlayerName() { return "Player"; }
    }
    @Test void keepsOwnershipIdDistinctAndConvertsOnlyPublicDtoValues() throws Exception {
        Map<String, Object> row = PublicTitleApi.ownedRow(new PublicOwnedDto());
        assertEquals(987, row.get("id"));
        assertEquals(42, row.get("titleId"));
        assertEquals(1234L, row.get("expirationTime"));
        assertEquals("00000000-0000-0000-0000-000000000001", row.get("playerUuid"));
        assertEquals("&6Owner", row.get("titleName"));
        assertEquals(8, row.size());
    }
    @Test void declaresActualStructuredVisualSections() {
        var panel = PlayerTitleWebPlugin.panel();
        assertEquals("playertitle.view", panel.permission());
        assertEquals(5, panel.sections().size());
        assertTrue(panel.sections().stream().anyMatch(section -> section instanceof com.lunarboreal.api.web.WebPanel.Iframe));
        assertEquals(3, panel.sections().stream().filter(section -> section instanceof com.lunarboreal.api.web.WebPanel.Table).count());
    }
}
