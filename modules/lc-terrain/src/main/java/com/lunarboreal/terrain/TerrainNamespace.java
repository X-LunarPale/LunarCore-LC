package com.lunarboreal.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class TerrainNamespace {
    public static final String CURRENT = "lc_terrain";
    public static final String LEGACY = "l" + "br_terrain";

    private TerrainNamespace() {}

    public static boolean isTerrain(String namespace) {
        return CURRENT.equals(namespace) || LEGACY.equals(namespace);
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
