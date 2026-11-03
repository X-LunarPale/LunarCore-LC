package com.lunarboreal.core.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Downloads are staged; file changes are applied before modules load. */
public final class ModuleMarket implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String REPOSITORY = "https://github.com/X-LunarPale/LunarCore-LC";
    public record Entry(String id, String name, String nameEn, String description, String descriptionEn,
                        String category, String icon, List<String> depends, String version,
                        String asset, String sha256, long size) {}
    private record Catalog(String version, List<Entry> modules) {}
    private record Installed(String id, String version, List<String> depends, String file) {}
    record Change(String id, String file, String asset, String sha256, boolean existed) {}
    record Plan(String version, List<Change> changes) {}
    @FunctionalInterface interface Downloader { byte[] download(Entry entry) throws Exception; }

    private final Path modules;
    private final Path work;
    private final String version;
    private final List<Entry> entries;
    private final Downloader downloader;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LunarCore-market");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String state = "idle";
    private volatile String error = "";
    private volatile String current = "";
    private boolean closed;

    public ModuleMarket(Path data, String version) throws IOException {
        this(data, version, readCatalog(version), null);
    }

    ModuleMarket(Path data, String version, List<Entry> entries, Downloader downloader) throws IOException {
        this.version = version;
        this.modules = data.toAbsolutePath().normalize().resolve("modules");
        this.work = data.toAbsolutePath().normalize().resolve(".market");
        safeDirectory(modules);
        safeDirectory(work);
        safeDirectory(work.resolve("staged"));
        safeDirectory(work.resolve("backup"));
        this.entries = List.copyOf(entries);
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (!entry.id().matches("[a-z0-9_-]+") || entry.id().equals("essentials")
                    || !ids.add(entry.id()) || !version.equals(entry.version())
                    || !entry.asset().equals("lbr-" + entry.id() + ".jar")
                    || !entry.sha256().matches("[a-f0-9]{64}") || entry.size() < 1 || entry.size() > 64 * 1024 * 1024) {
                throw new IOException("模块目录无效 / Invalid module catalog");
            }
        }
        this.downloader = downloader == null ? this::download : downloader;
    }

    private static List<Entry> readCatalog(String version) throws IOException {
        try (InputStream input = ModuleMarket.class.getResourceAsStream("/module-market.json")) {
            if (input == null) throw new IOException("缺少模块目录 / Module catalog missing");
            Catalog catalog = JSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Catalog.class);
            if (catalog == null || !version.equals(catalog.version()) || catalog.modules() == null)
                throw new IOException("模块目录版本不符 / Module catalog version mismatch");
            return catalog.modules();
        }
    }

    private static void safeDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("模块目录不可用 / Module directory unavailable");
    }

    private static Path child(Path directory, String name) throws IOException {
        if (name == null || !name.matches("[a-zA-Z0-9_.-]+\\.jar") || name.startsWith("."))
            throw new IOException("模块文件名无效 / Invalid module filename");
        Path file = directory.resolve(name);
        if (Files.isSymbolicLink(file)) throw new IOException("模块文件不能是链接 / Module file must not be a link");
        return file;
    }

    private Entry entry(String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new HttpException(404, "模块不存在 / Module not found"));
    }

    private Map<String, Installed> installed() throws IOException {
        Map<String, Installed> out = new LinkedHashMap<>();
        try (var files = Files.list(modules)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                child(modules, path.getFileName().toString());
                Installed module = describe(path);
                if (out.putIfAbsent(module.id(), module) != null)
                    throw new IOException("存在重复模块，请先移除重复文件 / Duplicate module files");
            }
        }
        return out;
    }

    private static Installed describe(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var descriptor = jar.getJarEntry("module.yml");
            if (descriptor == null || descriptor.getSize() > 65536)
                throw new IOException("模块缺少有效说明 / Invalid module descriptor");
            YamlConfiguration yaml;
            try (InputStream input = jar.getInputStream(descriptor)) {
                yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            String id = yaml.getString("id", "");
            if (!id.matches("[a-z0-9_-]+") || yaml.getString("main", "").isBlank())
                throw new IOException("模块说明无效 / Invalid module descriptor");
            return new Installed(id, yaml.getString("version", ""), List.copyOf(yaml.getStringList("depends")),
                    path.getFileName().toString());
        }
    }

    public synchronized Map<String, Object> snapshot() throws IOException {
        Map<String, Installed> installed = installed();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id());
            item.put("name", entry.name());
            item.put("nameEn", entry.nameEn());
            item.put("description", entry.description());
            item.put("descriptionEn", entry.descriptionEn());
            item.put("category", entry.category());
            item.put("depends", entry.depends());
            item.put("version", entry.version());
            item.put("size", entry.size());
            Installed local = installed.get(entry.id());
            item.put("installedVersion", local == null ? "" : local.version());
            item.put("updateAvailable", local != null && !entry.version().equals(local.version()));
            items.add(item);
        }
        Plan plan = pending();
        return Map.of("items", items, "version", version, "state", state, "error", error,
                "current", current, "pending", plan == null ? List.of() : plan.changes());
    }

    private void available() throws IOException {
        if (closed || state.equals("downloading")) throw new HttpException(409, "任务正在执行 / A task is running");
        if (pending() != null) throw new HttpException(409, "请重启服务器或取消待应用变更 / Restart or cancel pending changes");
    }

    public synchronized void install(String id) throws IOException {
        available();
        Map<String, Installed> installed = installed();
        List<Entry> selected = new ArrayList<>();
        collect(id, installed, new HashSet<>(), new HashSet<>(), selected);
        if (selected.isEmpty()) throw new HttpException(409, "已安装当前版本 / Current version already installed");
        state = "downloading";
        error = "";
        current = id;
        worker.execute(() -> stage(selected, installed));
    }

    private void collect(String id, Map<String, Installed> installed, Set<String> visiting,
                         Set<String> done, List<Entry> out) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw new HttpException(409, "模块依赖循环 / Circular module dependency");
        Entry entry = entry(id);
        for (String dependency : entry.depends()) {
            if (!installed.containsKey(dependency) || !version.equals(installed.get(dependency).version()))
                collect(dependency, installed, visiting, done, out);
        }
        if (!installed.containsKey(id) || !version.equals(installed.get(id).version())) out.add(entry);
        visiting.remove(id);
        done.add(id);
    }

    private void stage(List<Entry> selected, Map<String, Installed> installed) {
        try {
            List<Change> changes = new ArrayList<>();
            for (Entry entry : selected) {
                current = entry.id();
                byte[] bytes = downloader.download(entry);
                if (Thread.currentThread().isInterrupted()) throw new IOException("任务已停止 / Task stopped");
                if (bytes.length != entry.size() || !hash(bytes).equals(entry.sha256()))
                    throw new IOException("模块校验失败，请重试 / Module verification failed; retry");
                Path staged = child(work.resolve("staged"), entry.asset());
                Files.write(staged, bytes);
                Installed downloaded = describe(staged);
                if (!entry.id().equals(downloaded.id()) || !version.equals(downloaded.version())
                        || !Set.copyOf(entry.depends()).equals(Set.copyOf(downloaded.depends())))
                    throw new IOException("模块内容与目录不符 / Module does not match catalog");
                Installed local = installed.get(entry.id());
                changes.add(new Change(entry.id(), local == null ? entry.asset() : local.file(),
                        entry.asset(), entry.sha256(), local != null));
            }
            synchronized (this) {
                if (closed) return;
                writeJson(work.resolve("pending.json"), new Plan(version, changes));
                state = "ready";
            }
        } catch (Exception exception) {
            // Do not expose upstream response bodies or access-bearing URLs.
            error = exception instanceof IOException && exception.getMessage() != null
                    && exception.getMessage().contains(" / ") ? exception.getMessage()
                    : "模块下载失败，请检查网络及仓库访问令牌 / Download failed; check network and repository token";
            state = "failed";
        } finally {
            current = "";
        }
    }

    public synchronized void remove(String id) throws IOException {
        available();
        if (id.equals("essentials")) throw new HttpException(409, "基础模块不能卸载 / Basic module cannot be removed");
        Map<String, Installed> installed = installed();
        Installed target = installed.get(id);
        if (target == null) throw new HttpException(404, "模块未安装 / Module is not installed");
        List<String> requiredBy = installed.values().stream().filter(m -> m.depends().contains(id))
                .map(Installed::id).toList();
        if (!requiredBy.isEmpty())
            throw new HttpException(409, "请先卸载依赖此模块的模块 / Remove dependent modules first: " + String.join(", ", requiredBy));
        writeJson(work.resolve("pending.json"), new Plan(version,
                List.of(new Change(id, target.file(), null, null, true))));
        state = "ready";
        error = "";
    }

    public synchronized void cancel() throws IOException {
        if (state.equals("downloading")) throw new HttpException(409, "下载中，请稍候 / Download in progress");
        Files.deleteIfExists(work.resolve("pending.json"));
        state = "idle";
        error = "";
    }

    private Plan pending() throws IOException {
        Path file = work.resolve("pending.json");
        if (!Files.exists(file)) return null;
        if (Files.isSymbolicLink(file)) throw new IOException("变更文件不可用 / Pending plan unavailable");
        Plan plan;
        try { plan = JSON.fromJson(Files.readString(file), Plan.class); }
        catch (RuntimeException e) { throw new IOException("变更文件无效 / Invalid pending plan", e); }
        if (plan == null || plan.changes() == null || !version.equals(plan.version()) || plan.changes().isEmpty())
            throw new IOException("待应用变更版本不符 / Pending changes version mismatch");
        Set<String> files = new HashSet<>();
        for (Change change : plan.changes()) {
            if (change.id() == null || change.id().equals("essentials") || !change.id().matches("[a-z0-9_-]+")
                    || !files.add(change.file())) throw new IOException("变更文件无效 / Invalid pending plan");
            child(modules, change.file());
            if (change.asset() != null) {
                Entry entry = entry(change.id());
                if (!entry.asset().equals(change.asset()) || !entry.sha256().equals(change.sha256()))
                    throw new IOException("变更内容无效 / Invalid pending changes");
            }
        }
        return plan;
    }

    /** Backup first; an interrupted apply is rolled back before the next attempt. */
    public synchronized void applyPending() throws IOException {
        Plan plan = pending();
        Path journal = work.resolve("applying");
        Path committed = work.resolve("committed");
        if (plan == null) {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(committed);
            return;
        }
        if (Files.exists(committed)) {
            Files.delete(work.resolve("pending.json"));
            Files.deleteIfExists(journal);
            Files.delete(committed);
            return;
        }
        if (Files.exists(journal)) {
            rollback(plan);
            Files.delete(journal);
        }
        for (Change change : plan.changes()) {
            Path target = child(modules, change.file());
            if (change.existed() != Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("模块文件已变化，请取消后重试 / Module files changed; cancel and retry");
            if (change.asset() != null) {
                Path staged = child(work.resolve("staged"), change.asset());
                if (!hash(Files.readAllBytes(staged)).equals(change.sha256()))
                    throw new IOException("待安装模块校验失败 / Staged module verification failed");
            }
            if (change.existed()) Files.copy(target, child(work.resolve("backup"), change.file()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(journal, version);
        try {
            for (Change change : plan.changes()) {
                Path target = child(modules, change.file());
                if (change.asset() == null) Files.delete(target);
                else {
                    Path temporary = target.resolveSibling(target.getFileName() + ".market-tmp");
                    Files.copy(child(work.resolve("staged"), change.asset()), temporary, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(committed, version);
        } catch (IOException exception) {
            try { rollback(plan); Files.deleteIfExists(journal); }
            catch (IOException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
            throw exception;
        }
        Files.delete(work.resolve("pending.json"));
        Files.deleteIfExists(journal);
        Files.delete(committed);
        state = "idle";
    }

    private void rollback(Plan plan) throws IOException {
        for (Change change : plan.changes()) {
            Path target = child(modules, change.file());
            if (change.existed()) Files.copy(child(work.resolve("backup"), change.file()), target, StandardCopyOption.REPLACE_EXISTING);
            else Files.deleteIfExists(target);
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private HttpRequest request(URI uri, String accept) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Accept", accept).header("User-Agent", "LunarCore/" + version).GET().build();
    }

    private byte[] fetch(URI uri, String accept, int limit) throws Exception {
        for (int redirect = 0; redirect < 4; redirect++) {
            var pending = http.sendAsync(request(uri, accept),
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), limit));
            try {
                var response = pending.get(60, TimeUnit.SECONDS);
                if (response.statusCode() == 302 || response.statusCode() == 301
                        || response.statusCode() == 303 || response.statusCode() == 307 || response.statusCode() == 308) {
                    URI next = uri.resolve(response.headers().firstValue("Location").orElseThrow());
                    String host = next.getHost();
                    if (!"https".equals(next.getScheme()) || next.getUserInfo() != null || host == null
                            || !(host.equals("github.com") || host.endsWith(".githubusercontent.com")))
                        throw new IOException("下载地址无效 / Invalid download location");
                    uri = next;
                    continue;
                }
                if (response.statusCode() != 200) throw new IOException("GitHub HTTP " + response.statusCode());
                return response.body();
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        }
        throw new IOException("下载跳转过多 / Too many download redirects");
    }

    static URI downloadUri(Entry entry) {
        return URI.create(REPOSITORY + "/releases/download/v" + entry.version() + "/" + entry.asset());
    }

    private byte[] download(Entry entry) throws Exception {
        return fetch(downloadUri(entry), "application/octet-stream", (int) entry.size());
    }

    @Override public synchronized void close() {
        closed = true;
        worker.shutdownNow();
        http.shutdownNow();
    }
}
