package com.lunarboreal.scoreboard;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Optional pack, appended with an independent UUID; never replaces other server packs. */
final class IslandPack implements Listener, AutoCloseable {
    static final UUID ID = UUID.fromString("2f623ee4-09f7-492e-8cb1-3a75bdc4cf6d");
    static final int WIDTH = 192, ADVANCE = 193, TEXT_BUDGET = 168, LIMIT = 32;
    private Map<Integer,Integer> widths = Map.of();
    private final ScoreboardModule module;
    private final Map<UUID, String> states = new HashMap<>();
    private final Set<UUID> offered = new HashSet<>();
    private Settings settings;
    private boolean closing;
    record Settings(boolean enabled, boolean autoOffer, String url) {
        Settings {
            if (url == null) throw new IllegalArgumentException("资源包地址不能为空值");
            if (url.isBlank()) {
                if (enabled && autoOffer) throw new IllegalArgumentException("自动发送资源包前须配置地址");
            } else {
                try {
                    URI uri = URI.create(url);
                    if (url.length() > 2048 || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                } catch (RuntimeException ex) { throw new IllegalArgumentException("资源包地址须为有效HTTP或HTTPS直链"); }
            }
        }
        Map<String,Object> values() { return Map.of("enabled", enabled, "autoOffer", autoOffer, "url", url); }
        static Settings from(Map<String,Object> map) {
            if (!(map.get("enabled") instanceof Boolean enabled) || !(map.get("autoOffer") instanceof Boolean automatic) || !(map.get("url") instanceof String url)) throw new IllegalArgumentException("资源包开关或地址类型无效");
            return new Settings(enabled, automatic, url);
        }
    }
    private byte[] hash;
    IslandPack(ScoreboardModule module) { this.module = module; }
    void start() {
        try (InputStream stream = module.moduleClassLoader().getResourceAsStream("island-pack.properties")) {
            if (stream != null) {
                Properties properties = new Properties(); properties.load(stream);
                String value = properties.getProperty("sha1", "");
                if (!value.matches("[a-fA-F0-9]{40}")) throw new IOException("Invalid bundled pack SHA1");
                hash = HexFormat.of().parseHex(value);
            }
        } catch (IOException ex) { module.logger().warning("资源包摘要读取失败，已保持原版显示"); }
        try (InputStream stream = module.moduleClassLoader().getResourceAsStream("island-widths.properties")) {
            if (stream != null) widths = readWidths(stream);
        } catch (IOException | IllegalArgumentException ex) { module.logger().warning("资源包字宽数据读取失败，已保持原版显示"); }
        load(); module.registerListener(this);
        if (settings.enabled() && settings.autoOffer()) for (Player player : Bukkit.getOnlinePlayers()) offer(player);
    }
    void load() {
        Path file = module.dataFolder().toPath().resolve("resource-pack.yml");
        if (!Files.exists(file)) {
            settings = new Settings(true, false, "");
            try { write(settings); } catch (IOException ex) { throw new IllegalStateException("无法保存资源包设置", ex); }
        } else {
            YamlConfiguration cfg = new YamlConfiguration();
            try { cfg.load(file.toFile()); } catch (Exception ex) { throw new IllegalArgumentException("资源包配置无效", ex); }
            settings = Settings.from(Map.of("enabled", cfg.get("pack-enabled", true), "autoOffer", cfg.get("auto-offer", true), "url", cfg.get("url", "")));
        }
    }
    private void write(Settings value) throws IOException {
        YamlConfiguration cfg = new YamlConfiguration(); cfg.set("pack-enabled", value.enabled()); cfg.set("auto-offer", value.autoOffer()); cfg.set("url", value.url());
        Path target = module.dataFolder().toPath().resolve("resource-pack.yml"); Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), "pack-", ".tmp");
        try { Files.writeString(temp, cfg.saveToString(), StandardCharsets.UTF_8); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
    Map<String,Object> values() { return settings.values(); }
    Map<String,Object> info() { return Map.of("id", ID.toString(), "url", settings.url(), "sha1", hash == null ? "" : HexFormat.of().formatHex(hash), "loaded", states.values().stream().filter("SUCCESSFULLY_LOADED"::equals).count(), "available", available()); }
    void save(Settings replacement) throws IOException {
        if (closing) throw new IllegalStateException("模块正在关闭");
        write(replacement); removeAll(); settings = replacement;
        if (settings.enabled() && settings.autoOffer()) for (Player player : Bukkit.getOnlinePlayers()) offer(player);
    }
    private boolean available() { return hash != null && !widths.isEmpty() && settings != null && !settings.url().isBlank(); }
    Component wrapLoaded(Component text) { return wrap(text, widths); }
    boolean loaded(UUID player) { return available() && settings.enabled() && !closing && "SUCCESSFULLY_LOADED".equals(states.get(player)); }
    boolean offer(Player player) {
        if (closing || !settings.enabled() || !available()) return false;
        if (offered.contains(player.getUniqueId()) && (loaded(player.getUniqueId()) || "OFFERED".equals(states.get(player.getUniqueId())) || "ACCEPTED".equals(states.get(player.getUniqueId())) || "DOWNLOADED".equals(states.get(player.getUniqueId())))) return true;
        states.put(player.getUniqueId(), "OFFERED"); offered.add(player.getUniqueId());
        try {
            player.addResourcePack(ID, settings.url(), hash.clone(), "加载顶部胶囊显示资源包（可选）", false);
            return true;
        } catch (RuntimeException ex) { offered.remove(player.getUniqueId()); states.put(player.getUniqueId(), "SEND_FAILED"); return false; }
    }
    static boolean accepts(UUID eventId, UUID player, Set<UUID> pending) { return ID.equals(eventId) && pending.contains(player); }
    @EventHandler public void status(PlayerResourcePackStatusEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        if (closing || !accepts(event.getID(), player, offered)) return;
        states.put(player, event.getStatus().name()); module.refreshDisplays();
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        if (!closing && settings.enabled() && settings.autoOffer()) module.runTaskLater(() -> {
            if (!closing && event.getPlayer().isOnline()) offer(event.getPlayer());
        }, 20L);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { states.remove(event.getPlayer().getUniqueId()); offered.remove(event.getPlayer().getUniqueId()); }
    List<Map<String,Object>> status() {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) rows.add(Map.of("uuid", player.getUniqueId().toString(), "name", player.getName(), "status", states.getOrDefault(player.getUniqueId(), !available() ? "PACK_UNAVAILABLE" : "NOT_OFFERED"), "loaded", loaded(player.getUniqueId())));
        return rows;
    }
    private void removeAll() {
        Set<UUID> previous = Set.copyOf(offered);
        states.clear(); offered.clear();
        for (Player player : Bukkit.getOnlinePlayers()) if (previous.contains(player.getUniqueId())) {
            try { player.removeResourcePack(ID); } catch (RuntimeException | LinkageError ignored) { }
        }
        states.clear(); offered.clear();
    }
    @Override public void close() { closing = true; HandlerList.unregisterAll(this); removeAll(); }
    static Map<Integer,Integer> readWidths(InputStream input) throws IOException {
        Properties properties = new Properties(); properties.load(input);
        Map<Integer,Integer> map = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            int cp = Integer.parseInt(key, 16), advance = Integer.parseInt(properties.getProperty(key));
            if (!Character.isValidCodePoint(cp) || advance < 1 || advance > 64) throw new IOException("Invalid glyph width");
            map.put(cp, advance);
        }
        if (!map.containsKey(0x25A1)) throw new IOException("Missing fallback glyph width");
        return Map.copyOf(map);
    }
    static String plain(String raw, Map<Integer,Integer> widths) {
        if (!widths.containsKey(0x25A1)) throw new IllegalArgumentException("Missing glyph widths");
        StringBuilder text = new StringBuilder(); int count = 0, used = 0;
        var iterator = raw.codePoints().iterator();
        while (iterator.hasNext() && count < LIMIT) {
            int cp = iterator.nextInt(); if (!widths.containsKey(cp)) cp = 0x25A1;
            int width = widths.get(cp); if (used + width > TEXT_BUDGET) break;
            text.appendCodePoint(cp); used += width; count++;
        }
        return text.toString();
    }
    static String space(int offset) {
        if (offset < -1023 || offset > 1023) throw new IllegalArgumentException("Offset exceeds bundled font range");
        StringBuilder text = new StringBuilder(); int value = Math.abs(offset), base = offset < 0 ? 0xE100 : 0xE110;
        for (int bit = 0; bit < 10; bit++) if ((value & (1 << bit)) != 0) text.appendCodePoint(base + bit);
        return text.toString();
    }
    static Component wrap(Component original, Map<Integer,Integer> widths) {
        String text = plain(PlainTextComponentSerializer.plainText().serialize(original), widths);
        int width = text.codePoints().map(widths::get).sum();
        int left = (WIDTH - width) / 2, right = WIDTH - width - left;
        Component result = Component.empty().shadowColor(net.kyori.adventure.text.format.ShadowColor.none()).color(NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .append(Component.text("\uE000").font(Key.key("lunar:island")))
            .append(Component.text(space(-1)).font(Key.key("lunar:space")))
            .append(Component.text("\uE002").font(Key.key("lunar:island")))
            .append(Component.text(space(-ADVANCE + left)).font(Key.key("lunar:space")))
            .append(Component.text(text).font(Key.key("lunar:mono")))
            .append(Component.text(space(right)).font(Key.key("lunar:space")));
        return result;
    }
}
