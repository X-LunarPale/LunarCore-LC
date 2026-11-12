package com.lunarboreal.land;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed XYZ cuboid edges; each player receives only nearby particles and a bounded budget. */
public final class Visualizer {
    public static final Color SELECTION = Color.fromRGB(80, 200, 255);
    public static final Color OWN = Color.fromRGB(80, 255, 120);
    public static final Color OTHER = Color.fromRGB(255, 90, 90);
    private record Visual(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          long expires, Particle.DustOptions dust) {}
    private final Map<UUID, List<Visual>> visuals = new ConcurrentHashMap<>();
    private static final int RANGE = 48, MAX_POINTS = 600;

    public void show(Player p, String world, int minX, int minZ, int maxX, int maxZ, int seconds, Color color) {
        show(p, world, minX, p.getWorld().getMinHeight(), minZ, maxX, p.getWorld().getMaxHeight() - 1, maxZ, seconds, color);
    }

    void show(Player p, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int seconds, Color color) {
        visuals.remove(p.getUniqueId());
        add(p, world, minX, minY, minZ, maxX, maxY, maxZ, seconds, color);
    }

    void add(Player p, Land land, int seconds) {
        add(p, land.world, land.minX, land.minY, land.minZ, land.maxX, land.maxY, land.maxZ, seconds,
                land.isMember(p.getUniqueId()) ? OWN : OTHER);
    }

    private void add(Player p, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int seconds, Color color) {
        org.bukkit.World w = Bukkit.getWorld(world);
        if (w == null) return;
        int bottom = Math.max(w.getMinHeight(), Math.min(minY, maxY));
        int top = Math.min(w.getMaxHeight() - 1, Math.max(minY, maxY));
        if (bottom > top) return;
        List<Visual> list = visuals.computeIfAbsent(p.getUniqueId(), key -> new ArrayList<>());
        if (list.size() >= 2) list.removeFirst();
        list.add(new Visual(world, Math.min(minX, maxX), bottom, Math.min(minZ, maxZ), Math.max(minX, maxX), top,
                Math.max(minZ, maxZ), System.currentTimeMillis() + Math.max(1, Math.min(120, seconds)) * 1000L,
                new Particle.DustOptions(color, 1.2f)));
    }

    public void hide(UUID uuid) { visuals.remove(uuid); }
    public void clear() { visuals.clear(); }

    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, List<Visual>>> it = visuals.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            e.getValue().removeIf(v -> now >= v.expires);
            if (p == null || e.getValue().isEmpty()) { it.remove(); continue; }
            int[] budget = {MAX_POINTS};
            for (Visual v : e.getValue()) if (p.getWorld().getName().equals(v.world)) draw(p, v, budget);
        }
    }

    private void draw(Player p, Visual v, int[] budget) {
        double x1 = v.minX, x2 = (double) v.maxX + 1, y1 = v.minY, y2 = (double) v.maxY + 1;
        double z1 = v.minZ, z2 = (double) v.maxZ + 1;
        for (double y : new double[]{y1, y2}) {
            line(p, x1,y,z1, x2,y,z1, v, budget); line(p, x1,y,z2, x2,y,z2, v, budget);
            line(p, x1,y,z1, x1,y,z2, v, budget); line(p, x2,y,z1, x2,y,z2, v, budget);
        }
        for (double x : new double[]{x1,x2}) for (double z : new double[]{z1,z2})
            line(p,x,y1,z,x,y2,z,v,budget);
    }

    private void line(Player p, double ax, double ay, double az, double bx, double by, double bz, Visual v, int[] budget) {
        Location loc = p.getLocation();
        double[] a = {ax,ay,az}, b = {bx,by,bz}, center = {loc.getX(),loc.getY(),loc.getZ()};
        int axis = ax != bx ? 0 : ay != by ? 1 : 2;
        for (int i=0;i<3;i++) if (i != axis && Math.abs(a[i]-center[i]) > RANGE) return;
        double low = Math.max(a[axis], center[axis]-RANGE), high = Math.min(b[axis],center[axis]+RANGE);
        // Clip before sampling: legacy full-height or very large cuboids cannot cause unbounded loops.
        double step = Math.max(1, (high-low)/24);
        for (double t=low;t<=high && budget[0]>0;t+=step) {
            double[] point = a.clone(); point[axis]=t; budget[0]--;
            p.spawnParticle(Particle.DUST,point[0],point[1],point[2],1,0,0,0,0,v.dust);
        }
    }
}
