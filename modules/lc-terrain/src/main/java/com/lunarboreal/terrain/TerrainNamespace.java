package com.lunarboreal.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;

public final class TerrainNamespace {
    public static final String CURRENT = "lc_terrain";
    public static final String LEGACY = "l" + "br_terrain";

    private TerrainNamespace() {}

    public static boolean isTerrain(String namespace) {
        return CURRENT.equals(namespace) || LEGACY.equals(namespace);
    }

    static String registeredName(String value, Collection<String> registered) {
        if (value == null) return null;
        var matches = new LinkedHashSet<String>();
        if (registered.contains(value)) matches.add(value);
        for (String name : registered) {
            for (String namespace : new String[]{CURRENT, LEGACY}) {
                String displayName = namespace + "_" + name;
                if (value.equals(namespace + ":" + name) || value.equals(displayName)
                        || value.equals("minecraft:" + displayName)) matches.add(name);
            }
        }
        if (matches.size() > 1) throw new IllegalArgumentException("地形世界标识对应多个登记，请使用完整维度标识");
        return matches.stream().findFirst().orElse(null);
    }

    public static String forWorld(Path levelDirectory, String name) throws IOException {
        TerrainWorldSpec.validateName(name);
        Path root = levelDirectory.toRealPath();
        Path current = checkedPath(root, CURRENT, name);
        Path legacy = checkedPath(root, LEGACY, name);
        boolean currentExists = Files.exists(current, LinkOption.NOFOLLOW_LINKS);
        boolean legacyExists = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        if (currentExists && legacyExists) throw new IOException("新旧地形存档存在同名目录，请先确认使用的世界");
        return legacyExists ? LEGACY : CURRENT;
    }

    public static Path dimensionPath(Path levelDirectory, String name) throws IOException {
        Path root = levelDirectory.toRealPath();
        return checkedPath(root, forWorld(root, name), name);
    }

    private static Path checkedPath(Path root, String namespace, String name) throws IOException {
        Path target = root.resolve("dimensions").resolve(namespace).resolve(name);
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize()))) {
                throw new IOException("地形世界路径存在重定向，已拒绝访问");
            }
        }
        return target;
    }
}
