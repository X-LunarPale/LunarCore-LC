package com.lunarboreal.qqauth;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** Saves settings; message changes apply without restarting authentication. */
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
            Object value = displayValue(config.get(key, field.getValue()), field.getValue());
            if (key.endsWith(".token")) { configured.put(key, value instanceof String text && !text.isBlank()); value = ""; }
            values.put(key, value);
        }
        var quotas = config.getConfigurationSection("binding.per-qq-limits");
        Map<String, Object> quotaValues = new LinkedHashMap<>();
        if (quotas != null) quotas.getValues(false).forEach((qq, limit) -> quotaValues.put(qq, displayValue(limit, 1)));
        values.put("binding.per-qq-limits", quotaValues);
        Map<String, String> messages = new TreeMap<>();
        for (String key : defaults.getConfigurationSection("messages").getKeys(true)) {
            String path = "messages." + key;
            if (defaults.isString(path)) messages.put(key, config.isList(path)
                    ? String.join("\n", config.getStringList(path)) : config.getString(path, defaults.getString(path)));
        }
        return Map.of("values", values, "messages", messages, "configuredSecrets", configured,
                "revision", digest(source), "requiresReload", requiresReload(config));
    }

    private boolean requiresReload(YamlConfiguration saved) {
        for (var field : fields.entrySet()) {
            if (!Objects.equals(effectiveValue(saved, field.getKey(), field.getValue()),
                    effectiveValue(module.config(), field.getKey(), field.getValue()))) return true;
        }
        var savedQuotas = saved.getConfigurationSection("binding.per-qq-limits");
        var liveQuotas = module.config().getConfigurationSection("binding.per-qq-limits");
        return !Objects.equals(savedQuotas == null ? Map.of() : savedQuotas.getValues(false),
                liveQuotas == null ? Map.of() : liveQuotas.getValues(false));
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
        String mode = String.valueOf(values.containsKey("integration.mode")
                ? values.get("integration.mode") : next.getString("integration.mode", "astrbot"));
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
            value = normalize(key, prototype, value, mode);
            if (prototype instanceof String) {
                String text = (String) value;
                if (key.endsWith(".token")) {
                    if (clearSecrets.contains(key) && !text.isEmpty()) throw HttpException.badRequest("不能同时设置并清除密钥");
                    if (!clearSecrets.contains(key) && text.isEmpty()) continue;
                }
            }
            next.set(key, value);
        }
        for (Object item : clearSecrets) next.set((String) item, "");
        for (var field : fields.entrySet())
            next.set(field.getKey(), normalize(field.getKey(), field.getValue(), next.get(field.getKey(), field.getValue()), mode));
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
        module.applyMessages(next);
        return read();
    }

    private static Object displayValue(Object value, Object prototype) {
        if (prototype instanceof Number) {
            try { return decimal(value).intValueExact(); }
            catch (IllegalArgumentException | ArithmeticException ignored) { return value; }
        }
        if (prototype instanceof Boolean && value instanceof String text) {
            if (text.equalsIgnoreCase("true")) return true;
            if (text.equalsIgnoreCase("false")) return false;
        }
        if (prototype instanceof String && (value instanceof Number || value instanceof Boolean)) return scalarText(value);
        return value;
    }

    private static Object effectiveValue(YamlConfiguration config, String key, Object prototype) {
        if (prototype instanceof Number n) return config.getInt(key, n.intValue());
        if (prototype instanceof Boolean b) return config.getBoolean(key, b);
        if (prototype instanceof String text) return config.getString(key, text);
        return config.get(key, prototype);
    }

    private static Object normalize(String key, Object prototype, Object value, String mode) {
        if (prototype instanceof Number) {
            if ((key.startsWith("integration.astrbot.") && !mode.equals("astrbot"))
                    || (key.startsWith("integration.onebot.") && !mode.equals("onebot"))) {
                Object normalized = displayValue(value, prototype);
                if (normalized instanceof Number || normalized instanceof String) return normalized;
                throw HttpException.badRequest(label(key) + "必须为整数");
            }
            int min = switch (key) {
                case "binding.code-length" -> 6;
                case "auth.bcrypt-cost" -> 4;
                case "auth.max-attempt-entries" -> 2;
                default -> 1;
            };
            int max = switch (key) {
                case "binding.code-length" -> 8;
                case "auth.bcrypt-cost" -> 16;
                case "auth.min-password-length", "auth.max-password-length" -> 64;
                case "integration.astrbot.port" -> 65535;
                case "integration.onebot.reconnect-seconds" -> 86400;
                default -> 1_000_000;
            };
            return number(value, min, max, key);
        }
        if (prototype instanceof Boolean) {
            Object normalized = displayValue(value, prototype);
            if (!(normalized instanceof Boolean)) throw HttpException.badRequest(label(key) + "必须为开关");
            return normalized;
        }
        if (prototype instanceof String) {
            Object normalized = displayValue(value, prototype);
            if (!(normalized instanceof String text) || text.length() > 2048 || text.contains("\r") || text.contains("\n"))
                throw HttpException.badRequest(label(key) + "文本无效");
            return normalized;
        }
        if (prototype instanceof List && (!(value instanceof List<?> list) || list.size() > 100 || list.stream().anyMatch(
                item -> !(item instanceof String text) || !text.matches("[a-zA-Z0-9_:-]{1,64}"))))
            throw HttpException.badRequest("允许命令列表无效");
        return value;
    }

    private static String scalarText(Object value) {
        if (value instanceof Number) {
            try { return decimal(value).stripTrailingZeros().toPlainString(); }
            catch (IllegalArgumentException ignored) { }
        }
        return value.toString();
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException();
        return new BigDecimal(value.toString().trim());
    }

    private static int number(Object value, int min, int max, String key) {
        try {
            int result = decimal(value).intValueExact();
            if (result >= min && result <= max) return result;
        } catch (IllegalArgumentException | ArithmeticException ignored) { }
        throw HttpException.badRequest(label(key) + "必须为 " + min + " 到 " + max + " 的整数");
    }

    private static String label(String key) {
        return switch (key) {
            case "auth.identity-mode" -> "账号身份模式";
            case "auth.min-password-length" -> "最短密码长度";
            case "auth.max-password-length" -> "最长密码长度";
            case "auth.bcrypt-cost" -> "密码哈希强度";
            case "auth.max-attempts" -> "登录尝试上限";
            case "auth.attempt-window-seconds" -> "登录限流窗口";
            case "auth.max-attempt-entries" -> "限流记录上限";
            case "auth.max-accounts-per-ip" -> "每 IP 注册上限";
            case "auth.sessions.enabled" -> "启用免密会话";
            case "auth.sessions.ttl-seconds" -> "免密会话有效期";
            case "auth.sessions.max-sessions" -> "会话数量上限";
            case "binding.code-length" -> "验证码长度";
            case "binding.bind-ttl-seconds" -> "绑定码有效期";
            case "binding.unbind-ttl-seconds" -> "解绑码有效期";
            case "binding.reset-ttl-seconds" -> "改密码有效期";
            case "binding.max-pending-codes" -> "待处理验证码上限";
            case "binding.max-per-qq", "binding.per-qq-limits" -> "QQ 绑定上限";
            case "binding.required" -> "强制 QQ 验证";
            case "binding.reminder.enabled" -> "每日绑定提醒";
            case "binding.reminder.timezone" -> "每日提醒时区";
            case "integration.astrbot.port" -> "QQAuth 桥接端口";
            case "integration.onebot.reconnect-seconds" -> "OneBot 重连间隔";
            default -> key;
        };
    }

    private static void validate(YamlConfiguration c) {
        if (!Set.of("auto", "uuid", "name").contains(c.getString("auth.identity-mode", "auto")))
            throw HttpException.badRequest("账号身份模式无效");
        String mode = c.getString("integration.mode", "");
        if (!Set.of("astrbot", "onebot", "disabled").contains(mode))
            throw HttpException.badRequest("桥接模式无效");
        if (mode.equals("astrbot") && c.getString("integration.astrbot.token", "").isBlank())
            throw HttpException.badRequest("AstrBot 密钥不能为空");
        if (mode.equals("onebot")) {
            try {
                new com.lunarboreal.qqauth.bridge.OneBotSettings(
                        java.net.URI.create(c.getString("integration.onebot.endpoint", "")),
                        c.getString("integration.onebot.token", ""),
                        java.time.Duration.ofSeconds(c.getLong("integration.onebot.reconnect-seconds")));
            } catch (RuntimeException ex) { throw HttpException.badRequest("OneBot 地址无效，请使用不含账号、查询参数或片段的 ws 或 wss 地址"); }
        }
        String timezone = c.getString("binding.reminder.timezone", "").trim();
        if (!timezone.isEmpty()) {
            try { java.time.ZoneId.of(timezone); }
            catch (java.time.DateTimeException ex) { throw HttpException.badRequest("每日提醒时区无效，例如 Asia/Shanghai；留空使用服务器时区"); }
        }
        if (c.getInt("auth.min-password-length") > c.getInt("auth.max-password-length"))
            throw HttpException.badRequest("最短密码长度不能大于最长密码长度");
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
