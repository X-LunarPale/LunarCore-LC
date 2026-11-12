package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService;
import com.lunarboreal.api.service.LandService.Flag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 内存中的领地对象。仅在主线程修改；存储线程只读取快照。 */
public final class Land {

    public enum Role { MEMBER, MANAGER }

    public record Member(UUID uuid, String name, Role role) {}

    /** 数据库主键；-1 表示尚未写入数据库。 */
    volatile long id = -1;
    volatile String name;
    volatile UUID owner;
    volatile String ownerName;
    final String world;
    volatile int minX, minZ, maxX, maxZ;
    volatile int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
    final long createdAt;

    /** 传送点（同世界），null 表示未设置。 */
    volatile double[] tp; // x,y,z,yaw,pitch
    volatile String enterMessage;
    volatile String leaveMessage;
    /** 出售价格，null 表示未出售。 */
    volatile BigDecimal salePrice;
    /** 从服务器购买（创建 + 扩建）累计花费，用于退款计算。 */
    volatile BigDecimal paid = BigDecimal.ZERO;
    volatile boolean frozen;
    volatile long frozenSince;
    volatile long lastTax;

    final Map<Flag, Boolean> globalFlags = new EnumMap<>(Flag.class);
    final Map<Flag, Boolean> memberFlags = new EnumMap<>(Flag.class);
    final Map<UUID, Member> members = new LinkedHashMap<>();

    public Land(String name, UUID owner, String ownerName, String world, int minX, int minZ, int maxX, int maxZ, long createdAt) {
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxZ = Math.max(minZ, maxZ);
        this.createdAt = createdAt;
        this.lastTax = createdAt;
    }

    public long id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public String world() { return world; }
    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxZ() { return maxZ; }
    public long createdAt() { return createdAt; }
    public BigDecimal salePrice() { return salePrice; }
    public boolean frozen() { return frozen; }
    public Map<UUID, Member> members() { return members; }

    public long area() {
        return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    public int width() { return maxX - minX + 1; }

    public int length() { return maxZ - minZ + 1; }

    public boolean contains(String w, int x, int z) {
        return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean intersects(String w, int aMinX, int aMinZ, int aMaxX, int aMaxZ) {
        return world.equals(w) && aMinX <= maxX && aMaxX >= minX && aMinZ <= maxZ && aMaxZ >= minZ;
    }

    public boolean contains(String w, int x, int y, int z) {
        return contains(w, x, z) && y >= minY && y <= maxY;
    }

    public boolean intersects(String w, int aMinX, int aMinY, int aMinZ, int aMaxX, int aMaxY, int aMaxZ) {
        return intersects(w, aMinX, aMinZ, aMaxX, aMaxZ) && aMinY <= maxY && aMaxY >= minY;
    }

    public boolean isOwner(UUID u) { return owner.equals(u); }

    /** 主人或管理员。 */
    public boolean isManager(UUID u) {
        if (owner.equals(u)) return true;
        Member m = members.get(u);
        return m != null && m.role() == Role.MANAGER;
    }

    public boolean isMember(UUID u) {
        return owner.equals(u) || members.containsKey(u);
    }

    /** 访客（非成员）是否拥有该 flag / 领地环境开关。 */
    public boolean globalFlag(Flag f) {
        return globalFlags.getOrDefault(f, false);
    }

    public boolean memberFlag(Flag f) {
        if (!Settings.PLAYER_FLAGS.contains(f)) return globalFlag(f);
        return memberFlags.getOrDefault(f, true);
    }

    /** 判断某玩家在本领地是否拥有 flag（不含管理员绕过）。 */
    public boolean allows(UUID player, Flag f) {
        if (!Settings.PLAYER_FLAGS.contains(f)) return globalFlag(f);
        if (isManager(player)) return true;
        if (members.containsKey(player)) return memberFlag(f) || globalFlag(f);
        return globalFlag(f);
    }

    public Location teleportLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        double[] t = tp;
        if (t != null) return new Location(w, t[0], t[1], t[2], (float) t[3], (float) t[4]);
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        int y = Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, w.getHighestBlockYAt(cx, cz) + 1));
        y = Math.max(minY, Math.min(maxY, y));
        return new Location(w, cx + 0.5, y, cz + 0.5);
    }

    public LandService.LandInfo info() {
        return new LandService.LandInfo(id, name, owner, ownerName, world, minX, minZ, maxX, maxZ, minY, maxY, createdAt);
    }

    public String coords() {
        return minX + "," + minY + "," + minZ + " ~ " + maxX + "," + maxY + "," + maxZ;
    }
}
