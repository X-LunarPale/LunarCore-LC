package com.lunarboreal.core.web;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/** config.yml 中 web 段的快照。 */
record WebConfig(
        boolean enabled,
        String host,
        int port,
        String basePath,
        String publicUrl,
        boolean httpsEnabled,
        String keystore,
        String keystorePassword,
        String jwtSecret,
        int sessionHours,
        List<String> corsOrigins,
        int loginMaxAttempts,
        int loginLockMinutes,
        List<String> ipWhitelist,
        List<String> trustedProxies
) {

    static WebConfig from(FileConfiguration root) {
        ConfigurationSection c = root.getConfigurationSection("web");
        if (c == null) c = new YamlConfiguration();
        return new WebConfig(
                c.getBoolean("enabled", true),
                c.getString("host", "0.0.0.0"),
                c.getInt("port", 8765),
                normalizeBasePath(c.getString("base-path", "")),
                c.getString("public-url", ""),
                c.getBoolean("https.enabled", false),
                c.getString("https.keystore", "web.p12"),
                c.getString("https.keystore-password", "changeit"),
                c.getString("jwt-secret", ""),
                Math.max(1, c.getInt("session-hours", 24)),
                c.getStringList("cors-origins"),
                Math.max(1, c.getInt("login-max-attempts", 5)),
                Math.max(1, c.getInt("login-lock-minutes", 10)),
                c.getStringList("ip-whitelist"),
                // 受信任的反向代理（只有来自这些地址的请求才解析 X-Forwarded-For），默认仅本机
                c.isList("trusted-proxies") ? c.getStringList("trusted-proxies") : List.of("127.0.0.1", "::1")
        );
    }

    /** "" 或 "/lc"（无结尾斜杠）。 */
    static String normalizeBasePath(String p) {
        if (p == null) return "";
        p = p.trim().replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return "";
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }
}
