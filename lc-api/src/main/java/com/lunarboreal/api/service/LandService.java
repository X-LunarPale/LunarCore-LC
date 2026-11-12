package com.lunarboreal.api.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 领地服务（由 land 模块提供）。供其它模块查询某位置是否在领地内、玩家是否有权限。 */
public interface LandService {

    /** 领地权限标志。 */
    enum Flag { BUILD, BREAK, INTERACT, CONTAINER, PVP, MOB_SPAWN, EXPLOSION, FIRE_SPREAD, ENTER, TELEPORT, USE_DOOR, ANIMAL_DAMAGE }

    record LandInfo(long id, String name, UUID owner, String ownerName, String world,
                    int minX, int minZ, int maxX, int maxZ, int minY, int maxY, long createdAt) {
        /** Legacy callers create a full-height claim. */
        public LandInfo(long id, String name, UUID owner, String ownerName, String world,
                        int minX, int minZ, int maxX, int maxZ, long createdAt) {
            this(id, name, owner, ownerName, world, minX, minZ, maxX, maxZ,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, createdAt);
        }
        public long area() { return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1); }
        /** Horizontal projection only; protection checks must use XYZ containment. */
        public boolean contains(String w, int x, int z) {
            return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
        public boolean contains(String w, int x, int y, int z) {
            return contains(w, x, z) && y >= minY && y <= maxY;
        }
    }

    Optional<LandInfo> landAt(Location location);

    /** 玩家在该位置是否拥有某权限（不在领地内返回 true；管理员绕过返回 true）。 */
    boolean can(Player player, Location location, Flag flag);

    List<LandInfo> landsOf(UUID owner);
}
