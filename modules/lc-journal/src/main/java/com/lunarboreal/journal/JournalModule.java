package com.lunarboreal.journal;

import com.lunarboreal.api.module.LcModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class JournalModule extends LcModule implements Listener {

    private volatile JournalSettings settings;
    private JournalRepository repository;
    private JournalRecorder recorder;
    private JournalPendingStore pendingStore;
    private ExecutorService io;
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final Object snapshotLock = new Object();
    private final Map<UUID, Set<String>> seen = new HashMap<>();
    private volatile boolean active;
    private volatile boolean ready;
    private volatile boolean abortWrites;
    private long nextSample;
    private long lastWarning;

    public JournalSettings settings() { return settings; }
    public JournalRepository repository() { return repository; }
    public boolean ready() { return active && ready; }
    public int pendingCount() { return recorder.size(); }
    public boolean active() { return active; }

    @Override
    public void onEnable() {
        saveResource("config.yml", false);
        try {
            settings = readSettings();
            repository = new JournalRepository(api().database());
            recorder = new JournalRecorder(repository::save, JournalPendingStore.CAPACITY);
            pendingStore = new JournalPendingStore(dataFolder().toPath().resolve("pending.dat"));
            for (JournalEntry entry : pendingStore.load()) recorder.enqueue(entry);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load journal settings or retry snapshot", error);
        }
        io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
            Thread thread = new Thread(task, "lc-journal-io");
            thread.setDaemon(true);
            return thread;
        });
        active = true;
        defaultPermissions("lc.journal.use");
        registerCommand(new JournalCommand(this));
        registerListener(this);
        registerListener(new JournalGui.Listener());
        new JournalWeb(this).register();
        requestFlush();
        runTaskTimer(this::sampleBiomes, 20L, 20L);
        runTaskTimer(this::requestFlush, 100L, 100L);
    }

    @Override
    public synchronized void onDisable() {
        active = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof JournalGui gui
                    && gui.module() == this) player.closeInventory();
        }
        seen.clear();
        if (io == null) return;
        // Persist the bounded queue before waiting, so a stalled database cannot lose it.
        snapshotPending();
        try {
            io.execute(this::flush);
        } catch (RejectedExecutionException ignored) {
            // The saved snapshot is sufficient for replay.
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                abortWrites = true;
                io.shutdownNow();
                logger().warning("Journal shutdown timed out; pending discoveries will be replayed on next enable.");
            }
        } catch (InterruptedException error) {
            abortWrites = true;
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void onReload() {
        try {
            settings = readSettings();
        } catch (Exception error) {
            logger().log(Level.WARNING, "Invalid journal configuration; retaining active settings", error);
        }
    }

    private JournalSettings readSettings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(dataFolder().toPath().resolve("config.yml").toFile());
        return JournalSettings.fromYaml(yaml);
    }

    public synchronized void updateSettings(Map<String, Object> body) throws IOException {
        if (!active) throw new IllegalStateException("Journal module is not active");
        JournalSettings next = settings.updated(body);
        Path target = dataFolder().toPath().resolve("config.yml");
        Path temporary = Files.createTempFile(dataFolder().toPath(), "journal-config-", ".tmp");
        try {
            Files.writeString(temporary, next.toYaml().saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            settings = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean eligible(Player player) {
        JournalSettings current = settings;
        return active && current.enabled() && !player.isDead()
                && current.worlds().contains(player.getWorld().getName())
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && player.hasPermission("lc.journal.use");
    }

    private void sampleBiomes() {
        long now = System.nanoTime();
        if (!active || now < nextSample) return;
        nextSample = now + settings.sampleSeconds() * 1_000_000_000L;
        if (!settings.recordBiomes()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!eligible(player)) continue;
            var biome = player.getLocation().getBlock().getBiome();
            String key = biome.getKey().toString();
            record(player, JournalEntry.Category.BIOMES, key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        if (!settings.recordAdvancements() || !eligible(event.getPlayer()) || advancement.getDisplay() == null) return;
        var key = advancement.getKey();
        if (JournalEntry.vanillaAdvancement(key.getNamespace(), key.getKey()))
            record(event.getPlayer(), JournalEntry.Category.ADVANCEMENTS, key.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!settings.recordFishing() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !eligible(event.getPlayer()) || !(event.getCaught() instanceof Item item)
                || item.getItemStack().isEmpty()) return;
        record(event.getPlayer(), JournalEntry.Category.FISHING, item.getItemStack().getType().getKey().toString());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { seen.remove(event.getPlayer().getUniqueId()); }

    private void record(Player player, JournalEntry.Category category, String key) {
        String sessionKey = category.id() + "/" + key;
        Set<String> session = seen.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        if (session.contains(sessionKey)) return;
        JournalEntry entry = new JournalEntry(player.getUniqueId(), player.getName(), category, key,
                JournalEntry.readableKey(key), player.getWorld().getName(), System.currentTimeMillis());
        if (recorder.enqueue(entry)) session.add(sessionKey);
        else if (System.currentTimeMillis() - lastWarning > 30_000L) {
            lastWarning = System.currentTimeMillis();
            logger().warning("Journal retry queue is full; database recovery is required to accept more discoveries.");
        }
    }

    private void requestFlush() {
        if (!active || !flushQueued.compareAndSet(false, true)) return;
        if (!executeIO(() -> {
            try { flush(); }
            finally { flushQueued.set(false); }
        })) flushQueued.set(false);
    }

    private void flush() {
        if (abortWrites) return;
        try {
            savePending();
            if (!ready) {
                repository.init();
                ready = true;
            }
            recorder.drain(saved -> {
                if (saved.inserted() && active && settings.notifyDiscoveries()) {
                    onMain(() -> {
                        Player player = Bukkit.getPlayer(saved.entry().uuid());
                        if (player != null && eligible(player))
                            msg(player, "discovered", "entry", saved.entry().label());
                    });
                }
            }, () -> !abortWrites && !Thread.currentThread().isInterrupted());
        } catch (Exception error) {
            logger().log(Level.WARNING, "Journal write failed; preserving queued discoveries for retry", error);
        } finally {
            if (!abortWrites) snapshotPending();
        }
    }

    private void snapshotPending() {
        try {
            savePending();
        } catch (IOException error) {
            logger().log(Level.SEVERE, "Cannot save journal retry snapshot", error);
        }
    }

    private void savePending() throws IOException {
        synchronized (snapshotLock) { pendingStore.save(recorder.snapshot()); }
    }

    boolean executeIO(Runnable task) {
        if (!active) return false;
        try {
            io.execute(() -> { if (active) task.run(); });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    void onMain(Runnable task) {
        if (!active) return;
        runTask(() -> { if (active) task.run(); });
    }
}
