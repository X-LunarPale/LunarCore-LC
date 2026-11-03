package com.lunarboreal.essentials;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EssentialsLanguageTest {

    @Test
    void defaultMessagesDoNotIncludeModulePrefix() throws Exception {
        for (String locale : new String[]{"zh_CN", "en_US"}) {
            try (InputStream input = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
                assertNotNull(input, locale);
                String messages = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(messages.contains("<prefix>"), locale);
                assertFalse(messages.contains("prefix:"), locale);
            }
        }
    }
}
