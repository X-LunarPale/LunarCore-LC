package com.lunarboreal.anticheat;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Uses only Grim's public API, resolved from its Bukkit plugin classloader. */
public final class GrimBridge implements AutoCloseable {
    private final AntiCheatModule module;
    private final Consumer<Runnable> async;
    private long reloadOperation;
    private volatile Binding binding;
    private volatile String status = "未连接 GrimAC";
    private volatile String version = "";
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile String reloadStatus = "尚未重载";
    record Binding(Object api, Class<?> apiType, Object bus, Object owner, Object listener,
                   Method unregister, AtomicBoolean active) {}

    public GrimBridge(AntiCheatModule module) { this(module, task -> module.runAsync(task)); }
    GrimBridge(AntiCheatModule module, Consumer<Runnable> async) {
        this.module = module;
        this.async = async;
    }
    public synchronized void bind(Plugin plugin) {
        close();
        if (plugin == null || !plugin.isEnabled()) { status = "GrimAC 未安装或未启用"; return; }
        Object bus = null, owner = null, listener = null;
        Method unregister = null;
        AtomicBoolean active = new AtomicBoolean(false);
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> provider = loader.loadClass("ac.grim.grimac.api.GrimAPIProvider");
            Class<?> apiType = loader.loadClass("ac.grim.grimac.api.GrimAbstractAPI");
            Class<?> busType = loader.loadClass("ac.grim.grimac.api.event.EventBus");
            Class<?> ownerType = loader.loadClass("ac.grim.grimac.api.plugin.GrimPlugin");
            Class<?> listenerType = loader.loadClass("ac.grim.grimac.api.event.GrimEventListener");
            Class<?> eventType = loader.loadClass("ac.grim.grimac.api.event.events.FlagEvent");
            Object api = provider.getMethod("get").invoke(null);
            if (api == null) throw new IllegalStateException("Grim API 尚未就绪");
            bus = apiType.getMethod("getEventBus").invoke(api);
            owner = loader.loadClass("ac.grim.grimac.api.plugin.BasicGrimPlugin")
                    .getConstructor(java.util.logging.Logger.class, java.io.File.class, String.class, String.class, java.util.Collection.class)
                    .newInstance(module.logger(), module.dataFolder(), "LunarCore-anticheat-" + java.util.UUID.randomUUID(),
                            module.description().version(), List.of("琉月·冰澜"));
            // Resolve all getters once. Grim reuses FlagEvent, so copy primitives/Strings inside handle().
            SnapshotReader reader = new SnapshotReader(eventType, loader);
            listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "LunarCore Grim flag listener";
                    default -> null;
                };
                if (method.getName().equals("handle")) capture(active, reader, args[0]);
                return null;
            });
            unregister = busType.getMethod("unregisterListener", ownerType, listenerType);
            busType.getMethod("subscribe", ownerType, Class.class, listenerType).invoke(bus, owner, eventType, listener);
            binding = new Binding(api, apiType, bus, owner, listener, unregister, active);
            version = String.valueOf(apiType.getMethod("getGrimVersion").invoke(api));
            active.set(true);
            status = "已连接";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            active.set(false);
            if (unregister != null && listener != null) try { unregister.invoke(bus, owner, listener); } catch (ReflectiveOperationException ignored) { }
            binding = null;
            status = "GrimAC API 不兼容或尚未就绪";
            module.logger().warning(status + ": " + e.getClass().getSimpleName());
        }
    }
    void capture(AtomicBoolean active, SnapshotReader reader, Object event) {
        if (!active.get() || !module.captureEnabled()) return;
        try {
            RecentFlags.Flag flag = reader.read(event);
            synchronized (this) {
                // close() shares this monitor: an in-flight read cannot append after invalidation.
                if (active.get() && module.captureEnabled()) module.recent().add(flag);
            }
        } catch (ReflectiveOperationException | RuntimeException e) { module.captureFailed(); }
    }

    public synchronized void close() {
        Binding old = binding;
        binding = null;
        ++reloadOperation;
        reloading.set(false);
        reloadStatus = "尚未重载";
        version = "";
        status = "未连接 GrimAC";
        if (old != null) {
            old.active.set(false);
            try { old.unregister.invoke(old.bus, old.owner, old.listener); }
            catch (ReflectiveOperationException | RuntimeException e) { module.logger().warning("注销 GrimAC 桥接监听器失败: " + e.getClass().getSimpleName()); }
        }
    }
    public boolean connected() { return binding != null; }
    public String status() { return status; }
    public String version() { return version; }
    public boolean reloading() { return reloading.get(); }
    public String reloadStatus() { return reloadStatus; }

    /** Even reloadAsync() may perform synchronous I/O before returning its future. */
    public void reload(Consumer<String> completed) {
        Binding current;
        long operation;
        synchronized (this) {
            current = binding;
            if (current == null || !current.active.get()) throw new IllegalStateException("GrimAC 尚未连接");
            if (!reloading.compareAndSet(false, true)) throw new IllegalStateException("GrimAC 正在重载");
            operation = ++reloadOperation;
            reloadStatus = "正在重载";
        }
        try {
            async.accept(() -> invokeReload(current, operation, completed));
        } catch (RuntimeException e) {
            finishReload(current, operation, false, completed);
            throw new IllegalStateException("无法启动 GrimAC 重载", e);
        }
    }

    private synchronized boolean currentReload(Binding current, long operation) {
        return binding == current && current.active.get() && reloadOperation == operation && reloading.get();
    }

    private void invokeReload(Binding current, long operation, Consumer<String> completed) {
        if (!currentReload(current, operation)) return;
        try {
            Object result = current.apiType.getMethod("reloadAsync").invoke(current.api);
            if (!(result instanceof CompletableFuture<?> future)) throw new IllegalStateException("GrimAC 未返回异步重载结果");
            future.whenComplete((ok, error) -> finishReload(current, operation,
                    error == null && Boolean.TRUE.equals(ok), completed));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            finishReload(current, operation, false, completed);
        }
    }

    private synchronized void finishReload(Binding current, long operation, boolean success, Consumer<String> completed) {
        if (!currentReload(current, operation)) return;
        reloadStatus = success ? "重载成功" : "重载失败";
        reloading.set(false);
        completed.accept(reloadStatus);
    }

    static final class SnapshotReader {
        private final Method user, check, verbose, violations, cancelled, uuid, name, ping, world, checkName;
        SnapshotReader(Class<?> eventType, ClassLoader loader) throws ReflectiveOperationException {
            this(eventType, loader.loadClass("ac.grim.grimac.api.GrimUser"), loader.loadClass("ac.grim.grimac.api.AbstractCheck"));
        }
        SnapshotReader(Class<?> eventType, Class<?> userType, Class<?> checkType) throws ReflectiveOperationException {
            user = eventType.getMethod("getUser"); check = eventType.getMethod("getCheck");
            verbose = eventType.getMethod("getVerbose"); violations = eventType.getMethod("getViolations");
            cancelled = eventType.getMethod("isCancelled"); uuid = userType.getMethod("getUniqueId");
            name = userType.getMethod("getName"); ping = userType.getMethod("getTransactionPing");
            world = userType.getMethod("getWorldName"); checkName = checkType.getMethod("getCheckName");
        }
        RecentFlags.Flag read(Object event) throws ReflectiveOperationException {
            Object u = user.invoke(event), c = check.invoke(event);
            double vl = ((Number) violations.invoke(event)).doubleValue();
            return new RecentFlags.Flag(System.currentTimeMillis(), text(uuid.invoke(u), 64), text(name.invoke(u), 64),
                    text(checkName.invoke(c), 128), Double.isFinite(vl) ? vl : 0,
                    text(verbose.invoke(event), 2048), ((Number) ping.invoke(u)).intValue(),
                    text(world.invoke(u), 256), Boolean.TRUE.equals(cancelled.invoke(event)));
        }
    }
    static String text(Object value, int limit) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
