package com.lunarboreal.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Serial, atomic write-before-publish storage. Corrupt existing files are never replaced on load. */
public final class PermissionStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private volatile PermissionState state = PermissionState.initial();
    private boolean corrupt;
    public PermissionStore(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath(); }
    public static PermissionState initial() { return PermissionState.initial(); }
    public PermissionState snapshot() { return state; }
    public synchronized void load() throws IOException {
        if (!Files.exists(file)) { corrupt=false; return; }
        try {
            if (Files.size(file)>32*1024*1024) throw new IOException("Permission file too large");
            PermissionState loaded=JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),PermissionState.class);
            state=Objects.requireNonNull(loaded,"Empty permission document"); corrupt=false;
        } catch (RuntimeException | IOException ex) { corrupt=true; throw new IOException("权限文件损坏，已拒绝覆盖",ex); }
    }
    public synchronized PermissionState update(UnaryOperator<PermissionState> operation) throws IOException {
        if(corrupt)throw new IOException("权限文件损坏，修复并重新加载前禁止写入");
        PermissionState next=Objects.requireNonNull(operation.apply(state));
        // Record constructors validate all nested data; materialize a fresh root for callers.
        next=new PermissionState(next.groups(),next.users(),next.defaultGroup());
        String data=JSON.toJson(next);
        Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"permissions-",".tmp");
        try {
            Files.writeString(temporary,data,StandardCharsets.UTF_8);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            state=next;
        } finally { Files.deleteIfExists(temporary); }
        return next;
    }
}
