package com.lunarboreal.teleport;

import com.lunarboreal.api.service.LandService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** 随机传送：异步加载区块（Paper World#getChunkAtAsync），逐次尝试直到找到合适落点。 */
public final class RtpManager {

    public record Settings(int centerX, int centerZ, int minRadius, int maxRadius) {}

    private final TeleportModule m;

    public RtpManager(TeleportModule m) {
        this.m = m;
    }

    public List<String> worlds() {
        ConfigurationSection s = m.config().getConfigurationSection("rtp.worlds");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    public Settings settings(World w) {
        ConfigurationSection s = m.config().getConfigurationSection("rtp.worlds." + w.getName());
        if (s == null) return null;
        int min = Math.max(0, s.getInt("min-radius", 0));
        int max = Math.max(min + 1, s.getInt("max-radius", 5000));
        return new Settings(s.getInt("center-x", 0), s.getInt("center-z", 0), min, max);
    }

    /** 异步寻找随机落点，找不到时以 null 完成。 */
    public CompletableFuture<Location> find(World w) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        Settings st = settings(w);
        if (st == null) {
            result.complete(null);
            return result;
        }
        attempt(w, st, 0, Math.max(1, m.config().getInt("rtp.max-attempts", 20)), result);
        return result;
    }

    private void attempt(World w, Settings st, int i, int max, CompletableFuture<Location> result) {
        if (i >= max || !m.isEnabled()) {
            result.complete(null);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x, z;
        // 方形环：在 [min,max] 范围内随机，保证至少一个轴的偏移 >= min
        int dx = rnd.nextInt(-st.maxRadius(), st.maxRadius() + 1);
        int dz = rnd.nextInt(-st.maxRadius(), st.maxRadius() + 1);
        if (Math.abs(dx) < st.minRadius() && Math.abs(dz) < st.minRadius()) {
            int span = st.maxRadius() - st.minRadius() + 1;
            int edge = st.minRadius() + rnd.nextInt(span);
            if (rnd.nextBoolean()) dx = rnd.nextBoolean() ? edge : -edge;
            else dz = rnd.nextBoolean() ? edge : -edge;
        }
        x = st.centerX() + dx;
        z = st.centerZ() + dz;
        int fx = x, fz = z;
        loadChunk(w, x >> 4, z >> 4).whenComplete((chunk, err) -> {
            Runnable next = () -> {
                Location loc = err == null ? check(w, fx, fz) : null;
                if (loc != null) result.complete(loc);
                else attempt(w, st, i + 1, max, result);
            };
            if (Bukkit.isPrimaryThread()) next.run();
            else if (m.isEnabled()) m.runTask(next);
            else result.complete(null);
        });
    }

    private CompletableFuture<Chunk> loadChunk(World w, int cx, int cz) {
        if (m.api().isPaper()) {
            try {
                return w.getChunkAtAsync(cx, cz, true);
            } catch (NoSuchMethodError ignored) {
                // 回退到同步加载
            }
        }
        return CompletableFuture.completedFuture(w.getChunkAt(cx, cz));
    }

    private Location check(World w, int x, int z) {
        int y;
        if (w.getEnvironment() == World.Environment.NETHER) {
            y = -1;
            for (int yy = 32; yy < 120; yy++) {
                if (SafeLocation.isSafe(w, x, yy, z) && w.getBlockAt(x, yy - 1, z).getType() != Material.WATER) {
                    y = yy;
                    break;
                }
            }
            if (y < 0) return null;
        } else {
            int top = w.getHighestBlockYAt(x, z);
            if (top <= w.getMinHeight()) return null; // 虚空（末地外岛之间）
            Block b = w.getBlockAt(x, top, z);
            if (b.isLiquid() || SafeLocation.isDangerous(b.getType())) return null;
            y = top + 1;
            if (!SafeLocation.isSafe(w, x, y, z)) return null;
        }
        if (avoidedBiome(w, x, y, z)) return null;
        Location loc = new Location(w, x + 0.5, y, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360f - 180f, 0f);
        if (m.config().getBoolean("rtp.avoid-lands", true)) {
            Optional<LandService> land = m.land();
            if (land.isPresent()) {
                try {
                    if (land.get().landAt(loc).isPresent()) return null;
                } catch (Throwable ignored) {
                }
            }
        }
        return loc;
    }

    @SuppressWarnings("deprecation")
    private boolean avoidedBiome(World w, int x, int y, int z) {
        List<String> avoid = m.config().getStringList("rtp.avoid-biomes");
        if (avoid.isEmpty()) return false;
        try {
            Biome biome = w.getBiome(x, y, z);
            String key = biome.getKey().getKey().toLowerCase(Locale.ROOT);
            for (String a : avoid) {
                if (!a.isBlank() && key.contains(a.toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
