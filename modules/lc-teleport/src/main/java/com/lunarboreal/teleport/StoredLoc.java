package com.lunarboreal.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 与世界对象解耦的位置（世界可能未加载）。 */
public record StoredLoc(String world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLoc of(Location l) {
        return new StoredLoc(l.getWorld() == null ? "world" : l.getWorld().getName(),
                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** 读取 world,x,y,z,yaw,pitch 列。 */
    public static StoredLoc read(ResultSet rs) throws SQLException {
        return new StoredLoc(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"));
    }

    public static StoredLoc read(ConfigurationSection s) {
        if (s == null || !s.contains("world")) return null;
        return new StoredLoc(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public void write(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
    }

    /** 世界未加载返回 null。 */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public String display() {
        return world + " " + (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", world);
        m.put("x", Math.round(x * 10) / 10.0);
        m.put("y", Math.round(y * 10) / 10.0);
        m.put("z", Math.round(z * 10) / 10.0);
        return m;
    }
}
