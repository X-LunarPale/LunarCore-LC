package com.lunarboreal.landmarks;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.LandService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

public final class LandmarksModule extends LbrModule {
    private LandmarkRepository repository;
    private volatile LandmarksSettings settings;
    private volatile boolean active;
    private LandmarksGui gui;

    @Override public void onEnable() {
        active = true;
        defaultPermissions("lbr.landmarks.use");
        var store = new LandmarkStore(dataFolder().toPath().resolve("landmarks.properties"));
        repository = new LandmarkRepository(store);
        repository.task(() -> {
            Path file = configPath();
            if (Files.notExists(file)) {
                try (var resource = moduleClassLoader().getResourceAsStream("config.yml")) {
                    if (resource == null) throw new java.io.IOException("Missing default landmarks configuration");
                    LandmarkStore.atomicWrite(file, resource.readAllBytes());
                }
            }
            settings = LandmarksSettings.load(file);
            return null;
        }).thenCompose(ignored -> repository.load(store)).whenComplete((ignored, error) -> {
            if (error != null) logger().log(Level.SEVERE, "建筑地标册无法安全加载，已停止接受操作", unwrap(error));
        });
        gui = new LandmarksGui(this);
        registerListener(gui);
        registerCommand(new LandmarksCommand());
        new LandmarksWeb(this).register();
        runTaskTimer(gui::refreshInvalid, 20, 20);
    }

    @Override public void onReload() {
        repository.task(() -> {
            LandmarksSettings next = LandmarksSettings.load(configPath());
            settings = next;
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) logger().log(Level.WARNING, "建筑地标册配置未重载，保留先前配置", unwrap(error));
        });
    }

    @Override public void onDisable() {
        active = false;
        if (gui != null) gui.closeAll();
        if (repository != null) repository.close();
    }

    Path configPath() { return dataFolder().toPath().resolve("config.yml"); }
    boolean ready() { return active && repository != null && repository.ready() && settings != null; }
    boolean healthy() { return ready() && repository.healthy(); }
    LandmarksSettings settings() { requireReady(); return settings; }
    LandmarkBook book() { requireReady(); return repository.snapshot(); }
    void requireReady() { if (!ready()) throw new IllegalStateException("loading"); }
    Component component(String key, Object... placeholders) {
        return api().messages().component(id() + "." + key, placeholders);
    }

    Optional<LandService.LandInfo> liveClaim(Landmark entry) {
        if (!ready() || !settings.allows(entry.world())) return Optional.empty();
        var service = api().services().get(LandService.class);
        var world = Bukkit.getWorld(entry.world());
        if (service.isEmpty() || world == null) return Optional.empty();
        try {
            var location = new Location(world, entry.x() + .5, entry.y(), entry.z() + .5);
            return service.get().landAt(location)
                    .flatMap(claim -> LandmarkAccess.ownedClaim(entry, List.of(claim)));
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    boolean visibleTo(Player viewer, Landmark entry) {
        if (entry.status() != Landmark.Status.APPROVED || !viewer.hasPermission("lbr.landmarks.use")
                || liveClaim(entry).isEmpty()) return false;
        var service = api().services().get(LandService.class);
        var world = Bukkit.getWorld(entry.world());
        if (service.isEmpty() || world == null) return false;
        try {
            return service.get().can(viewer, new Location(world, entry.x() + .5, entry.y(), entry.z() + .5),
                    LandService.Flag.ENTER);
        } catch (RuntimeException unavailable) { return false; }
    }

    List<Landmark> entries(Player viewer, boolean mine) {
        return book().entries().stream()
                .filter(entry -> mine ? entry.owner().equals(viewer.getUniqueId()) : visibleTo(viewer, entry))
                .sorted(Comparator.comparingLong(Landmark::updatedAt).reversed().thenComparingLong(Landmark::id))
                .toList();
    }

    CompletableFuture<LandmarkBook> update(UnaryOperator<LandmarkBook> update) {
        requireReady();
        return repository.update(update);
    }

    CompletableFuture<Void> saveSettings(LandmarksSettings next) {
        requireReady();
        return repository.task(() -> {
            next.save(configPath());
            settings = next;
            return null;
        });
    }

    void withdraw(Player player, long id, long revision) {
        requireReady();
        UUID actor = player.getUniqueId();
        complete(player, update(state -> state.withdraw(id, actor, revision, System.currentTimeMillis())),
                "withdrawn", "withdraw", Long.toString(id));
    }

    private void submit(Player player, String title, String description) {
        requireReady();
        Location at = player.getLocation();
        if (!settings.allows(at.getWorld().getName())) throw Landmark.problem("world");
        LandService service = api().services().get(LandService.class).orElseThrow(() -> Landmark.problem("unavailable"));
        var land = service.landAt(at).filter(claim -> claim.owner().equals(player.getUniqueId())
                && claim.contains(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()))
                .orElseThrow(() -> Landmark.problem("claim"));
        UUID owner = player.getUniqueId();
        String ownerName = player.getName(), world = at.getWorld().getName();
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        complete(player, update(state -> state.submit(owner, ownerName, land.id(), world, x, y, z,
                title, description, settings.limit(), System.currentTimeMillis())), "submitted", "submit", title);
    }

    private void edit(Player player, long id, String title, String description) {
        Landmark current = book().find(id);
        if (!current.owner().equals(player.getUniqueId())) throw Landmark.problem("owner");
        if (liveClaim(current).isEmpty()) throw Landmark.problem("unavailable");
        UUID actor = player.getUniqueId();
        complete(player, update(state -> state.edit(id, actor, current.revision(), title, description,
                settings.limit(), System.currentTimeMillis())), "edited", "edit", Long.toString(id));
    }

    private void complete(Player player, CompletableFuture<?> result, String message, String action, String detail) {
        UUID actor = player.getUniqueId();
        result.whenComplete((ignored, error) -> later(() -> {
            if (error == null) audit(actor.toString(), action, detail);
            Player current = Bukkit.getPlayer(actor);
            if (current != null) {
                if (error == null) msg(current, message);
                else report(current, error);
            }
        }));
    }

    void later(Runnable task) {
        if (!active) return;
        try { runTask(() -> { if (active) task.run(); }); }
        catch (IllegalStateException closing) { /* The core is shutting down; writes still drain independently. */ }
    }

    void report(CommandSender sender, Throwable error) {
        Throwable cause = unwrap(error);
        String key = cause instanceof IllegalArgumentException ? cause.getMessage()
                : cause instanceof IllegalStateException && "loading".equals(cause.getMessage()) ? "loading" : "storage";
        if (key == null || !List.of("invalid", "missing", "full", "limit", "owner", "conflict", "state", "reason", "text",
                "config", "query", "usage", "world", "unavailable", "claim", "loading").contains(key)) key = "storage";
        msg(sender, "errors." + key);
        if (key.equals("usage")) msg(sender, "help");
        if (key.equals("storage")) logger().log(Level.WARNING, "地标册操作未确认保存", cause);
    }

    static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    private final class LandmarksCommand extends LbrCommand {
        LandmarksCommand() {
            super("landmarks", "lbr.landmarks.use");
            description("浏览和投稿建筑地标册");
            usage("/landmarks [help|mine|submit <标题> | <简介>|edit <ID> <标题> | <简介>|withdraw <ID>]");
            playerOnly(true);
        }
        @Override public void execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player player)) return;
            try {
                String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
                switch (action) {
                    case "help" -> { if (args.length != 1) throw Landmark.problem("usage"); msg(player, "help"); }
                    case "list" -> { if (args.length > 1) throw Landmark.problem("usage"); gui.open(player, false, 0); }
                    case "mine" -> { if (args.length != 1) throw Landmark.problem("usage"); gui.open(player, true, 0); }
                    case "submit" -> { String[] text = LandmarkRequests.submission(args, 1); submit(player, text[0], text[1]); }
                    case "edit" -> {
                        if (args.length < 3) throw Landmark.problem("usage");
                        String[] text = LandmarkRequests.submission(args, 2);
                        edit(player, LandmarkRequests.id(args[1]), text[0], text[1]);
                    }
                    case "withdraw" -> {
                        if (args.length != 2) throw Landmark.problem("usage");
                        Landmark current = book().find(LandmarkRequests.id(args[1]));
                        withdraw(player, current.id(), current.revision());
                    }
                    default -> throw Landmark.problem("usage");
                }
            } catch (RuntimeException error) { report(player, error); }
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) return filter(List.of("help", "mine", "submit", "edit", "withdraw"), args[0]);
            if (ready() && sender instanceof Player player && args.length == 2
                    && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("withdraw")))
                return filter(entries(player, true).stream().map(entry -> Long.toString(entry.id())).toList(), args[1]);
            return List.of();
        }
    }
}
