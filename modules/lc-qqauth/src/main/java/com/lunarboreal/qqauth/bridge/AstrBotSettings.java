package com.lunarboreal.qqauth.bridge;

public record AstrBotSettings(String bindAddress, int port, String token,
                              int maxBodyBytes, int workers, int queueCapacity) {
    public AstrBotSettings {
        if (bindAddress == null || bindAddress.isBlank()) bindAddress = "127.0.0.1";
        if (port < 0 || port > 65535 || token == null || token.isBlank()
                || maxBodyBytes < 1 || maxBodyBytes > 65536 || workers < 1 || workers > 64
                || queueCapacity < 1 || queueCapacity > 4096)
            throw new IllegalArgumentException("Invalid AstrBot settings");
    }
    public AstrBotSettings(String bindAddress, int port, String token) {
        this(bindAddress, port, token, 65536, 4, 32);
    }
    @Override public String toString() { return "AstrBotSettings[bindAddress=" + bindAddress + ", port=" + port + ", token=<redacted>]"; }
}
