package com.lunarboreal.core.web;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.core.message.LcMessageService;
import com.lunarboreal.core.placeholder.LcPlaceholderRegistry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Authenticated, side-effect-free text resolution; Bukkit/PAPI work is confined to ctx.sync. */
final class TextPreviewApi {
    private static final Pattern TOKEN = Pattern.compile("%[^%\\s]+%");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    void register(WebServer web) {
        web.routes.add("GET", "/api/text/preview", null, false, this::status, web, "core");
        web.routes.add("POST", "/api/text/preview", null, false, this::preview, web, "core");
    }

    private static void authorize(WebContext ctx, boolean status) {
        var user = ctx.user();
        if (user == null) throw new HttpException(401, "请先登录");
        if (user.hasPermission("core.config.edit") || user.hasPermission("core.server.manage")
                || user.hasPermission("motd.manage") || (status && user.hasPermission("motd.view"))) return;
        throw HttpException.forbidden("没有文本预览权限");
    }

    private static boolean available() {
        return LC.get().placeholders() instanceof LcPlaceholderRegistry registry && registry.isPapiHooked();
    }

    private void status(WebContext ctx) throws Exception {
        authorize(ctx, true);
        ctx.json(ctx.sync(() -> {
            List<Map<String, Object>> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) players.add(identity(player));
            boolean enabled = available();
            return Json.of("placeholderApiAvailable", enabled, "players", players,
                    "message", enabled ? "PlaceholderAPI 已连接" : "未连接 PlaceholderAPI，变量将保留原文");
        }));
    }

    private void preview(WebContext ctx) throws Exception {
        authorize(ctx, false);
        Map<String, Object> body = ctx.bodyMap();
        if (!(body.get("text") instanceof String text) || text.length() > 4096)
            throw HttpException.badRequest("text 必须为不超过 4096 个字符的字符串");
        String player = optionalString(body, "player");
        String format = optionalString(body, "format");
        if (format != null && !format.equals("motd") && !format.equals("message"))
            throw HttpException.badRequest("format 必须为 motd 或 message");
        ctx.json(ctx.sync(() -> resolve(text, player, "motd".equals(format))));
    }

    private static String optionalString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw HttpException.badRequest(key + " 必须为字符串");
        return text;
    }

    private static Map<String, Object> identity(Player player) {
        return Json.of("uuid", player.getUniqueId().toString(), "name", player.getName());
    }

    /** Resolves each recipient's context without reparsing expansion output as actions. */
    static String messageSource(String text, Player player) {
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder result = new StringBuilder();
        Map<String, String> values = new java.util.HashMap<>();
        while (matcher.find()) {
            String replacement = values.computeIfAbsent(matcher.group(), token -> {
                String value = available() ? LC.get().placeholders().apply(player, token) : token;
                if (value == null || value.length() > 65536) return token;
                return token.equals(value) ? token : LcMessageService.convertLegacy(MM.escapeTags(value));
            });
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, Object> resolve(String text, String selector, boolean motd) {
        Player player = null;
        if (selector != null && !selector.isBlank()) {
            try { player = Bukkit.getPlayer(UUID.fromString(selector.trim())); }
            catch (IllegalArgumentException ignored) { player = Bukkit.getPlayerExact(selector.trim()); }
            if (player == null || !player.isOnline()) throw HttpException.notFound("预览玩家不在线");
        }
        boolean enabled = available();
        String source = text;
        Map<String, String> literalValues = new java.util.LinkedHashMap<>();
        String message;
        try {
            // Expand each unique token only once. Expansion output cannot introduce MiniMessage actions
            // or recursively trigger another placeholder; legacy colors remain supported in message mode.
            String input = text;
            if (motd) {
                literalValues.put("online", Integer.toString(Bukkit.getOnlinePlayers().size()));
                literalValues.put("max", Integer.toString(Bukkit.getMaxPlayers()));
                literalValues.put("version", Bukkit.getMinecraftVersion());
            }
            Matcher matcher = TOKEN.matcher(input);
            StringBuilder result = new StringBuilder();
            Map<String, String> values = new java.util.HashMap<>();
            while (matcher.find()) {
                String token = matcher.group();
                Player context = player;
                String replacement = values.computeIfAbsent(token, key -> {
                    String value = enabled ? LC.get().placeholders().apply(context, key) : key;
                    if (value == null) throw new IllegalStateException("变量返回空结果");
                    if (value.length() > 65536) throw new IllegalStateException("变量结果过长");
                    if (key.equals(value)) return key;
                    if (motd) {
                        String marker = "papi_preview_" + literalValues.size();
                        literalValues.put(marker, value.replaceAll("[\\r\\n\\t\\u2028\\u2029]", " "));
                        return "{" + marker + "}";
                    }
                    return LcMessageService.convertLegacy(MM.escapeTags(value));
                });
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
                if (result.length() > 65536) throw new IllegalStateException("预览结果过长");
            }
            matcher.appendTail(result);
            source = result.toString();
            message = enabled ? "变量解析完成" : "未连接 PlaceholderAPI，变量已保留原文";
        } catch (RuntimeException | LinkageError ex) {
            source = text;
            message = "变量解析失败，已保留输入原文";
        }
        var unresolved = new LinkedHashSet<String>();
        Matcher remaining = TOKEN.matcher(source);
        while (remaining.find()) unresolved.add(remaining.group());
        if (enabled && !unresolved.isEmpty()) message = "部分变量未解析或扩展解析失败；请检查扩展及玩家上下文";
        Map<String, Object> out = Json.of("text", source, "placeholderApiAvailable", enabled,
                "unresolved", List.copyOf(unresolved), "message", message, "variables", motd ? literalValues : Map.of());
        if (player != null) out.put("player", identity(player));
        return out;
    }
}
