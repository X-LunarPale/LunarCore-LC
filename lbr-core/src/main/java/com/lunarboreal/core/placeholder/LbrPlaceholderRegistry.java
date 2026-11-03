package com.lunarboreal.core.placeholder;

import com.lunarboreal.api.placeholder.PlaceholderRegistry;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变量注册表：%lc_&lt;namespace&gt;_&lt;key&gt;%，兼容旧变量前缀。
 * 安装 PlaceholderAPI 时通过 {@link PapiBridge} 注册扩展。
 */
public final class LbrPlaceholderRegistry implements PlaceholderRegistry {

    private static final Pattern PATTERN = Pattern.compile("%(?:lc|lbr)_([^%\\s]+)%", Pattern.CASE_INSENSITIVE);

    private record Entry(Resolver resolver, Object owner) {}

    private final LunarCorePlugin plugin;
    private final Map<String, Entry> resolvers = new ConcurrentHashMap<>();
    /** PapiBridge 实例；类型用 Object 避免在无 PAPI 时加载桥接类。 */
    private volatile Object papiBridge;

    public LbrPlaceholderRegistry(LunarCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void register(String namespace, Resolver resolver, Object owner) {
        String ns = namespace.toLowerCase(Locale.ROOT);
        Entry old = resolvers.put(ns, new Entry(resolver, owner));
        if (old != null && old.owner() != owner) {
            plugin.getLogger().warning("变量命名空间 " + ns + " 被 " + LunarCorePlugin.ownerName(owner) + " 覆盖");
        }
    }

    @Override
    public void unregisterAll(Object owner) {
        resolvers.entrySet().removeIf(e -> e.getValue().owner() == owner);
    }

    @Override
    public String resolve(OfflinePlayer player, String identifier) {
        if (identifier == null || identifier.isEmpty()) return null;
        String id = identifier;
        // 保留同名命名空间的优先级，兼容带前缀的完整标识。
        if (id.regionMatches(true, 0, "lc_", 0, 3) && !resolvers.containsKey("lc")) id = id.substring(3);
        else if (id.regionMatches(true, 0, "lbr_", 0, 4) && !resolvers.containsKey("lbr")) id = id.substring(4);
        // 先尝试最长命名空间匹配（命名空间本身可能含下划线）
        String lower = id.toLowerCase(Locale.ROOT);
        String bestNs = null;
        for (String ns : resolvers.keySet()) {
            if (lower.startsWith(ns + "_") && (bestNs == null || ns.length() > bestNs.length())) bestNs = ns;
        }
        if (bestNs == null) return null;
        Entry e = resolvers.get(bestNs);
        if (e == null) return null;
        try {
            return e.resolver().resolve(player, id.substring(bestNs.length() + 1));
        } catch (Throwable t) {
            plugin.debug("变量解析出错 %lc_" + id + "%: " + t);
            return null;
        }
    }

    @Override
    public String apply(OfflinePlayer player, String text) {
        if (text == null || text.indexOf('%') < 0) return text;
        Object bridge = papiBridge;
        if (bridge != null) {
            try {
                return ((PapiBridge) bridge).apply(player, text);
            } catch (Throwable t) {
                plugin.debug("PlaceholderAPI 解析失败: " + t);
            }
        }
        Matcher m = PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = resolve(player, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    public Map<String, String> namespaces() {
        Map<String, String> out = new TreeMap<>();
        resolvers.forEach((ns, e) -> out.put(ns, "%lc_" + ns + "_<key>% · " + LunarCorePlugin.ownerName(e.owner())));
        return out;
    }

    // ------------------------------------------------------------------ PAPI

    public boolean isPapiHooked() {
        return papiBridge != null;
    }

    /** 检测到 PlaceholderAPI 已启用时注册扩展。 */
    public synchronized void hookPapi() {
        if (papiBridge != null) return;
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return;
        try {
            PapiBridge bridge = new PapiBridge(plugin, this);
            if (bridge.hook()) {
                papiBridge = bridge;
                plugin.getLogger().info("已桥接 PlaceholderAPI（%lc_<模块>_<变量>%）");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("桥接 PlaceholderAPI 失败: " + t);
        }
    }

    public synchronized void unhookPapi() {
        Object bridge = papiBridge;
        papiBridge = null;
        if (bridge != null) {
            try {
                ((PapiBridge) bridge).unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    /** PAPI 被卸载时仅丢弃引用（其扩展已随之清理）。 */
    public void dropPapi() {
        papiBridge = null;
    }
}
