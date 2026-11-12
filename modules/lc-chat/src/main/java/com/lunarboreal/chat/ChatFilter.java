package com.lunarboreal.chat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 反刷屏/敏感词/广告过滤。线程安全，可在异步聊天线程调用。 */
final class ChatFilter {

    /** blockKey 非空表示拦截（语言 key）；否则 text 为处理后的文本。 */
    record Result(String blockKey, String text, boolean violation) {
        static Result block(String key) { return new Result(key, null, true); }
        boolean blocked() { return blockKey != null; }
    }

    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}[.,。 ]){3}\\d{1,3}(?::\\d{1,5})?\\b");
    private static final Pattern DOMAIN = Pattern.compile("(?i)\\b([a-z0-9-]+(?:\\s*[.。]\\s*[a-z0-9-]+)*\\s*[.。]\\s*(?:com|net|org|cn|top|xyz|cc|me|io|gg|club|vip|site|online|fun|icu|ltd|info|tk))\\b");

    private boolean enabled;
    private long cooldownMs;
    private long duplicateMs;
    private double capsRatio;
    private int capsMin;
    private boolean antiAd;
    private List<String> whitelist = List.of();
    private List<Pattern> badWords = List.of();

    private boolean punishEnabled;
    private int punishThreshold;
    private long punishWindowMs;
    private List<String> punishCommands = List.of();
    private final Map<UUID, Deque<Long>> violations = new ConcurrentHashMap<>();

    void load(ConfigurationSection s) {
        if (s == null) { enabled = false; return; }
        enabled = s.getBoolean("enabled", true);
        cooldownMs = s.getLong("cooldown-ms", 1500);
        duplicateMs = s.getLong("duplicate-seconds", 30) * 1000L;
        capsRatio = s.getDouble("caps.max-ratio", 0.7);
        capsMin = s.getInt("caps.min-length", 8);
        antiAd = s.getBoolean("anti-ad.enabled", true);
        whitelist = s.getStringList("anti-ad.whitelist").stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        List<Pattern> words = new ArrayList<>();
        for (String w : s.getStringList("bad-words")) {
            if (!w.isBlank()) words.add(Pattern.compile(Pattern.quote(w), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        badWords = words;
        punishEnabled = s.getBoolean("punish.enabled", true);
        punishThreshold = s.getInt("punish.threshold", 5);
        punishWindowMs = s.getLong("punish.window-seconds", 60) * 1000L;
        punishCommands = s.getStringList("punish.commands");
    }

    List<String> badWordList() {
        return badWords.stream().map(p -> p.pattern().replace("\\Q", "").replace("\\E", "")).toList();
    }

    Result check(Player player, ChatUser user, String message) {
        if (!enabled || player.hasPermission("lc.chat.bypass.filter")) return new Result(null, message, false);
        long now = System.currentTimeMillis();
        boolean bypassSpam = player.hasPermission("lc.chat.bypass.spam");
        if (!bypassSpam) {
            if (now - user.lastChatAt < cooldownMs) return Result.block("filter.cooldown");
            String norm = message.trim().toLowerCase(Locale.ROOT);
            if (norm.equals(user.lastMessage) && now - user.lastMessageAt < duplicateMs) return Result.block("filter.duplicate");
        }
        if (antiAd && !player.hasPermission("lc.chat.bypass.ad") && containsAd(message)) return Result.block("filter.advertising");

        String text = message;
        boolean violation = false;
        for (Pattern p : badWords) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                violation = true;
                text = m.replaceAll(r -> "*".repeat(r.group().length()));
            }
        }
        if (!bypassSpam && text.length() >= capsMin) {
            int letters = 0, upper = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isLetter(c) && c < 128) {
                    letters++;
                    if (Character.isUpperCase(c)) upper++;
                }
            }
            if (letters > 0 && (double) upper / letters > capsRatio) text = text.toLowerCase(Locale.ROOT);
        }
        return new Result(null, text, violation);
    }

    void accepted(ChatUser user, String original) {
        long now = System.currentTimeMillis();
        user.lastChatAt = now;
        user.lastMessage = original.trim().toLowerCase(Locale.ROOT);
        user.lastMessageAt = now;
    }

    private boolean containsAd(String message) {
        if (IP.matcher(message).find()) return true;
        Matcher m = DOMAIN.matcher(message);
        while (m.find()) {
            String d = m.group(1).replaceAll("\\s", "").replace('。', '.').toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String w : whitelist) if (d.endsWith(w)) { ok = true; break; }
            if (!ok) return true;
        }
        return false;
    }

    /** 记录一次违规，达到阈值时返回需要执行的命令（已替换 {player}），否则空列表。 */
    List<String> recordViolation(Player player) {
        if (!punishEnabled) return List.of();
        long now = System.currentTimeMillis();
        Deque<Long> q = violations.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > punishWindowMs) q.pollFirst();
            if (q.size() >= punishThreshold) {
                q.clear();
                return punishCommands.stream().map(c -> c.replace("{player}", player.getName())).toList();
            }
        }
        return List.of();
    }

    void forget(UUID uuid) {
        violations.remove(uuid);
    }
}
