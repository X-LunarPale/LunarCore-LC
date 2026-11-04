package com.lunarboreal.structures.config;

import java.util.Arrays;
import java.util.Set;

/** Stable persisted identifiers, independent of UI translations and upstream class names. */
public enum StructureFamily {
    MINESHAFTS("mineshafts", "bettermineshafts", "矿井", Set.of("minecraft:mineshaft", "minecraft:mineshaft_mesa")),
    DUNGEONS("dungeons", "betterdungeons", "地牢", Set.of()),
    STRONGHOLDS("strongholds", "betterstrongholds", "要塞", Set.of("minecraft:stronghold")),
    DESERT_TEMPLES("desert_temples", "betterdeserttemples", "沙漠神庙", Set.of("minecraft:desert_pyramid")),
    JUNGLE_TEMPLES("jungle_temples", "betterjungletemples", "丛林神庙", Set.of("minecraft:jungle_pyramid")),
    WITCH_HUTS("witch_huts", "betterwitchhuts", "女巫小屋", Set.of("minecraft:swamp_hut")),
    OCEAN_MONUMENTS("ocean_monuments", "betteroceanmonuments", "海底神殿", Set.of("minecraft:monument")),
    NETHER_FORTRESSES("nether_fortresses", "betterfortresses", "下界要塞", Set.of("minecraft:fortress")),
    END_ISLAND("end_island", "betterendisland", "末地岛", Set.of());

    public final String id, namespace, label;
    public final Set<String> vanillaStructures;

    StructureFamily(String id, String namespace, String label, Set<String> vanillaStructures) {
        this.id = id;
        this.namespace = namespace;
        this.label = label;
        this.vanillaStructures = vanillaStructures;
    }

    public static StructureFamily require(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知结构类型：" + id));
    }
}
