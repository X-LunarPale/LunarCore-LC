package com.lunarboreal.core.message;

import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.core.LunarCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MiniMessage 多语言消息服务。
 * 语言包来源：各命名空间 ClassLoader 中的 lang/&lt;locale&gt;.yml，释放到 plugins/LunarCore/lang/&lt;locale&gt;/&lt;namespace&gt;.yml，
 * 每次加载时补全缺失键。
 */
public final class LbrMessageService implements MessageService {

    public static final String DEFAULT_LOCALE = "zh_CN";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])|[&§]#([0-9a-fA-F]{6})");

    private final LunarCorePlugin plugin;
    private final Map<String, ClassLoader> bundles = new ConcurrentHashMap<>();
    /** 完整 key → String 或 List&lt;String&gt; */
    private final Map<String, Object> messages = new ConcurrentHashMap<>();
    private final Map<String, String> prefixes = new ConcurrentHashMap<>();
    private volatile String locale = DEFAULT_LOCALE;
    private volatile String prefix = "<aqua>[核心]</aqua> ";

    public LbrMessageService(LunarCorePlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getConfig().getString("language", DEFAULT_LOCALE);
    }

    /** 重新读取 language 配置并重新加载全部语言包。 */
    public synchronized void reload() {
        this.locale = plugin.getConfig().getString("language", DEFAULT_LOCALE);
        messages.clear();
        prefixes.clear();
        for (Map.Entry<String, ClassLoader> e : bundles.entrySet()) {
            load(e.getKey(), e.getValue());
        }
        refreshPrefix();
    }

    private void refreshPrefix() {
        Object p = messages.get("core.prefix");
        this.prefix = p == null ? "<dark_gray>[</dark_gray><aqua><bold>核心</bold></aqua><dark_gray>]</dark_gray> " : convertLegacy(String.valueOf(p));
        prefixes.put("core", this.prefix);
    }

    @Override
    public synchronized void registerBundle(String namespace, ClassLoader loader) {
        bundles.put(namespace, loader);
        messages.keySet().removeIf(k -> k.startsWith(namespace + "."));
        prefixes.remove(namespace);
        load(namespace, loader);
        if (namespace.equals("core")) refreshPrefix();
    }

    @Override
    public synchronized void unregisterBundle(String namespace) {
        bundles.remove(namespace);
        messages.keySet().removeIf(k -> k.startsWith(namespace + "."));
        prefixes.remove(namespace);
    }

    /** 外部插件卸载时清理其注册的语言包。 */
    public synchronized void unregisterBundles(ClassLoader loader) {
        List<String> remove = new ArrayList<>();
        bundles.forEach((ns, cl) -> { if (cl == loader) remove.add(ns); });
        remove.forEach(this::unregisterBundle);
    }

    private static URL findResource(ClassLoader loader, String path) {
        // 优先只在自身 jar 中查找，避免父加载器（core 插件 jar）中的同名资源遮蔽
        if (loader instanceof URLClassLoader u) {
            URL url = u.findResource(path);
            if (url != null) return url;
        }
        return loader.getResource(path);
    }

    private YamlConfiguration readBundled(ClassLoader loader, String loc) {
        URL url = findResource(loader, "lang/" + loc + ".yml");
        if (url == null) return null;
        try {
            var conn = url.openConnection();
            conn.setUseCaches(false);
            try (InputStream in = conn.getInputStream()) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("读取语言包失败 " + url + ": " + e.getMessage());
            return null;
        }
    }

    private void load(String namespace, ClassLoader loader) {
        YamlConfiguration bundled = readBundled(loader, locale);
        YamlConfiguration fallback = locale.equals(DEFAULT_LOCALE) ? null : readBundled(loader, DEFAULT_LOCALE);
        if (bundled == null) bundled = fallback;

        File file = new File(plugin.getDataFolder(), "lang/" + locale + "/" + namespace + ".yml");
        YamlConfiguration cfg;
        if (file.exists()) {
            cfg = YamlConfiguration.loadConfiguration(file);
            if (bundled != null) {
                boolean changed = namespace.equals("core") && migrateCoreBranding(cfg, bundled);
                for (String key : bundled.getKeys(true)) {
                    if (bundled.isConfigurationSection(key)) continue;
                    if (!cfg.contains(key, true)) {
                        cfg.set(key, bundled.get(key));
                        List<String> comments = bundled.getComments(key);
                        if (!comments.isEmpty()) cfg.setComments(key, comments);
                        changed = true;
                    }
                }
                if (changed) {
                    try {
                        cfg.save(file);
                    } catch (IOException e) {
                        plugin.getLogger().warning("补全语言文件失败 " + file + ": " + e.getMessage());
                    }
                }
            }
        } else if (bundled != null) {
            cfg = bundled;
            copyBundled(loader, file, bundled);
        } else {
            plugin.debug("命名空间 " + namespace + " 没有语言包 lang/" + locale + ".yml");
            return;
        }
        flatten(namespace, cfg);
        String configuredPrefix = lookup(namespace + ".prefix");
        prefixes.put(namespace, resolveNamespacePrefix(namespace, configuredPrefix));
        // jar 内默认值兜底（文件损坏或键被删除）
        if (bundled != null) {
            for (String key : bundled.getKeys(true)) {
                if (bundled.isConfigurationSection(key)) continue;
                messages.putIfAbsent(namespace + "." + key, value(bundled, key));
            }
        }
        if (fallback != null && fallback != bundled) {
            for (String key : fallback.getKeys(true)) {
                if (fallback.isConfigurationSection(key)) continue;
                messages.putIfAbsent(namespace + "." + key, value(fallback, key));
            }
        }
    }

    static boolean migrateCoreBranding(YamlConfiguration cfg, YamlConfiguration bundled) {
        boolean changed = false;
        String oldPrefix = cfg.getString("prefix");
        if ("<gradient:#7ec8ff:#b98cff><bold>LBR</bold></gradient> <dark_gray>» ".equals(oldPrefix)
                || "<dark_gray>[</dark_gray><aqua><bold>核心</bold></aqua><dark_gray>]</dark_gray> ".equals(oldPrefix)) {
            cfg.set("prefix", bundled.getString("prefix"));
            changed = true;
        }
        for (String key : cfg.getKeys(true)) {
            Object old = cfg.get(key);
            Object updated = old;
            if (old instanceof String text) updated = text.replaceAll("(?i)/lbr(?=\\s|$)", "/lc");
            else if (old instanceof List<?> lines) updated = lines.stream().map(line ->
                    line instanceof String text ? text.replaceAll("(?i)/lbr(?=\\s|$)", "/lc") : line).toList();
            if (!java.util.Objects.equals(old, updated)) {
                cfg.set(key, updated);
                changed = true;
            }
        }
        return changed;
    }

    private void copyBundled(ClassLoader loader, File file, YamlConfiguration bundled) {
        file.getParentFile().mkdirs();
        URL url = findResource(loader, "lang/" + locale + ".yml");
        try {
            if (url != null) {
                var conn = url.openConnection();
                conn.setUseCaches(false);
                try (InputStream in = conn.getInputStream()) {
                    java.nio.file.Files.copy(in, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                bundled.save(file);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("释放语言文件失败 " + file + ": " + e.getMessage());
        }
    }

    private void flatten(String namespace, ConfigurationSection cfg) {
        for (String key : cfg.getKeys(true)) {
            if (cfg.isConfigurationSection(key)) continue;
            Object v = value(cfg, key);
            if (v != null) messages.put(namespace + "." + key, v);
        }
    }

    private static Object value(ConfigurationSection cfg, String key) {
        if (cfg.isList(key)) {
            List<String> list = new ArrayList<>();
            for (Object o : cfg.getList(key, List.of())) list.add(String.valueOf(o));
            return List.copyOf(list);
        }
        Object v = cfg.get(key);
        return v == null ? null : String.valueOf(v);
    }

    // ------------------------------------------------------------------

    public boolean has(String key) {
        return get(key) != null;
    }

    /**
     * 取值；YAML 1.1 会把 yes/no/on/off/y/n 键解析成布尔（键名变成 true/false），这里做兼容回退。
     */
    private Object get(String key) {
        Object v = messages.get(key);
        if (v != null) return v;
        int dot = key.lastIndexOf('.');
        String last = key.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String alt = switch (last) {
            case "yes", "on", "y", "true" -> "true";
            case "no", "off", "n", "false" -> "false";
            default -> null;
        };
        return alt == null ? null : messages.get(key.substring(0, dot + 1) + alt);
    }

    /** 获取消息原文（列表以换行连接），不存在返回 null。 */
    private String lookup(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof List<?> l) return String.join("\n", l.stream().map(String::valueOf).toList());
        return (String) v;
    }

    private List<String> lookupLines(String key) {
        Object v = get(key);
        if (v == null) return null;
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of((String) v);
    }

    private String namespacePrefix(String key) {
        int dot = key.indexOf('.');
        return dot > 0 ? prefixes.getOrDefault(key.substring(0, dot), prefix) : prefix;
    }

    static String resolveNamespacePrefix(String namespace, String configuredPrefix) {
        // Older essentials.yml files still contain <prefix> in message templates.
        if ("essentials".equals(namespace)) return "";
        return configuredPrefix == null || configuredPrefix.isBlank()
                ? "<gray>[" + namespace + "]</gray> " : convertLegacy(configuredPrefix);
    }

    private String withNamespacePrefix(String text, String key) {
        return text == null ? null : text.replace("<prefix>", namespacePrefix(key));
    }

    private String missing(String key) {
        plugin.debug("缺失语言键: " + key + " (" + locale + ")");
        return key;
    }

    /** 替换 {name} 占位符。Component 值序列化为 MiniMessage。 */
    public static String replace(String text, Object... placeholders) {
        if (text == null || placeholders == null || placeholders.length < 2) return text;
        String out = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            out = out.replace("{" + placeholders[i] + "}", placeholderValue(placeholders[i + 1]));
        }
        return out;
    }

    /**
     * 占位符值安全规则：Rich → 可信 MiniMessage 原样插入（处理 & 颜色码）；Component → 序列化为 MiniMessage；
     * 其它对象 toString 后 escapeTags，防止玩家输入注入 click/hover 等标签。
     */
    public static String placeholderValue(Object v) {
        if (v instanceof MessageService.Rich r) return convertLegacy(r.miniMessage());
        if (v instanceof Component c) return MM.serialize(c);
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return MM.escapeTags(String.valueOf(v));
    }

    /** 把 &a / §a / &#RRGGBB 旧式颜色码转换为 MiniMessage 标签。 */
    public static String convertLegacy(String text) {
        if (text == null || (text.indexOf('&') < 0 && text.indexOf('§') < 0)) return text;
        Matcher m = LEGACY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(2) != null) {
                rep = "<#" + m.group(2) + ">";
            } else {
                rep = switch (Character.toLowerCase(m.group(1).charAt(0))) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> m.group();
                };
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private TagResolver resolvers() {
        return Placeholder.parsed("prefix", prefix);
    }

    // ------------------------------------------------------------------

    @Override
    public void send(CommandSender to, String key, Object... placeholders) {
        if (to == null) return;
        List<String> lines = lookupLines(key);
        if (lines == null) {
            to.sendMessage(net.kyori.adventure.text.Component.text(missing(key)));
            return;
        }
        for (String line : lines) {
            if (line.isEmpty()) continue; // 空字符串表示不发送
            to.sendMessage(parse(withNamespacePrefix(line, key), placeholders));
        }
    }

    /** 发送一段任意 MiniMessage 文本。 */
    public void sendRaw(CommandSender to, String miniMessage, Object... placeholders) {
        if (miniMessage == null || miniMessage.isEmpty()) return;
        to.sendMessage(parse(miniMessage, placeholders));
    }

    @Override
    public Component component(String key, Object... placeholders) {
        String text = lookup(key);
        if (text == null) return Component.text(missing(key));
        return parse(withNamespacePrefix(text, key), placeholders);
    }

    @Override
    public String raw(String key, Object... placeholders) {
        String text = lookup(key);
        if (text == null) return missing(key);
        return replace(convertLegacy(withNamespacePrefix(text, key)), placeholders);
    }

    @Override
    public String prefix(String namespace) {
        return prefixes.getOrDefault(namespace, prefix);
    }

    /** 列表消息逐行（已替换占位符，未解析）。 */
    public List<String> rawLines(String key, Object... placeholders) {
        List<String> lines = lookupLines(key);
        if (lines == null) return List.of(missing(key));
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(replace(convertLegacy(withNamespacePrefix(l, key)), placeholders));
        return out;
    }

    @Override
    public Component parse(String miniMessage, Object... placeholders) {
        if (miniMessage == null) return Component.empty();
        String text = replace(convertLegacy(miniMessage), placeholders);
        try {
            return MM.deserialize(text, resolvers());
        } catch (Exception e) {
            plugin.debug("MiniMessage 解析失败: " + e.getMessage() + " | " + text);
            return Component.text(text);
        }
    }

    @Override
    public String locale() {
        return locale;
    }

    public Map<String, ClassLoader> bundles() {
        return Map.copyOf(bundles);
    }
}
