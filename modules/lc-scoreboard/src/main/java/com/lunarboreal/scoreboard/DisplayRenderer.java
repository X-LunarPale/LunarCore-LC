package com.lunarboreal.scoreboard;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Sole display owner. Never writes displayName, name tags, TAB or foreign objectives. */
final class DisplayRenderer {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VARIABLE = Pattern.compile("%[A-Za-z0-9_:.]+%");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final ScoreboardModule module;
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Map<String,String>> variables = new HashMap<>();
    private long elapsed;
    private long lastWarning;
    private static boolean occupied(org.bukkit.scoreboard.Scoreboard board) { return !board.getTeams().isEmpty() || !board.getObjectives().isEmpty(); }
    private static final class View {
        org.bukkit.scoreboard.Scoreboard own, original;
        Objective objective;
        BossBar bar;
        boolean blocked;
    }
    DisplayRenderer(ScoreboardModule module) { this.module = module; }
    static boolean mayTake(org.bukkit.scoreboard.Scoreboard current, org.bukkit.scoreboard.Scoreboard main) { return current == main; }
    static boolean mayRestore(org.bukkit.scoreboard.Scoreboard current, org.bukkit.scoreboard.Scoreboard own) { return current == own; }
    void refresh() {
        ScoreboardSettings settings = module.settings(); elapsed += settings.updateTicks(); variables.clear();
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            View view = views.computeIfAbsent(player.getUniqueId(), ignored -> new View());
            ScoreboardSettings.Mode mode = module.effective(player);
            if (mode != ScoreboardSettings.Mode.SIDEBAR) releaseBoard(player, view);
            if (mode != ScoreboardSettings.Mode.ISLAND && view.bar != null) { player.hideBossBar(view.bar); view.bar = null; }
            try {
                if (mode == ScoreboardSettings.Mode.SIDEBAR) sidebar(player, view, settings);
                else if (mode == ScoreboardSettings.Mode.ISLAND) island(player, view, settings);
            } catch (RuntimeException ex) {
                forget(player);
                if (System.nanoTime() - lastWarning > 60_000_000_000L || lastWarning == 0) {
                    lastWarning = System.nanoTime(); module.logger().warning("计分板刷新失败，已释放该玩家显示：" + ex.getClass().getSimpleName());
                }
            }
        }
        views.keySet().retainAll(online);
    }
    private Component render(Player player, String template) {
        return renderTemplate(template, variables.computeIfAbsent(player.getUniqueId(), ignored -> values(player)), key -> module.api().placeholders().apply(player, key));
    }
    private Map<String,String> values(Player player) {
        double tps = 20, mspt = 0;
        try { tps = Bukkit.getServer().getTPS()[0]; mspt = Bukkit.getServer().getAverageTickTime(); } catch (LinkageError ignored) { }
        Map<String, String> vars = new HashMap<>(Map.of("player", player.getName(), "world", player.getWorld().getName(), "online", String.valueOf(Bukkit.getOnlinePlayers().size()),
                "ping", String.valueOf(player.getPing()), "tps", String.format(Locale.ROOT, "%.2f", Math.min(20, tps)), "mspt", String.format(Locale.ROOT, "%.2f", mspt)));
        String title = module.api().placeholders().apply(player, "%lc_playertitle_current_plain%");
        vars.put("title", title == null || title.isBlank() || title.equals("%lc_playertitle_current_plain%") ? "未佩戴" : title);
        String balance = module.api().placeholders().apply(player, "%lc_economy_balance_formatted%");
        vars.put("balance", balance == null || balance.isBlank() || balance.equals("%lc_economy_balance_formatted%") ? "暂无数据" : balance);
        vars.put("max_players", String.valueOf(Bukkit.getMaxPlayers()));
        vars.put("entities", String.valueOf(Bukkit.getWorlds().stream().mapToInt(world -> world.getEntityCount()).sum()));
        vars.put("chunks", String.valueOf(Bukkit.getWorlds().stream().mapToInt(world -> world.getLoadedChunks().length).sum()));
        Runtime runtime = Runtime.getRuntime();
        vars.put("memory_used", String.valueOf((runtime.totalMemory() - runtime.freeMemory()) / 1048576));
        vars.put("memory_max", String.valueOf(runtime.maxMemory() / 1048576));
        return vars;
    }
    static Component renderTemplate(String template, Map<String,String> variables, Function<String,String> registry) {
        List<TagResolver> tags = new ArrayList<>();
        String text = template;
        for (var entry : variables.entrySet()) {
            String tag = "sbvar_" + entry.getKey();
            text = text.replace("{" + entry.getKey() + "}", "<" + tag + ">");
            tags.add(Placeholder.unparsed(tag, entry.getValue()));
        }
        var matcher = VARIABLE.matcher(text); StringBuilder out = new StringBuilder(); int index = 0;
        while (matcher.find()) {
            String tag = "sbplaceholder_" + index++;
            String value = registry.apply(matcher.group());
            tags.add(Placeholder.unparsed(tag, value == null ? "" : value));
            matcher.appendReplacement(out, "<" + tag + ">");
        }
        matcher.appendTail(out);
        return MM.deserialize(out.toString(), TagResolver.resolver(tags));
    }
    private void sidebar(Player player, View view, ScoreboardSettings settings) {
        boolean admin = settings.adminEnabled() && player.hasPermission("lc.scoreboard.admin-view");
        String title = admin ? settings.adminTitle() : settings.title();
        List<String> lines = admin ? settings.adminLines() : settings.lines();
        var manager = Bukkit.getScoreboardManager();
        var current = player.getScoreboard();
        if (view.own != null && view.original != null && occupied(view.original)) {
            releaseBoard(player, view); view.blocked = true; return;
        }
        if (view.own != null && current != view.own) { view.own = null; view.objective = null; view.original = null; view.blocked = true; }
        if (view.own == null) {
            if (!mayTake(current, manager.getMainScoreboard()) || occupied(current)) { view.blocked = true; return; }
            view.original = current; view.own = manager.getNewScoreboard();
            view.objective = view.own.registerNewObjective("lc_sidebar", Criteria.DUMMY, render(player, title));
            view.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            view.objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            player.setScoreboard(view.own); view.blocked = false;
        }
        view.objective.displayName(render(player, title));
        Set<String> retained = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String entry = "lc-line-" + i; retained.add(entry);
            Score score = view.objective.getScore(entry);
            score.customName(render(player, lines.get(i)));
            if (!score.isScoreSet() || score.getScore() != lines.size() - i) score.setScore(lines.size() - i);
        }
        for (String entry : new HashSet<>(view.own.getEntries())) if (!retained.contains(entry)) view.own.resetScores(entry);
    }
    private void island(Player player, View view, ScoreboardSettings settings) {
        int frame = settings.rotate() ? (int) ((elapsed / settings.rotationTicks()) % settings.frames().size()) : 0;
        Component name = render(player, settings.frames().get(frame));
        if (module.pack() != null && module.pack().loaded(player.getUniqueId())) name = module.pack().wrapLoaded(name);
        if (view.bar == null) { view.bar = BossBar.bossBar(name, settings.progress(), settings.color(), BossBar.Overlay.PROGRESS); player.showBossBar(view.bar); }
        else { view.bar.name(name).color(settings.color()).progress(settings.progress()); }
        view.blocked = false;
    }
    private void releaseBoard(Player player, View view) {
        if (view.own != null && mayRestore(player.getScoreboard(), view.own) && view.original != null) player.setScoreboard(view.original);
        view.own = null; view.original = null; view.objective = null; view.blocked = false;
    }
    void forget(Player player) {
        View view = views.remove(player.getUniqueId()); if (view == null) return;
        releaseBoard(player, view); if (view.bar != null) player.hideBossBar(view.bar);
    }
    void close() { for (Player player : Bukkit.getOnlinePlayers()) forget(player); views.clear(); elapsed = 0; }
    List<Map<String,Object>> status() {
        int sidebar = 0, island = 0, blocked = 0;
        for (View view : views.values()) { if (view.own != null) sidebar++; if (view.bar != null) island++; if (view.blocked) blocked++; }
        return List.of(Map.of("label", "侧边栏", "value", sidebar), Map.of("label", "顶部信息条", "value", island), Map.of("label", "外部计分板占用", "value", blocked));
    }
}
