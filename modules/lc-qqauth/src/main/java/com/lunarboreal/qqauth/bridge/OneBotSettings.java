package com.lunarboreal.qqauth.bridge;

import java.net.URI;
import java.time.Duration;

public record OneBotSettings(URI endpoint, String token, Duration reconnectDelay,
                             int maxMessageChars, int workers, int queueCapacity) {
    public OneBotSettings {
        if (endpoint == null || !("ws".equalsIgnoreCase(endpoint.getScheme()) || "wss".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null
                || reconnectDelay == null || reconnectDelay.isNegative() || reconnectDelay.isZero()
                || reconnectDelay.compareTo(Duration.ofDays(1)) > 0
                || maxMessageChars < 1 || maxMessageChars > 65536 || workers < 1 || workers > 64
                || queueCapacity < 1 || queueCapacity > 4096)
            throw new IllegalArgumentException("Invalid OneBot settings");
        if (token == null) token = "";
        if (token.contains("\r") || token.contains("\n")) throw new IllegalArgumentException("Invalid OneBot token");
    }
    public OneBotSettings(URI endpoint, String token, Duration reconnectDelay) {
        this(endpoint, token, reconnectDelay, 65536, 1, 32);
    }
    @Override public String toString() { return "OneBotSettings[endpoint=<configured>, token=<redacted>]"; }
}
