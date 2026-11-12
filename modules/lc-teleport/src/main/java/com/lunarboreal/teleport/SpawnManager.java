package com.lunarboreal.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** 出生点：保存在数据目录 spawn.yml（写入异步）。未设置时使用主世界出生点。 */
public final class SpawnManager {

    private final TeleportModule m;
    private volatile StoredLoc spawn;
    private volatile Location configuredSpawn;

    public SpawnManager(TeleportModule m) {
        this.m = m;
    }

    private File file() {
        return new File(m.dataFolder(), "spawn.yml");
    }

    public void load() {
        File f = file();
        spawn = f.exists() ? StoredLoc.read(YamlConfiguration.loadConfiguration(f).getConfigurationSection("spawn")) : null;
        refresh();
    }

    public StoredLoc stored() {
        return spawn;
    }

    /** Configured hub snapshot, also safe to read during asynchronous player configuration. */
    public Location configured() {
        Location current = configuredSpawn;
        return current == null ? null : current.clone();
    }

    void refresh() {
        StoredLoc current = spawn;
        configuredSpawn = current == null ? null : current.toLocation();
    }

    void unloaded(World world) {
        Location current = configuredSpawn;
        if (current != null && current.getWorld() == world) configuredSpawn = null;
    }

    /** 当前出生点（主线程）。 */
    public Location get() {
        if (spawn != null) {
            Location l = spawn.toLocation();
            if (l != null) return l;
        }
        if (Bukkit.getWorlds().isEmpty()) return null;
        World w = Bukkit.getWorlds().get(0);
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    public void set(Location loc) {
        StoredLoc s = StoredLoc.of(loc);
        spawn = s;
        configuredSpawn = loc.clone();
        YamlConfiguration y = new YamlConfiguration();
        s.write(y.createSection("spawn"));
        String data = y.saveToString();
        File f = file();
        m.runAsync(() -> {
            try {
                f.getParentFile().mkdirs();
                java.nio.file.Files.writeString(f.toPath(), data, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                m.logger().warning("保存 spawn.yml 失败: " + e.getMessage());
            }
        });
    }
}
