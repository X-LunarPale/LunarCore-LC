package com.lunarboreal.land;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 领地内存索引：区块坐标索引（world → chunkKey → lands），按名字、按主人索引。
 * 修改只在主线程进行；读取的容器为并发容器，异步线程也可安全读取。
 */
public final class LandManager {

    private final Map<String, Map<Long, List<Land>>> chunkIndex = new ConcurrentHashMap<>();
    private final Map<String, Land> byName = new ConcurrentHashMap<>();
    private final Map<UUID, List<Land>> byOwner = new ConcurrentHashMap<>();
    private final List<Land> all = new CopyOnWriteArrayList<>();

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public void clear() {
        chunkIndex.clear();
        byName.clear();
        byOwner.clear();
        all.clear();
    }

    public void add(Land land) {
        all.add(land);
        byName.put(land.name.toLowerCase(Locale.ROOT), land);
        byOwner.computeIfAbsent(land.owner, k -> new CopyOnWriteArrayList<>()).add(land);
        indexChunks(land);
    }

    public void remove(Land land) {
        all.remove(land);
        byName.remove(land.name.toLowerCase(Locale.ROOT), land);
        List<Land> owned = byOwner.get(land.owner);
        if (owned != null) {
            owned.remove(land);
            if (owned.isEmpty()) byOwner.remove(land.owner);
        }
        unindexChunks(land);
    }

    /** 修改范围前调用 {@link #beginResize}，修改后调用 {@link #endResize}。 */
    public void beginResize(Land land) {
        unindexChunks(land);
    }

    public void endResize(Land land) {
        indexChunks(land);
    }

    public void rename(Land land, String newName) {
        byName.remove(land.name.toLowerCase(Locale.ROOT), land);
        land.name = newName;
        byName.put(newName.toLowerCase(Locale.ROOT), land);
    }

    public void changeOwner(Land land, UUID newOwner, String newOwnerName) {
        List<Land> owned = byOwner.get(land.owner);
        if (owned != null) {
            owned.remove(land);
            if (owned.isEmpty()) byOwner.remove(land.owner);
        }
        land.owner = newOwner;
        land.ownerName = newOwnerName;
        land.members.remove(newOwner);
        byOwner.computeIfAbsent(newOwner, k -> new CopyOnWriteArrayList<>()).add(land);
    }

    private void indexChunks(Land land) {
        Map<Long, List<Land>> w = chunkIndex.computeIfAbsent(land.world, k -> new ConcurrentHashMap<>());
        for (int cx = land.minX >> 4; cx <= land.maxX >> 4; cx++)
            for (int cz = land.minZ >> 4; cz <= land.maxZ >> 4; cz++)
                w.computeIfAbsent(chunkKey(cx, cz), k -> new CopyOnWriteArrayList<>()).add(land);
    }

    private void unindexChunks(Land land) {
        Map<Long, List<Land>> w = chunkIndex.get(land.world);
        if (w == null) return;
        for (int cx = land.minX >> 4; cx <= land.maxX >> 4; cx++)
            for (int cz = land.minZ >> 4; cz <= land.maxZ >> 4; cz++) {
                long key = chunkKey(cx, cz);
                List<Land> list = w.get(key);
                if (list != null) {
                    list.remove(land);
                    if (list.isEmpty()) w.remove(key);
                }
            }
    }

    // ------------------------------------------------------------ 查询

    public Land at(String world, int x, int z) {
        Map<Long, List<Land>> w = chunkIndex.get(world);
        if (w == null) return null;
        List<Land> list = w.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (Land l : list) if (x >= l.minX && x <= l.maxX && z >= l.minZ && z <= l.maxZ) return l;
        return null;
    }

    public Land at(String world, int x, int y, int z) {
        Map<Long, List<Land>> w = chunkIndex.get(world);
        if (w == null) return null;
        List<Land> list = w.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (Land land : list) if (land.contains(world, x, y, z)) return land;
        return null;
    }

    public Land at(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return at(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Land at(Block b) {
        return at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    /** 与矩形相交的领地（排除 ignore）。 */
    public List<Land> overlapping(String world, int minX, int minZ, int maxX, int maxZ, Land ignore) {
        Map<Long, List<Land>> w = chunkIndex.get(world);
        if (w == null) return Collections.emptyList();
        List<Land> out = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                List<Land> list = w.get(chunkKey(cx, cz));
                if (list == null) continue;
                for (Land l : list)
                    if (l != ignore && !out.contains(l) && l.intersects(world, minX, minZ, maxX, maxZ)) out.add(l);
            }
        return out;
    }

    public List<Land> overlapping(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Land ignore) {
        List<Land> candidates = overlapping(world, minX, minZ, maxX, maxZ, ignore);
        candidates = new ArrayList<>(candidates);
        candidates.removeIf(land -> minY > land.maxY || maxY < land.minY);
        return candidates;
    }

    public Land byName(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Land byId(long id) {
        for (Land l : all) if (l.id == id) return l;
        return null;
    }

    public List<Land> ownedBy(UUID owner) {
        List<Land> l = byOwner.get(owner);
        return l == null ? Collections.emptyList() : new ArrayList<>(l);
    }

    public long totalArea(UUID owner) {
        long sum = 0;
        for (Land l : byOwner.getOrDefault(owner, Collections.emptyList())) sum += l.area();
        return sum;
    }

    public Collection<Land> all() {
        return all;
    }

    public int ownerCount() {
        return byOwner.size();
    }

    public Map<String, Integer> countByWorld() {
        Map<String, Integer> m = new HashMap<>();
        for (Land l : all) m.merge(l.world, 1, Integer::sum);
        return m;
    }
}
