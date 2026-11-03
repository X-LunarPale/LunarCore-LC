package com.lunarboreal.qqauth;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** Edits the persisted configuration without changing the live authentication snapshot. */
final class QQAuthConfig {
    private final QQAuthModule module;
    private final YamlConfiguration defaults;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    QQAuthConfig(QQAuthModule module) {
        this.module = module;
        try (var input = QQAuthConfig.class.getResourceAsStream("/config.yml")) {
            if (input == null) throw new IllegalStateException("Missing config");
            defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !key.startsWith("messages.") && !key.equals("auth.enabled"))
                fields.put(key, defaults.get(key));
        }
    }

    private java.nio.file.Path file() { return new java.io.File(module.dataFolder(), "config.yml").toPath(); }

    synchronized Map<String, Object> read() throws Exception {
        String source = Files.readString(file());
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(source);
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> configured = new LinkedHashMap<>();
        for (var field : fields.entrySet()) {
            String key = field.getKey();
            Object value = config.get(key, field.getValue());
            if (key.endsWith(".token")) { configured.put(key, value instanceof String text && !text.isBlank()); value = ""; }
            values.put(key, value);
        }
        var quotas = config.getConfigurationSection("binding.per-qq-limits");
        values.put("binding.per-qq-limits", quotas == null ? Map.of() : quotas.getValues(false));
        Map<String, String> messages = new TreeMap<>();
        for (String key : defaults.getConfigurationSection("messages").getKeys(true)) {
            String path = "messages." + key;
            if (defaults.isString(path)) messages.put(key, config.isList(path)
                    ? String.join("\n", config.getStringList(path)) : config.getString(path, defaults.getString(path)));
        }
        return Map.of("values", values, "messages", messages, "configuredSecrets", configured,
                "revision", digest(source), "requiresReload", true);
    }

    synchronized Map<String, Object> save(Map<String, Object> body) throws Exception {
        if (body.keySet().stream().anyMatch(k -> !Set.of("revision", "values", "messages", "clearSecrets").contains(k)))
            throw HttpException.badRequest("未知配置字段");
        String source = Files.readString(file());
        if (!Objects.equals(body.get("revision"), digest(source))) throw new HttpException(409, "配置已变化，请重新加载后再保存");
        if (!(body.get("values") instanceof Map<?, ?> values) || !(body.get("messages") instanceof Map<?, ?> messages))
            throw HttpException.badRequest("配置格式无效");
        Object clears = body.getOrDefault("clearSecrets", List.of());
        if (!(clears instanceof List<?> clearSecrets) || clearSecrets.stream().anyMatch(k ->
                !List.of("integration.astrbot.token", "integration.onebot.token").contains(k)))
            throw HttpException.badRequest("清除密钥列表无效");
        YamlConfiguration next = new YamlConfiguration();
        next.loadFromString(source);
        next.setDefaults(defaults);
        next.options().copyDefaults(true);
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw HttpException.badRequest("配置键无效");
            Object value = entry.getValue();
            if (key.equals("binding.per-qq-limits")) {
                if (!(value instanceof Map<?, ?> limits) || limits.size() > 1000) throw HttpException.badRequest("QQ 配额格式无效");
                next.set(key, null);
                for (var quota : limits.entrySet()) {
                    if (!(quota.getKey() instanceof String qq) || !qq.matches("[0-9]{5,20}"))
                        throw HttpException.badRequest("QQ 号码无效");
                    next.set(key + "." + qq, number(quota.getValue(), 1, 10000, key));
                }
                continue;
            }
            Object prototype = fields.get(key);
            if (prototype == null) throw HttpException.badRequest("不支持的配置项：" + key);
            if (prototype instanceof Boolean && !(value instanceof Boolean)) throw HttpException.badRequest(key + " 必须为开关");
            if (prototype instanceof Number) value = number(value, 1, 1_000_000, key);
            if (prototype instanceof String) {
                if (!(value instanceof String text) || text.length() > 2048 || text.contains("\r") || text.contains("\n"))
                    throw HttpException.badRequest(key + " 文本无效");
                if (key.endsWith(".token")) {
                    if (clearSecrets.contains(key) && !text.isEmpty()) throw HttpException.badRequest("不能同时设置并清除密钥");
                    if (!clearSecrets.contains(key) && text.isEmpty()) continue;
                }
            }
            if (prototype instanceof List) {
                if (!(value instanceof List<?> list) || list.size() > 100 || list.stream().anyMatch(
                        item -> !(item instanceof String text) || !text.matches("[a-zA-Z0-9_:-]{1,64}")))
                    throw HttpException.badRequest("允许命令列表无效");
            }
            next.set(key, value);
        }
        for (Object item : clearSecrets) next.set((String) item, "");
        for (var entry : messages.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !defaults.isString("messages." + key)
                    || !(entry.getValue() instanceof String text) || text.length() > 4096)
                throw HttpException.badRequest("提示消息无效");
            next.set("messages." + key, entry.getValue());
        }
        validate(next);
        java.nio.file.Path temporary = Files.createTempFile(file().getParent(), "qqauth-", ".tmp");
        try {
            Files.writeString(temporary, next.saveToString());
            try { Files.move(temporary, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return read();
    }

    private static int number(Object value, int min, int max, String key) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max) throw HttpException.badRequest(key + " 数值超出范围");
        return n.intValue();
    }

    private static void validate(YamlConfiguration c) {
        if (!Set.of("astrbot", "onebot", "disabled").contains(c.getString("integration.mode", "")))
            throw HttpException.badRequest("桥接模式无效");
        if (c.getString("integration.mode", "").equals("astrbot") && c.getString("integration.astrbot.token", "").length() < 16)
            throw HttpException.badRequest("AstrBot 密钥至少 16 位");
        if (c.getInt("integration.astrbot.port") > 65535) throw HttpException.badRequest("端口无效");
        try {
            var uri = java.net.URI.create(c.getString("integration.onebot.endpoint", ""));
            if (!Set.of("ws", "wss").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw HttpException.badRequest("OneBot 地址无效"); }
        try {
            new com.lunarboreal.qqauth.auth.AuthPolicy(c.getInt("auth.bcrypt-cost"), c.getInt("auth.min-password-length"),
                    c.getInt("auth.max-password-length"), c.getInt("auth.max-accounts-per-ip"), c.getInt("auth.max-attempts"),
                    java.time.Duration.ofSeconds(c.getLong("auth.attempt-window-seconds")), c.getInt("auth.max-attempt-entries"),
                    c.getBoolean("auth.sessions.enabled"), java.time.Duration.ofSeconds(c.getLong("auth.sessions.ttl-seconds")),
                    c.getInt("auth.sessions.max-sessions"));
            new com.lunarboreal.qqauth.binding.BindingSettings(c.getInt("binding.code-length"),
                    java.time.Duration.ofSeconds(c.getLong("binding.bind-ttl-seconds")),
                    java.time.Duration.ofSeconds(c.getLong("binding.unbind-ttl-seconds")),
                    java.time.Duration.ofSeconds(c.getLong("binding.reset-ttl-seconds")), c.getInt("binding.max-pending-codes"),
                    c.getInt("binding.max-per-qq"), Map.of());
        } catch (IllegalArgumentException ex) { throw HttpException.badRequest("密码、会话或验证码策略无效"); }
    }

    static String digest(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
