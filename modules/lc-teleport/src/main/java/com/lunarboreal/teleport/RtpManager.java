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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** 随机传送：异步加载区块（Paper World#getChunkAtAsync），逐次尝试直到找到合适落点。 */
public final class RtpManager {

    public static final int MAX_RADIUS = 30_000_000;

    public record Settings(double centerX, double centerZ, int minRadius, int maxRadius) {}

    private final TeleportModule m;

    public RtpManager(TeleportModule m) {
        this.m = m;
    }

    public List<String> worlds() {
        return Bukkit.getWorlds().stream()
                .filter(w -> m.config().getBoolean("rtp.worlds." + w.getName() + ".enabled", true))
                .map(World::getName).toList();
    }

    public int defaultRadius() {
        return Math.max(1, Math.min(MAX_RADIUS, m.config().getInt("rtp.default-radius", 1000)));
    }

    public Settings settings(World w) {
        ConfigurationSection s = m.config().getConfigurationSection("rtp.worlds." + w.getName());
        if (s == null) return new Settings(0, 0, 0, defaultRadius());
        int min = Math.max(0, s.getInt("min-radius", 0));
        min = Math.min(MAX_RADIUS - 1, min);
        int max = Math.min(MAX_RADIUS, Math.max(min + 1, s.getInt("max-radius", defaultRadius())));
        return new Settings(s.getInt("center-x", 0), s.getInt("center-z", 0), min, max);
    }

    /** 异步寻找随机落点，找不到时以 null 完成。 */
    public CompletableFuture<Location> find(World w) {
        Settings st = settings(w);
        return find(w, st, false);
    }

    /** 普通 /rtp 默认以玩家坐标为圆心；可按世界配置固定中心与半径。 */
    public CompletableFuture<Location> find(World w, Location origin) {
        Settings st = settings(w);
        if (m.config().getBoolean("rtp.worlds." + w.getName() + ".center-on-player", true))
            st = new Settings(origin.getX(), origin.getZ(), st.minRadius(), st.maxRadius());
        return find(w, st, true);
    }

    public CompletableFuture<Location> findCircle(World w, int centerX, int centerZ, int radius) {
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid radius");
        return find(w, new Settings(centerX, centerZ, 0, radius), true);
    }

    private CompletableFuture<Location> find(World w, Settings st, boolean circle) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        if (st == null) {
            result.complete(null);
            return result;
        }
        attempt(w, st, circle, 0, Math.max(1, m.config().getInt("rtp.max-attempts", 20)), result);
        return result;
    }

    static int[] circleOffset(RandomGenerator random, int radius) {
        return circleOffset(random, radius, 0, 0);
    }

    private static int[] circleOffset(RandomGenerator random, int radius, double fractionX, double fractionZ) {
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid radius");
        int dx, dz;
        double distanceSquared;
        do {
            dx = random.nextInt(-radius, radius + 1);
            dz = random.nextInt(-radius, radius + 1);
            // The landing position is the block center, including at the circle edge.
            double x = dx + 0.5 - fractionX, z = dz + 0.5 - fractionZ;
            distanceSquared = x * x + z * z;
        } while (distanceSquared > (double) radius * radius);
        return new int[]{dx, dz};
    }

    private void attempt(World w, Settings st, boolean circle, int i, int max,
                         CompletableFuture<Location> result) {
        if (i >= max || !m.isEnabled()) {
            result.complete(null);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x, z;
        int dx, dz;
        if (circle) {
            double fractionX = st.centerX() - Math.floor(st.centerX());
            double fractionZ = st.centerZ() - Math.floor(st.centerZ());
            int[] offset = fractionX == 0 && fractionZ == 0
                    ? circleOffset(rnd, st.maxRadius())
                    : circleOffset(rnd, st.maxRadius(), fractionX, fractionZ);
            dx = offset[0];
            dz = offset[1];
        } else {
            dx = rnd.nextInt(-st.maxRadius(), st.maxRadius() + 1);
            dz = rnd.nextInt(-st.maxRadius(), st.maxRadius() + 1);
            if (Math.abs(dx) < st.minRadius() && Math.abs(dz) < st.minRadius()) {
                int span = st.maxRadius() - st.minRadius() + 1;
                int edge = st.minRadius() + rnd.nextInt(span);
                if (rnd.nextBoolean()) dx = rnd.nextBoolean() ? edge : -edge;
                else dz = rnd.nextBoolean() ? edge : -edge;
            }
        }
        x = (int) Math.floor(st.centerX()) + dx;
        z = (int) Math.floor(st.centerZ()) + dz;
        double offsetX = x + .5 - st.centerX(), offsetZ = z + .5 - st.centerZ();
        double distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
        if (circle && distanceSquared < (double) st.minRadius() * st.minRadius()) {
            attempt(w, st, true, i + 1, max, result);
            return;
        }
        if (circle && !w.getWorldBorder().isInside(new Location(w, x + 0.5, w.getMinHeight(), z + 0.5))) {
            attempt(w, st, true, i + 1, max, result);
            return;
        }
        int fx = x, fz = z;
        loadChunk(w, x >> 4, z >> 4).whenComplete((chunk, err) -> {
            Runnable next = () -> {
                if (!m.isEnabled()) {
                    result.complete(null);
                    return;
                }
                Location loc = err == null ? check(w, fx, fz) : null;
                if (loc != null) result.complete(loc);
                else attempt(w, st, circle, i + 1, max, result);
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
