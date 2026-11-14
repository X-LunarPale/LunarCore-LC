package com.lunarboreal.terrain;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

final class TerrainWorldFiles {
    private TerrainWorldFiles() {}

    static Path deletionTarget(Path levelDirectory, String name, Path actualFolder) throws IOException {
        Path expected = TerrainWorldSpec.dimensionPath(levelDirectory, TerrainWorldSpec.validateName(name));
        if (actualFolder != null && !actualFolder.toRealPath().equals(expected.toRealPath())) {
            throw new IOException("世界目录与地形登记不一致，无法删除");
        }
        return expected;
    }

    static void delete(Path levelDirectory, String name, Path expected) throws IOException {
        Path current = deletionTarget(levelDirectory, name, null);
        if (!current.equals(expected)) throw new IOException("地形世界目录已变化，请重新读取后操作");
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(current, new SimpleFileVisitor<>() {
            private void interrupted() throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("世界删除已中断");
            }

            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                interrupted();
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                interrupted();
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                interrupted();
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
