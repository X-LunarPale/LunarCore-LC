package com.lunarboreal.qqauth;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class MessagesTest {
    @Test void customMultilineAndLiteralArgumentsDoNotExpandRecursively() {
        var cfg = new YamlConfiguration();
        cfg.set("messages.login.bind-required", List.of("欢迎 {player}", "验证码 {code}，群 {group}，未知 {missing}"));
        var messages = new Messages(cfg);
        assertEquals("欢迎 $\\{code}\n验证码 123456，群 QQ，未知 {missing}", messages.text("login.bind-required", "player", "$\\{code}", "code", "123456", "group", "QQ"));
    }
    @Test void missingKeysUseDefaultsAndEmptyOverridesStayEmpty() {
        var cfg = new YamlConfiguration();
        cfg.set("messages.join.instructions", "");
        var messages = new Messages(cfg);
        assertEquals("", messages.text("join.instructions"));
        assertEquals("请先登录或注册。", messages.text("login.required"));
        assertEquals("unknown-key", messages.text("unknown-key"));
        assertThrows(IllegalArgumentException.class, () -> messages.text("login.required", "odd"));
    }
    @Test void rendersAllowedFormattingAndStripsItForPlainTransport() {
        assertTrue(Messages.plain("<gradient:#7ec8ff:#b98cff>成功</gradient>").contains("成功"));
        assertEquals("成功", Messages.plain("<gradient:#7ec8ff:#b98cff>成功</gradient>"));
        assertEquals("金色", Messages.plain("&6金色"));
    }

    @Test void dynamicArgumentsAreEscapedBeforeMiniMessageRendering() {
        var cfg = new YamlConfiguration();
        cfg.set("messages.join.instructions", "欢迎 {player}");
        var messages = new Messages(cfg);
        String formatted = messages.formatted("join.instructions", "player", "<red>evil</red>");
        assertEquals("欢迎 <red\\>evil</red\\>", Messages.plain(formatted));
        assertTrue(formatted.contains("\\<red\\>"));
    }

    @Test void everyDomainStatusHasConfiguredDescription() {
        var messages = new Messages();
        for (var status : com.lunarboreal.qqauth.auth.AuthService.Result.values())
            assertNotEquals("status.auth." + status.name(), messages.text("status.auth." + status.name()));
        for (var status : com.lunarboreal.qqauth.binding.BindingService.Status.values())
            assertNotEquals("status.binding." + status.name(), messages.text("status.binding." + status.name()));
    }
}
