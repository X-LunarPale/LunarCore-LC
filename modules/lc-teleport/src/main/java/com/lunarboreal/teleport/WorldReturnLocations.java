package com.lunarboreal.teleport;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Per-world return points saved with the player data. */
public final class WorldReturnLocations {
    private static final NamespacedKey RETURNS = key("teleport_world_returns");
    private static final NamespacedKey X = key("x");
    private static final NamespacedKey Y = key("y");
    private static final NamespacedKey Z = key("z");
    private static final NamespacedKey YAW = key("yaw");
    private static final NamespacedKey PITCH = key("pitch");

    private WorldReturnLocations() {}

    public static void remember(Player player, Location location) {
        if (player == null || !valid(location)) return;
        World world = location.getWorld();
        if (world.getKey() == null) return;
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data == null) return;
        PersistentDataContainer worlds = data.get(RETURNS, PersistentDataType.TAG_CONTAINER);
        if (worlds == null) worlds = data.getAdapterContext().newPersistentDataContainer();
        PersistentDataContainer point = data.getAdapterContext().newPersistentDataContainer();
        point.set(X, PersistentDataType.DOUBLE, location.getX());
        point.set(Y, PersistentDataType.DOUBLE, location.getY());
        point.set(Z, PersistentDataType.DOUBLE, location.getZ());
        point.set(YAW, PersistentDataType.FLOAT, location.getYaw());
        point.set(PITCH, PersistentDataType.FLOAT, location.getPitch());
        worlds.set(world.getKey(), PersistentDataType.TAG_CONTAINER, point);
        data.set(RETURNS, PersistentDataType.TAG_CONTAINER, worlds);
    }

    public static Location get(Player player, World world) {
        if (player == null || world == null || world.getKey() == null) return null;
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data == null) return null;
        PersistentDataContainer worlds = data.get(RETURNS, PersistentDataType.TAG_CONTAINER);
        if (worlds == null) return null;
        PersistentDataContainer point = worlds.get(world.getKey(), PersistentDataType.TAG_CONTAINER);
        if (point == null) return null;
        Double x = point.get(X, PersistentDataType.DOUBLE);
        Double y = point.get(Y, PersistentDataType.DOUBLE);
        Double z = point.get(Z, PersistentDataType.DOUBLE);
        Float yaw = point.get(YAW, PersistentDataType.FLOAT);
        Float pitch = point.get(PITCH, PersistentDataType.FLOAT);
        if (x == null || y == null || z == null || yaw == null || pitch == null) return null;
        Location location = new Location(world, x, y, z, yaw, pitch);
        return valid(location) ? location : null;
    }

    private static boolean valid(Location location) {
        if (location == null || location.getWorld() == null
                || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ()) || !Float.isFinite(location.getYaw())
                || !Float.isFinite(location.getPitch())) return false;
        World world = location.getWorld();
        return location.getY() >= world.getMinHeight() && location.getY() < world.getMaxHeight()
                && world.getWorldBorder().isInside(location);
    }

    private static NamespacedKey key(String name) { return new NamespacedKey("lunarcore", name); }
}
