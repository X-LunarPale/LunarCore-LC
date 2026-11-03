package com.lunarboreal.teleport;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.Set;

/** 安全落点工具：避免岩浆、虚空、窒息、火焰等。仅在主线程调用。 */
public final class SafeLocation {

    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.END_PORTAL, Material.NETHER_PORTAL, Material.END_GATEWAY,
            Material.POINTED_DRIPSTONE, Material.COBWEB);

    private SafeLocation() {}

    public static boolean isDangerous(Material m) {
        return DANGEROUS.contains(m);
    }

    /** (x,y,z) 为脚所在方块：脚下实心、脚与头可通过且不危险。 */
    public static boolean isSafe(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 1) return false;
        Block below = w.getBlockAt(x, y - 1, z);
        Material bt = below.getType();
        if (!(bt.isSolid() || bt == Material.WATER) || DANGEROUS.contains(bt)) return false;
        return passable(w.getBlockAt(x, y, z)) && passable(w.getBlockAt(x, y + 1, z));
    }

    private static boolean passable(Block b) {
        Material t = b.getType();
        if (DANGEROUS.contains(t)) return false;
        return b.isPassable();
    }

    /**
     * 从给定位置寻找安全落点：先原地，再同一列向上、向下，最后在水平半径内逐圈搜索。
     * @return 居中的安全位置（保留朝向），找不到返回 null
     */
    public static Location find(Location loc, int radius) {
        World w = loc.getWorld();
        if (w == null) return null;
        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;
        if (w.getEnvironment() == World.Environment.NETHER && loc.getBlockY() < 127) maxY = Math.min(maxY, 125);
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        int by = loc.getBlockY();
        if (by < minY) {
            // 虚空：取最高方块之上
            by = w.getHighestBlockYAt(bx, bz) + 1;
        }
        by = Math.max(minY, Math.min(maxY, by));
        if (isSafe(w, bx, by, bz)) return center(loc, bx, by, bz);
        for (int r = 0; r <= Math.max(0, radius); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = bx + dx, z = bz + dz;
                    for (int y = by; y <= maxY; y++) {
                        if (isSafe(w, x, y, z)) return center(loc, x, y, z);
                    }
                    for (int y = by - 1; y >= minY; y--) {
                        if (isSafe(w, x, y, z)) return center(loc, x, y, z);
                    }
                }
            }
        }
        return null;
    }

    private static Location center(Location src, int x, int y, int z) {
        Location l = new Location(src.getWorld(), x + 0.5, y, z + 0.5, src.getYaw(), src.getPitch());
        // 原位置本就安全且在同一方块内时保留精确坐标
        if (src.getBlockX() == x && src.getBlockY() == y && src.getBlockZ() == z) return src.clone();
        return l;
    }
}
