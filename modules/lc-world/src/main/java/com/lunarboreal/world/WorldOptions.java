package com.lunarboreal.world;

import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldType;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Validated, immutable persisted world settings. */
record WorldOptions(String name, World.Environment environment, WorldType type, long seed,
                    boolean loadOnStartup, boolean pvp, Difficulty difficulty, long time,
                    boolean daylightCycle, String weather, boolean autoSave, boolean allowCrossDimension,
                    Integer spawnX, Integer spawnY, Integer spawnZ, Map<String, String> gamerules) {
    static boolean allowsCrossDimension(WorldOptions options, World.Environment from, World.Environment to) {
        return options == null || options.allowCrossDimension || from == to;
    }
    static String validateName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")
                || value.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])"))
            throw new IllegalArgumentException("世界名只允许1~48位字母、数字、下划线和横线，且不能是设备名");
        return value;
    }
    static Path safePath(Path root, String name) throws IOException {
        validateName(name);
        Path base = root.toRealPath();
        Path target = base.resolve(name).normalize();
        if (!target.getParent().equals(base) || Files.isSymbolicLink(target))
            throw new IllegalArgumentException("世界路径不安全");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.toRealPath().getParent().equals(base))
            throw new IllegalArgumentException("世界路径不可越过世界目录");
        return target;
    }
    /** Accept legacy name roots or the exact 26.3 dimension path under a direct save root. */
    static Path loadedSaveRoot(Path container, String name, Path folder, String namespace, String dimension) throws IOException {
        Path expected = safePath(container,name);
        Path base = container.toRealPath();
        Path actual = folder.toRealPath();
        if (!namespace.matches("[a-z0-9_.-]+") || !dimension.matches("[a-z0-9_.-]+"))
            throw new IllegalArgumentException("不支持的维度标识");
        if (Files.exists(expected) && actual.equals(expected.toRealPath()) && Files.isRegularFile(actual.resolve("level.dat"))) return actual;
        // Resolve the shape before canonicalization too: junctions/symlinks may not redirect dimensions.
        try (var roots = Files.list(base)) {
            for (Path save : roots.filter(Files::isDirectory).toList()) {
                if (Files.isSymbolicLink(save) || !save.toRealPath().getParent().equals(base)
                        || !Files.isRegularFile(save.resolve("level.dat"))) continue;
                Path dim = save.resolve("dimensions").resolve(namespace).resolve(dimension);
                if (!folder.toAbsolutePath().normalize().equals(dim.toAbsolutePath().normalize())) continue;
                Path cursor = save;
                boolean redirected = false;
                for (Path part : save.relativize(dim)) {
                    cursor = cursor.resolve(part);
                    if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize())) { redirected = true; break; }
                }
                if (!redirected && actual.equals(dim.toRealPath())) return save.toRealPath();
            }
        }
        throw new IllegalArgumentException("世界目录不匹配已加载维度：仅允许存档根或dimensions/" + namespace + "/" + dimension);
    }

    static WorldOptions parse(String name, Map<String, Object> m, WorldOptions old) {
        validateName(name);
        String env = text(m, "environment", old == null ? "NORMAL" : old.environment.name());
        World.Environment environment = World.Environment.valueOf(env.toUpperCase(Locale.ROOT));
        if (environment == World.Environment.CUSTOM) throw new IllegalArgumentException("不支持CUSTOM环境");
        WorldType type = WorldType.valueOf(text(m, "type", old == null ? "NORMAL" : old.type.name()).toUpperCase(Locale.ROOT));
        long seed = number(m, "seed", old == null ? 0 : old.seed);
        long time = number(m, "time", old == null ? 1000 : old.time);
        if (time < 0 || time > 23999) throw new IllegalArgumentException("时间范围为0~23999");
        String weather = text(m, "weather", old == null ? "CLEAR" : old.weather).toUpperCase(Locale.ROOT);
        if (!Set.of("CLEAR", "RAIN", "THUNDER").contains(weather)) throw new IllegalArgumentException("天气为CLEAR/RAIN/THUNDER");
        Map<String, String> rules = new LinkedHashMap<>(old == null ? Map.of() : old.gamerules);
        if (m.containsKey("gamerules")) {
            rules.clear();
            Object value = m.get("gamerules");
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("gamerules必须为对象");
            map.forEach((k,v) -> rules.put(String.valueOf(k), String.valueOf(v)));
        }
        return new WorldOptions(name, environment, type, seed,
                bool(m,"loadOnStartup",old == null || old.loadOnStartup), bool(m,"pvp",old == null || old.pvp),
                Difficulty.valueOf(text(m,"difficulty",old == null ? "NORMAL" : old.difficulty.name()).toUpperCase(Locale.ROOT)), time,
                bool(m,"daylightCycle",old == null || old.daylightCycle), weather, bool(m,"autoSave",old == null || old.autoSave),
                bool(m,"allowCrossDimension",old == null || old.allowCrossDimension),
                coord(m,"spawnX",old == null ? null : old.spawnX), coord(m,"spawnY",old == null ? null : old.spawnY),
                coord(m,"spawnZ",old == null ? null : old.spawnZ), Map.copyOf(rules));
    }
    private static Integer coord(Map<String,Object> m, String k, Integer fallback) {
        if (!m.containsKey(k) || m.get(k) == null || String.valueOf(m.get(k)).isBlank()) return fallback;
        long n = number(m,k,0);
        if (n < -30000000 || n > 30000000) throw new IllegalArgumentException("出生点越界");
        return (int)n;
    }
    static long number(Map<String,Object> m, String k, long fallback) {
        if (!m.containsKey(k)) return fallback;
        try { return new java.math.BigDecimal(String.valueOf(m.get(k))).longValueExact(); }
        catch (Exception e) { throw new IllegalArgumentException(k + "必须是范围内整数"); }
    }
    static String text(Map<String,Object> m,String k,String fallback) { return m.containsKey(k) ? String.valueOf(m.get(k)).trim() : fallback; }
    static boolean bool(Map<String,Object> m,String k,boolean fallback) {
        if (!m.containsKey(k)) return fallback;
        String v = String.valueOf(m.get(k));
        if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) throw new IllegalArgumentException(k + "必须为布尔值");
        return Boolean.parseBoolean(v);
    }
    Map<String,Object> map() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name",name); m.put("environment",environment.name()); m.put("type",type.name()); m.put("seed",Long.toString(seed));
        m.put("loadOnStartup",loadOnStartup); m.put("pvp",pvp); m.put("difficulty",difficulty.name()); m.put("time",time);
        m.put("daylightCycle",daylightCycle); m.put("weather",weather); m.put("autoSave",autoSave);
        m.put("allowCrossDimension",allowCrossDimension);
        m.put("spawnX",spawnX); m.put("spawnY",spawnY); m.put("spawnZ",spawnZ); m.put("gamerules",gamerules);
        return m;
    }
}
