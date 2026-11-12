package com.lunarboreal.qqauth.binding;

import java.util.Objects;
import java.util.UUID;

/** Durable Minecraft-to-QQ identity. The QQ value is an opaque platform identifier. */
public record Binding(UUID uuid, String playerName, String qq, String group, long boundAt) {
    public Binding {
        Objects.requireNonNull(uuid, "uuid");
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("playerName");
        require(qq, "qq");
        require(group, "group");
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) throw new IllegalArgumentException(field);
    }
}
