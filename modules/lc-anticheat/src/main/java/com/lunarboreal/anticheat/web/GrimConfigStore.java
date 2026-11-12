package com.lunarboreal.anticheat.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Edits existing Grim files only. Call from the Web worker, never the server tick. */
final class GrimConfigStore {
    private static final Object SAVE_LOCK = new Object();
    private static final int MAX_BYTES = 512 * 1024, MAX_FIELDS = 2048, MAX_BACKUPS = 5;
    private static final List<ConfigFile> FILES = List.of(
            new ConfigFile("config", "config.yml", "主配置"),
            new ConfigFile("punishments", "punishments.yml", "处罚规则"),
            new ConfigFile("messages", "messages.yml", "消息文本"),
            new ConfigFile("discord", "discord.yml", "Discord 告警"),
            new ConfigFile("database", "database.yml", "历史存储"),
            new ConfigFile("database-sqlite", "databases/sqlite.yml", "SQLite 数据库"),
            new ConfigFile("database-mysql", "databases/mysql.yml", "MySQL 数据库"),
            new ConfigFile("database-postgres", "databases/postgres.yml", "PostgreSQL 数据库"),
            new ConfigFile("database-mongo", "databases/mongo.yml", "MongoDB 数据库"),
            new ConfigFile("database-redis", "databases/redis.yml", "Redis 数据库"));
    private final Path root;

    GrimConfigStore(Path dataFolder) {
        root = dataFolder.toAbsolutePath().normalize();
    }

    Map<String, Object> directory() throws IOException {
        synchronized (SAVE_LOCK) {
            realRoot();
            var files = new ArrayList<Map<String, Object>>();
            String first = null;
            for (ConfigFile file : FILES) {
                boolean available;
                try { file(file.id); available = true; }
                catch (HttpException missing) {
                    if (missing.status() != 404) throw missing;
                    available = false;
                }
                files.add(Map.of("id", file.id, "name", file.name, "label", file.label,
                        "available", available));
                if (available && first == null) first = file.id;
            }
            var response = new LinkedHashMap<String, Object>();
            response.put("files", files);
            response.put("selected", first == null ? null : response(first, load(first)));
            return response;
        }
    }

    Map<String, Object> read(String id) throws IOException {
        synchronized (SAVE_LOCK) { return response(id, load(id)); }
    }

    Map<String, Object> patch(String id, Map<String, Object> request) throws IOException {
        synchronized (SAVE_LOCK) {
            descriptor(id);
            if (request == null || !request.keySet().equals(Set.of("fingerprint", "changes")))
                throw bad("请求仅允许 fingerprint 和 changes 字段");
            if (!(request.get("fingerprint") instanceof String expected)
                    || !expected.matches("sha256:[a-f0-9]{64}"))
                throw bad("缺少有效的配置指纹，请重新读取");
            if (!(request.get("changes") instanceof Map<?, ?> changes)
                    || changes.isEmpty() || changes.size() > MAX_FIELDS)
                throw bad("changes 必须是非空配置键值对象");
            Snapshot original = load(id);
            if (!expected.equals(original.fingerprint)) throw conflict();
            var fields = new LinkedHashMap<String, GrimConfigSchema.Field>();
            for (var field : GrimConfigSchema.fields(id, original.yaml)) fields.put(field.key(), field);
            var validated = new LinkedHashMap<String, Object>();
            for (var entry : changes.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw bad("配置键必须为字符串");
                var field = fields.get(key);
                if (field == null || field.readOnly()) throw bad("不允许修改配置项：" + key);
                Object value = field.validate(entry.getValue());
                validateSize(value);
                validated.put(key, value);
            }
            var changed = new ArrayList<String>();
            for (var entry : validated.entrySet()) {
                if (!equivalent(original.yaml.get(entry.getKey()), entry.getValue())) {
                    original.yaml.set(entry.getKey(), entry.getValue());
                    changed.add(entry.getKey());
                }
            }
            if (changed.isEmpty()) return saved(id, original, changed);
            GrimConfigSchema.validateRelations(original.yaml);
            byte[] updated = original.yaml.saveToString().getBytes(StandardCharsets.UTF_8);
            if (updated.length > MAX_BYTES) throw bad("配置文件过大，不支持在线保存");
            Snapshot replacement = new Snapshot(updated, parse(updated), fingerprint(updated));
            Path target = file(id);
            Path temporary = Files.createTempFile(target.getParent(), ".grim-config-", ".tmp");
            try {
                writeForced(temporary, updated);
                if (!expected.equals(load(id).fingerprint)) throw conflict();
                Path backup = Files.createTempFile(target.getParent(),
                        target.getFileName() + ".lc-backup-", ".bak");
                try { writeForced(backup, original.bytes); }
                catch (IOException cause) { Files.deleteIfExists(backup); throw cause; }
                // External file editors do not share our lock; recheck immediately before replacement.
                if (!expected.equals(load(id).fingerprint)) throw conflict();
                Path verifiedTarget = file(id);
                if (!target.equals(verifiedTarget)) throw conflict();
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                pruneBackups(target);
                return saved(id, replacement, changed);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Map<String, Object> saved(String id, Snapshot snapshot, List<String> changed) {
        return Map.of("ok", true, "message", "GrimAC 配置已保存，请重载 GrimAC 后生效",
                "changedKeys", List.copyOf(changed), "snapshot", response(id, snapshot));
    }

    private Map<String, Object> response(String id, Snapshot snapshot) {
        ConfigFile file = descriptor(id);
        var metadata = new ArrayList<Map<String, Object>>();
        var values = new LinkedHashMap<String, Object>();
        for (var field : GrimConfigSchema.fields(id, snapshot.yaml)) {
            var details = new LinkedHashMap<>(field.metadata());
            Object value = snapshot.yaml.get(field.key());
            if (field.secret()) {
                details.put("configured", value != null && !value.toString().isEmpty());
                values.put(field.key(), "");
            } else if (Boolean.TRUE.equals(details.get("hidden"))) {
                values.put(field.key(), "");
            } else {
                values.put(field.key(), safeValue(value));
            }
            metadata.add(details);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("id", file.id);
        result.put("name", file.name);
        result.put("label", file.label);
        result.put("fingerprint", snapshot.fingerprint);
        result.put("fields", metadata);
        result.put("values", values);
        result.put("reloadRequired", true);
        result.put("restartRequired", false);
        result.put("notice", id.startsWith("database")
                ? "数据库重载期间历史写入会短暂中断，超过排空超时的队列记录可能被丢弃。请在负载较低时应用。"
                : "保存仅写入配置文件，不会自动重载。未收录的设置保持只读。");
        return result;
    }

    private Snapshot load(String id) throws IOException {
        Path path = file(id);
        if (Files.size(path) > MAX_BYTES) throw bad("配置文件过大，不支持在线编辑");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw bad("配置文件过大，不支持在线编辑");
        YamlConfiguration yaml = parse(bytes);
        if (yaml.getKeys(true).size() > MAX_FIELDS) throw bad("配置项过多，不支持在线编辑");
        return new Snapshot(bytes, yaml, fingerprint(bytes));
    }

    private Path realRoot() throws IOException {
        if (Files.isSymbolicLink(root)) throw bad("GrimAC 配置目录不允许符号链接");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw HttpException.notFound("GrimAC 配置目录尚未创建，请先完成插件首次启动");
        Path real = root.toRealPath();
        if (!real.equals(root)) throw bad("GrimAC 配置目录不允许重定向路径");
        return real;
    }

    private Path file(String id) throws IOException {
        ConfigFile file = descriptor(id);
        Path real = realRoot();
        Path target = real.resolve(file.name);
        Path parent = target.getParent();
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(target))
            throw bad("GrimAC 配置路径不允许符号链接");
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw HttpException.notFound("GrimAC 配置文件不存在：" + file.name);
        if (!parent.toRealPath().equals(parent) || !parent.toRealPath().startsWith(real)
                || !target.toRealPath().equals(target))
            throw bad("GrimAC 配置路径必须位于插件数据目录内");
        return target;
    }

    private static ConfigFile descriptor(String id) {
        return FILES.stream().filter(file -> file.id.equals(id)).findFirst()
                .orElseThrow(() -> bad("不支持的 GrimAC 配置文件"));
    }

    private static YamlConfiguration parse(byte[] bytes) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try { yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8)); }
        catch (InvalidConfigurationException | RuntimeException cause) {
            // Parser diagnostics may contain the original secret-bearing line.
            throw new HttpException(409, "GrimAC YAML 格式无效，请先修复配置文件");
        }
        return yaml;
    }

    private static Object safeValue(Object value) {
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) return Double.isFinite(number.doubleValue()) ? value : "";
        if (value instanceof List<?> list && list.size() <= 512 && list.stream().allMatch(String.class::isInstance))
            return List.copyOf(list);
        return "";
    }

    private static void validateSize(Object value) {
        if (value instanceof String string && string.length() > 16_384)
            throw bad("配置文本不得超过 16384 字符");
        if (value instanceof List<?> list) {
            if (list.size() > 512) throw bad("单个配置列表不得超过 512 项");
            for (Object item : list) {
                if (!(item instanceof String string) || string.length() > 8192)
                    throw bad("配置列表项必须为不超过 8192 字符的文本");
            }
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void pruneBackups(Path target) {
        String prefix = target.getFileName() + ".lc-backup-";
        try (var paths = Files.list(target.getParent())) {
            List<Path> backups = paths.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".bak")
                            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(GrimConfigStore::modified).reversed()
                            .thenComparing(Path::toString)).toList();
            for (int index = MAX_BACKUPS; index < backups.size(); index++) Files.deleteIfExists(backups.get(index));
        } catch (IOException ignored) {
            // A successful replacement remains successful if optional backup rotation is unavailable.
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException cause) { return Long.MIN_VALUE; }
    }

    private static boolean equivalent(Object first, Object second) {
        if (first instanceof Number && second instanceof Number) {
            try { return new BigDecimal(first.toString()).compareTo(new BigDecimal(second.toString())) == 0; }
            catch (NumberFormatException ignored) { return false; }
        }
        return Objects.equals(first, second);
    }

    private static String fingerprint(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException cause) { throw new IllegalStateException(cause); }
    }

    private static HttpException bad(String message) { return HttpException.badRequest(message); }
    private static HttpException conflict() { return new HttpException(409, "配置已被其他位置修改，请重新读取后核对"); }
    private record ConfigFile(String id, String name, String label) {}
    private record Snapshot(byte[] bytes, YamlConfiguration yaml, String fingerprint) {}
}
