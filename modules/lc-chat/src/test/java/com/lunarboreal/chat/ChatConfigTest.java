package com.lunarboreal.chat;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatConfigTest {
    @Test void validatesAnnouncementAndCooldownBeforeApplyingConfig() {
        assertDoesNotThrow(() -> ChatModule.validateChatConfig(Map.of(
                "announcer", false, "announceInterval", 10, "cooldownMs", 0, "announcements", "")));
        assertDoesNotThrow(() -> ChatModule.validateChatConfig(Map.of("announceInterval", 300.0)));
        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("announcer", "true"),
                Map.<String, Object>of("cooldownMs", "1500"),
                Map.<String, Object>of("cooldownMs", -1),
                Map.<String, Object>of("announceInterval", 9),
                Map.<String, Object>of("announceInterval", 10.5),
                Map.<String, Object>of("announceInterval", Long.MAX_VALUE),
                Map.<String, Object>of("announceInterval", Double.POSITIVE_INFINITY),
                Map.<String, Object>of("announcements", List.of("message")))) {
            assertThrows(HttpException.class, () -> ChatModule.validateChatConfig(body), body.toString());
        }
    }

    @Test void manualAnnouncementIntervalsCannotOverflowSchedulerTicks() {
        assertEquals(200, ChatModule.announcerPeriod(Long.MIN_VALUE));
        assertEquals(6000, ChatModule.announcerPeriod(300));
        assertEquals(Long.MAX_VALUE / 20 * 20, ChatModule.announcerPeriod(Long.MAX_VALUE));
    }
}
