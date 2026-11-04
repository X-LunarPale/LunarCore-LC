package com.lunarboreal.probe;

import net.minecraft.nbt.*;
import java.nio.file.*;

/** Offline resource migration, never a server command. Originals remain in third-party/yungs.
 * Removes only default-valued Forge attributes with no vanilla registry entry; Minecraft attributes are preserved. */
public final class TemplateMigration {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!root.endsWith("integrations/lbr-structures/src/main/resources/yungs/data"))
            throw new IllegalArgumentException("Only the adapted resource tree may be migrated");
        int changed = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                if (migrate(tag)) { NbtIo.writeCompressed(tag, path); changed++; }
            }
        }
        System.out.println("Removed default Forge-only attributes from " + changed + " adapted templates");
    }

    private static boolean migrate(Tag value) {
        boolean changed = false;
        if (value instanceof CompoundTag compound) {
            for (String key : new String[]{"Attributes", "attributes"}) {
                var list = compound.getListOrEmpty(key);
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (!(list.get(i) instanceof CompoundTag attribute)) continue;
                    String id = attribute.getStringOr("Name", attribute.getStringOr("id", ""));
                    if (!id.startsWith("forge:")) continue;
                    double base = attribute.getDoubleOr("Base", attribute.getDoubleOr("base", Double.NaN));
                    if (!(id.equals("forge:entity_gravity") && base == .08 || id.equals("forge:step_height_addition") && base == 0)
                            || attribute.contains("Modifiers") || attribute.contains("modifiers"))
                        throw new IllegalStateException("Forge attribute needs a semantic port: " + attribute);
                    list.remove(i); changed = true;
                }
            }
            for (Tag nested : compound.values()) changed |= migrate(nested);
        } else if (value instanceof ListTag list) {
            for (Tag nested : list) changed |= migrate(nested);
        }
        return changed;
    }
}
