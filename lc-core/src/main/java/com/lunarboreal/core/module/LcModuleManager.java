package com.lunarboreal.core.module;

import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.module.ModuleManager;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * 模块管理器：释放内置模块、扫描 modules/*.jar、拓扑排序、独立类加载器、失败隔离、运行时启用/禁用/重载。
 * 所有公开操作需在主线程调用。
 */
public final class LcModuleManager implements ModuleManager {

    public record Discovered(ModuleDescription description, File file) {}

    /** 与 core 语言/变量/web 命名空间冲突的保留 ID。 */
    private static final Set<String> RESERVED_IDS = Set.of("core", "lc", "web", "api", "lunarcore", "system", "admin", "ext");

    private record Loaded(LcModule module, ModuleClassLoader loader, File file) {}

    private final LunarCorePlugin plugin;
    private final File modulesDir;
    private final Map<String, Discovered> discovered = new LinkedHashMap<>();
    /** 已启用模块（按启用顺序）。 */
    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    /** 模块 ID → 最近一次失败原因。 */
    private final Map<String, String> failures = new LinkedHashMap<>();

    public LcModuleManager(LunarCorePlugin plugin) {
        this.plugin = plugin;
        this.modulesDir = new File(plugin.getDataFolder(), "modules");
        modulesDir.mkdirs();
    }

    public File modulesDir() {
        return modulesDir;
    }

    // ================================================================== 内置模块释放

    /**
     * 从插件 jar 内 builtin-modules/*.jar 释放到 modules/。
     * 记录每个内置 jar 的 SHA-256 于 modules/.builtin.yml；首次释放全部，之后在 update-builtin=true 且内容变化时覆盖；
     * 服主手动删除过的内置模块不会被重新释放（除非是新加入的内置模块）。
     */
    public void extractBuiltin(File pluginJar) {
        if (!plugin.getConfig().getBoolean("modules.extract-builtin", true)) return;
        boolean update = plugin.getConfig().getBoolean("modules.update-builtin", true);
        File manifestFile = new File(modulesDir, ".builtin.yml");
        YamlConfiguration manifest = YamlConfiguration.loadConfiguration(manifestFile);
        boolean firstRun = !manifestFile.exists();
        int extracted = 0;
        try (JarFile jar = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("builtin-modules/") || !name.endsWith(".jar")) continue;
                String fileName = name.substring("builtin-modules/".length());
                if (fileName.contains("/")) continue;
                byte[] data;
                try (InputStream in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                String hash = sha256(data);
                String key = "files." + fileName.replace('.', '_');
                String recorded = manifest.getString(key);
                File out = new File(modulesDir, fileName);
                boolean write;
                if (firstRun || recorded == null) {
                    write = true; // 首次运行或新增的内置模块
                } else if (!out.exists()) {
                    write = false; // 服主删除过，尊重其选择
                } else {
                    write = update && !hash.equals(recorded) && !hash.equals(sha256(Files.readAllBytes(out.toPath())));
                }
                if (write) {
                    Files.write(out.toPath(), data);
                    extracted++;
                }
                manifest.set(key, hash);
            }
            manifest.set("core-version", plugin.version());
            manifest.options().setHeader(List.of("LunarCore 内置模块释放记录，请勿手动修改。删除某个内置模块 jar 后不会被自动恢复。"));
            manifest.save(manifestFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "释放内置模块失败", e);
        }
        if (extracted > 0) plugin.getLogger().info("已释放/更新 " + extracted + " 个内置模块到 " + modulesDir.getPath());
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(data));
        }
    }

    // ================================================================== 扫描

    /** 重新扫描 modules/ 目录。 */
    public synchronized void scan() {
        discovered.clear();
        File[] files = modulesDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try {
                ModuleDescription desc = readDescription(f);
                if (desc == null) {
                    plugin.getLogger().warning("模块 " + f.getName() + " 缺少 module.yml，已忽略");
                    continue;
                }
                Discovered prev = discovered.get(desc.id());
                if (prev != null) {
                    plugin.getLogger().warning("模块 ID 重复: " + desc.id() + "（" + prev.file().getName() + " 与 " + f.getName() + "），忽略后者");
                    continue;
                }
                discovered.put(desc.id(), new Discovered(desc, f));
            } catch (Exception e) {
                plugin.getLogger().warning("读取模块 " + f.getName() + " 失败: " + e.getMessage());
            }
        }
    }

    private static ModuleDescription readDescription(File file) throws IOException {
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("module.yml");
            if (entry == null) return null;
            YamlConfiguration yml;
            try (InputStream in = jar.getInputStream(entry)) {
                yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String id = yml.getString("id");
            String main = yml.getString("main");
            if (id == null || id.isBlank() || main == null || main.isBlank()) {
                throw new IOException("module.yml 缺少 id 或 main");
            }
            id = id.trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_\\-]+")) throw new IOException("非法模块 ID: " + id);
            if (RESERVED_IDS.contains(id)) throw new IOException("模块 ID 为保留字: " + id);
            return new ModuleDescription(
                    id,
                    yml.getString("name", id),
                    yml.getString("version", "1.0.0"),
                    main.trim(),
                    yml.getString("description", ""),
                    List.copyOf(yml.getStringList("authors")),
                    lower(yml.getStringList("depends")),
                    lower(yml.getStringList("softdepends")));
        }
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.trim().toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    // ================================================================== 批量

    private Set<String> configDisabled() {
        Set<String> s = new HashSet<>();
        for (String id : plugin.getConfig().getStringList("modules.disabled")) s.add(id.trim().toLowerCase(Locale.ROOT));
        // An existing installation keeps its old config.yml; absence of this opt-in must not
        // auto-start QQAuth (which disconnects players and requires a configured QQ bridge).
        if (!plugin.getConfig().getBoolean("modules.qqauth-autostart", false)) s.add("qqauth");
        return s;
    }

    /** 启动时调用：扫描并按依赖顺序启用全部未禁用模块。 */
    public synchronized void loadAll() {
        scan();
        Set<String> disabled = configDisabled();
        Map<String, Discovered> candidates = new LinkedHashMap<>();
        for (Discovered d : discovered.values()) {
            if (disabled.contains(d.description().id())) {
                plugin.getLogger().info("模块 " + d.description().id() + " 已在配置中禁用，跳过");
                continue;
            }
            candidates.put(d.description().id(), d);
        }
        List<String> order = topoSort(candidates);
        long start = System.currentTimeMillis();
        for (String id : order) {
            Discovered d = candidates.get(id);
            List<String> missing = new ArrayList<>();
            for (String dep : d.description().depends()) if (!loaded.containsKey(dep)) missing.add(dep);
            if (!missing.isEmpty()) {
                fail(id, "缺少依赖模块: " + String.join(", ", missing));
                continue;
            }
            enableDiscovered(d);
        }
        plugin.getLogger().info("已启用 " + loaded.size() + "/" + discovered.size() + " 个模块（" + (System.currentTimeMillis() - start) + "ms）");
    }

    /** 依赖优先的拓扑排序（软依赖仅影响顺序），检测循环依赖。 */
    private List<String> topoSort(Map<String, Discovered> candidates) {
        List<String> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String id : candidates.keySet()) visit(id, candidates, done, visiting, out);
        return out;
    }

    private void visit(String id, Map<String, Discovered> c, Set<String> done, Set<String> visiting, List<String> out) {
        if (done.contains(id)) return;
        if (visiting.contains(id)) {
            plugin.getLogger().warning("检测到循环依赖: " + String.join(" → ", visiting) + " → " + id);
            return;
        }
        Discovered d = c.get(id);
        if (d == null) return;
        visiting.add(id);
        for (String dep : d.description().depends()) visit(dep, c, done, visiting, out);
        for (String dep : d.description().softDepends()) visit(dep, c, done, visiting, out);
        visiting.remove(id);
        done.add(id);
        out.add(id);
    }

    /** 插件关闭时按启用逆序禁用全部模块。 */
    public synchronized void disableAll() {
        List<String> ids = new ArrayList<>(loaded.keySet());
        java.util.Collections.reverse(ids);
        for (String id : ids) {
            Loaded l = loaded.get(id);
            if (l != null) shutdown(id, l);
        }
    }

    /** /lc reload：调用所有模块的 onReload。 */
    public synchronized int reloadConfigs() {
        int n = 0;
        for (Map.Entry<String, Loaded> e : loaded.entrySet()) {
            try {
                e.getValue().module().onReload();
                n++;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "模块 " + e.getKey() + " 重载配置失败", t);
            }
        }
        return n;
    }

    // ================================================================== 单个模块

    private boolean enableDiscovered(Discovered d) {
        ModuleDescription desc = d.description();
        String id = desc.id();
        ModuleClassLoader loader = null;
        LcModule module = null;
        boolean enabledCalled = false;
        long t0 = System.currentTimeMillis();
        try {
            loader = new ModuleClassLoader(id, d.file().toURI().toURL(), plugin.getClass().getClassLoader());
            Class<?> main = Class.forName(desc.main(), true, loader);
            if (!LcModule.class.isAssignableFrom(main)) {
                throw new IllegalStateException(desc.main() + " 未继承 LcModule");
            }
            module = (LcModule) main.getDeclaredConstructor().newInstance();
            File dataFolder = new File(modulesDir, id);
            dataFolder.mkdirs();
            module.init(desc, dataFolder, loader, new ModuleLogger(id, plugin.getLogger()));
            plugin.messages().registerBundle(id, loader);
            module.onLoad();
            enabledCalled = true;
            module.setEnabledState(true);
            module.onEnable();
            loaded.put(id, new Loaded(module, loader, d.file()));
            failures.remove(id);
            plugin.getLogger().info("模块 " + desc.name() + " (" + id + ") v" + desc.version() + " 已启用（" + (System.currentTimeMillis() - t0) + "ms）");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "模块 " + id + " 启用失败", t);
            fail(id, t.getClass().getSimpleName() + ": " + t.getMessage());
            if (module != null) {
                if (enabledCalled) {
                    try {
                        module.onDisable();
                    } catch (Throwable ignored) {
                    }
                }
                module.setEnabledState(false);
                cleanup(id, module);
            } else {
                plugin.messages().unregisterBundle(id);
            }
            closeLoader(loader);
            return false;
        }
    }

    private void fail(String id, String reason) {
        failures.put(id, reason);
        plugin.getLogger().warning("模块 " + id + " 未启用: " + reason);
    }

    private void cleanup(String id, LcModule module) {
        try {
            module.cleanupInternal();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "清理模块 " + id + " 监听器/任务失败", t);
        }
        safe(() -> plugin.commandRegistry().unregisterAll(module));
        safe(() -> plugin.webServer().unregisterAll(module));
        safe(() -> plugin.services().unregisterAll(module));
        safe(() -> plugin.placeholders().unregisterAll(module));
        safe(() -> plugin.messages().unregisterBundle(id));
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "模块清理出错", t);
        }
    }

    private void closeLoader(ModuleClassLoader loader) {
        if (loader == null) return;
        // 运行时禁用：延迟 10 秒关闭，让在途的数据库回调/一次性任务执行完，避免 NoClassDefFoundError
        if (plugin.isEnabled()) {
            try {
                org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> closeNow(loader), 200L);
                return;
            } catch (Throwable ignored) {
            }
        }
        closeNow(loader);
    }

    private void closeNow(ModuleClassLoader loader) {
        try {
            loader.close();
        } catch (IOException e) {
            plugin.debug("关闭模块类加载器失败: " + e.getMessage());
        }
    }

    private void shutdown(String id, Loaded l) {
        LcModule module = l.module();
        try {
            module.onDisable();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "模块 " + id + " onDisable 出错", t);
        }
        module.setEnabledState(false);
        cleanup(id, module);
        loaded.remove(id);
        closeLoader(l.loader());
        plugin.getLogger().info("模块 " + id + " 已禁用");
    }

    /** 当前已启用且硬依赖 id 的模块。 */
    private List<String> dependents(String id) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Loaded> e : loaded.entrySet()) {
            if (e.getValue().module().description().depends().contains(id)) out.add(e.getKey());
        }
        return out;
    }

    @Override
    public synchronized Collection<LcModule> loaded() {
        List<LcModule> out = new ArrayList<>();
        for (Loaded l : loaded.values()) out.add(l.module());
        return out;
    }

    @Override
    public synchronized Optional<LcModule> get(String id) {
        if (id == null) return Optional.empty();
        Loaded l = loaded.get(id.toLowerCase(Locale.ROOT));
        return l == null ? Optional.empty() : Optional.of(l.module());
    }

    @Override
    public synchronized boolean isEnabled(String id) {
        return id != null && loaded.containsKey(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public synchronized boolean enable(String id) {
        return enable0(id.toLowerCase(Locale.ROOT), new HashSet<>());
    }

    private boolean enable0(String id, Set<String> chain) {
        if (loaded.containsKey(id)) return true;
        if (!chain.add(id)) {
            fail(id, "循环依赖");
            return false;
        }
        if (!discovered.containsKey(id)) scan();
        Discovered d = discovered.get(id);
        if (d == null) {
            failures.put(id, "未找到模块");
            return false;
        }
        for (String dep : d.description().depends()) {
            if (!enable0(dep, chain)) {
                fail(id, "依赖模块 " + dep + " 无法启用");
                return false;
            }
        }
        return enableDiscovered(d);
    }

    @Override
    public synchronized boolean disable(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        Loaded l = loaded.get(key);
        if (l == null) return false;
        for (String dep : dependents(key)) {
            plugin.getLogger().info("模块 " + dep + " 依赖 " + key + "，级联禁用");
            disable(dep);
        }
        shutdown(key, l);
        return true;
    }

    @Override
    public synchronized boolean reload(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!loaded.containsKey(key)) return enable(key);
        List<String> dependents = collectDependents(key);
        disable(key);
        scan(); // jar 可能已被替换
        boolean ok = enable(key);
        if (ok) {
            for (String dep : dependents) enable(dep);
        }
        return ok;
    }

    /** 递归收集已启用的依赖者（按启用顺序）。 */
    private List<String> collectDependents(String id) {
        List<String> out = new ArrayList<>();
        for (String d : dependents(id)) {
            if (!out.contains(d)) out.add(d);
            for (String dd : collectDependents(d)) if (!out.contains(dd)) out.add(dd);
        }
        return out;
    }

    @Override
    public synchronized Collection<ModuleDescription> discovered() {
        List<ModuleDescription> out = new ArrayList<>();
        for (Discovered d : discovered.values()) out.add(d.description());
        return out;
    }

    // ================================================================== 供 web / 命令展示

    public synchronized Optional<Discovered> discoveredInfo(String id) {
        return Optional.ofNullable(discovered.get(id.toLowerCase(Locale.ROOT)));
    }

    public synchronized Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    public synchronized String failure(String id) {
        return failures.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isConfigDisabled(String id) {
        return configDisabled().contains(id.toLowerCase(Locale.ROOT));
    }

    /** 持久化修改 modules.disabled（保存 config.yml）。 */
    public void setConfigDisabled(String id, boolean disabled) {
        String key = id.toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("modules.disabled"));
        list.removeIf(s -> s.trim().equalsIgnoreCase(key));
        if (disabled) list.add(key);
        plugin.getConfig().set("modules.disabled", list);
        plugin.saveConfig();
    }

    /** 状态：enabled / failed / disabled（配置禁用）/ stopped。 */
    public synchronized String status(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (loaded.containsKey(key)) return "enabled";
        if (failures.containsKey(key)) return "failed";
        if (isConfigDisabled(key)) return "disabled";
        return "stopped";
    }
}
