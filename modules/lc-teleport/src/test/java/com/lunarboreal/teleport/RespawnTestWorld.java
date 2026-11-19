package com.lunarboreal.teleport;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Voxel-backed world fixture; block reads use actual coordinates and collision bounds. */
final class RespawnTestWorld {
    private record Position(int x, int y, int z) {}
    private static final EnumSet<Material> SOLID = EnumSet.of(Material.STONE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.OAK_FENCE, Material.STONE_SLAB, Material.OAK_LEAVES);
    private static final EnumSet<Material> PASSABLE = EnumSet.of(Material.AIR, Material.WATER, Material.LAVA,
            Material.FIRE, Material.SOUL_FIRE, Material.COBWEB, Material.POWDER_SNOW);

    final World world;
    final List<HeightMap> heightMaps = new ArrayList<>();
    private final Map<Position, Material> blocks = new HashMap<>();
    private final Map<Position, BoundingBox> bounds = new HashMap<>();
    private final int minHeight;
    private final int maxHeight;
    private Integer groundY = 79;
    private Location spawn;
    private double borderX;
    private double borderZ;
    private double borderSize = 1_000_000;
    int writes;

    RespawnTestWorld(String name) {
        this(name, World.Environment.NORMAL, -64, 320);
    }

    RespawnTestWorld(String name, World.Environment environment, int minHeight, int maxHeight) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        UUID uid = UUID.randomUUID();
        WorldBorder border = proxy(WorldBorder.class, (method, args) -> switch (method) {
            case "getCenter" -> new Location(world(), borderX, 0, borderZ);
            case "getSize" -> borderSize;
            case "isInside" -> {
                Location point = (Location) args[0];
                yield point.getX() >= borderX - borderSize / 2 && point.getX() <= borderX + borderSize / 2
                        && point.getZ() >= borderZ - borderSize / 2 && point.getZ() <= borderZ + borderSize / 2;
            }
            default -> null;
        });
        world = proxy(World.class, (method, args) -> switch (method) {
            case "getUID" -> uid;
            case "getName" -> name;
            case "getEnvironment" -> environment;
            case "getMinHeight" -> minHeight;
            case "getMaxHeight" -> maxHeight;
            case "getWorldBorder" -> border;
            case "getChunkAtAsync" -> java.util.concurrent.CompletableFuture.completedFuture(null);
            case "getSpawnLocation" -> spawn.clone();
            case "getBlockAt" -> args[0] instanceof Location location
                    ? block(location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    : block((int) args[0], (int) args[1], (int) args[2]);
            case "getHighestBlockYAt", "getHighestBlockAt" -> {
                int x = args[0] instanceof Location location ? location.getBlockX() : (int) args[0];
                int z = args[0] instanceof Location location ? location.getBlockZ() : (int) args[1];
                HeightMap map = args[args.length - 1] instanceof HeightMap value ? value : null;
                heightMaps.add(map);
                int y = highest(x, z, map);
                yield method.equals("getHighestBlockYAt") ? y : block(x, y, z);
            }
            default -> null;
        });
        spawn = new Location(world, 0.5, 80, 0.5);
    }

    private World world() {
        return world;
    }

    RespawnTestWorld ground(Integer y) {
        groundY = y;
        return this;
    }

    RespawnTestWorld spawn(double x, double y, double z) {
        spawn = new Location(world, x, y, z);
        return this;
    }

    RespawnTestWorld set(int x, int y, int z, Material material) {
        blocks.put(new Position(x, y, z), material);
        return this;
    }

    RespawnTestWorld bounds(int x, int y, int z, BoundingBox box) {
        bounds.put(new Position(x, y, z), box);
        return this;
    }

    RespawnTestWorld border(double x, double z, double size) {
        borderX = x;
        borderZ = z;
        borderSize = size;
        return this;
    }

    private Material material(int x, int y, int z) {
        return blocks.getOrDefault(new Position(x, y, z),
                groundY != null && y <= groundY ? Material.STONE : Material.AIR);
    }

    private int highest(int x, int z, HeightMap map) {
        for (int y = maxHeight - 1; y >= minHeight; y--) {
            Material type = material(x, y, z);
            if (type == Material.AIR || map == HeightMap.MOTION_BLOCKING_NO_LEAVES && type == Material.OAK_LEAVES) continue;
            return y;
        }
        return minHeight;
    }

    private Block block(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) throw new AssertionError("Block read outside world height: " + y);
        Material type = material(x, y, z);
        BoundingBox box = bounds.get(new Position(x, y, z));
        if (box == null) {
            box = switch (type) {
                case AIR, WATER, LAVA, FIRE, SOUL_FIRE, COBWEB, POWDER_SNOW -> new BoundingBox(x, y, z, x, y, z);
                case OAK_FENCE -> new BoundingBox(x + .375, y, z + .375, x + .625, y + 1.5, z + .625);
                case STONE_SLAB -> new BoundingBox(x, y, z, x + 1, y + .5, z + 1);
                default -> new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
            };
        }
        BoundingBox collision = box;
        return proxy(Block.class, (method, args) -> {
            if (method.startsWith("set") || method.equals("breakNaturally")) {
                writes++;
                throw new AssertionError("Respawn search must not modify blocks");
            }
            return switch (method) {
                case "getWorld" -> world;
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getType" -> type;
                case "getLocation" -> new Location(world, x, y, z);
                case "getBoundingBox" -> collision.clone();
                case "isPassable" -> PASSABLE.contains(type);
                case "isLiquid" -> type == Material.WATER || type == Material.LAVA;
                case "isEmpty" -> type == Material.AIR;
                default -> null;
            };
        });
    }

    static AutoCloseable materials() throws ReflectiveOperationException {
        Field field = Material.class.getDeclaredField("blockType");
        field.setAccessible(true);
        Map<Material, Object> previous = new EnumMap<>(Material.class);
        for (Material material : EnumSet.of(Material.AIR, Material.STONE, Material.WATER, Material.LAVA,
                Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.OAK_FENCE,
                Material.STONE_SLAB, Material.OAK_LEAVES, Material.COBWEB, Material.POWDER_SNOW)) {
            previous.put(material, field.get(material));
            field.set(material, (Supplier<BlockType>) () -> proxy(BlockType.class, (method, args) -> switch (method) {
                case "isSolid", "hasCollision" -> SOLID.contains(material);
                case "isAir" -> material == Material.AIR;
                default -> null;
            }));
        }
        return () -> {
            for (var entry : previous.entrySet()) field.set(entry.getKey(), entry.getValue());
        };
    }

    @FunctionalInterface
    private interface Answer { Object answer(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object result = answer.answer(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive()) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            return 0;
        });
    }
}
