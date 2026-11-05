package com.lunarboreal.structures.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** One writer, compare-and-save revisions, durable replacement before publishing the new value. */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private StructuresConfig saved;

    public ConfigStore(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        rejectLinks(this.path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) saved = StructuresConfig.initial();
        else {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
                throw new IOException("结构配置文件无效或过大");
            try {
                var json = JsonParser.parseString(Files.readString(path));
                saved = StructuresConfig.parse(withoutOverwrite(StructuresConfig.object(GSON.fromJson(json, Object.class))));
            } catch (RuntimeException e) { throw new IOException("结构配置损坏，拒绝覆盖已有设置", e); }
        }
    }

    /** Earlier builds stored a replace-existing switch; settings saved by them must still load. */
    static java.util.Map<String, Object> withoutOverwrite(java.util.Map<String, Object> body) {
        if (body.get("defaults") instanceof java.util.Map<?, ?> defaults) defaults.remove("overwriteExisting");
        if (body.get("worlds") instanceof java.util.Map<?, ?> worlds)
            worlds.values().forEach(world -> { if (world instanceof java.util.Map<?, ?> flags) flags.remove("overwriteExisting"); });
        return body;
    }

    public synchronized StructuresConfig saved() { return saved; }

    public synchronized StructuresConfig save(StructuresConfig requested) throws IOException {
        if (requested.revision() != saved.revision()) throw new IllegalArgumentException("配置已被其他管理员修改，请刷新");
        if (requested.sameRules(saved)) return saved;
        var next = new StructuresConfig(Math.addExact(saved.revision(), 1), requested.defaults(), requested.worlds());
        atomicWrite(path, (GSON.toJson(next.map()) + "\n").getBytes(StandardCharsets.UTF_8));
        saved = next;
        return next;
    }

    public static void atomicWrite(Path target, byte[] bytes) throws IOException {
        rejectLinks(target);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), ".structures-", ".tmp");
        try {
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: a failed save must leave the previous file intact.
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("结构数据路径不能包含符号链接");
    }
}
