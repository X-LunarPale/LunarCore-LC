package com.lunarboreal.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

record TerrainWorldSpec(String name, long seed, TerrainOptions options, boolean autoLoad) {
    static String validateName(Object value) {
        if (!(value instanceof String name) || !name.matches("[a-z][a-z0-9_-]{0,31}")
                || name.matches("(con|prn|aux|nul|com[0-9]|lpt[0-9])")) {
            throw new IllegalArgumentException("世界名须为1~32位小写字母、数字、下划线或横线，以字母开头，且不能是设备名");
        }
        return name;
    }

    static long parseSeed(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("种子须以整数字符串提交");
        text = text.trim();
        if (text.isEmpty()) return ThreadLocalRandom.current().nextLong();
        if (!text.matches("-?[0-9]{1,19}")) throw new IllegalArgumentException("种子须为64位整数，可留空随机生成");
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("种子超出64位整数范围");
        }
    }

    String key() {
        return "lbr_terrain:" + name;
    }

    Map<String, Object> map() {
        return Map.of("name", name, "seed", Long.toString(seed), "config", options.map(), "autoLoad", autoLoad);
    }

    TerrainWorldSpec withAutoLoad(boolean value) {
        return new TerrainWorldSpec(name, seed, options, value);
    }

    static Path dimensionPath(Path levelDirectory, String name) throws IOException {
        validateName(name);
        Path root = levelDirectory.toRealPath();
        Path target = root.resolve("dimensions").resolve("lbr_terrain").resolve(name);
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
