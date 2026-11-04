package com.lunarboreal.structures.end;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Extra state is stored per dimension alongside the vanilla dragon fight. */
public final class EndFightData extends SavedData {
    public static final Codec<EndFightData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("firstPortal").forGetter((EndFightData d) -> d.firstPortal),
            Codec.BOOL.fieldOf("everSpawned").forGetter((EndFightData d) -> d.everSpawned),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("kills").forGetter((EndFightData d) -> d.kills),
            Codec.STRING.optionalFieldOf("stage", "").forGetter((EndFightData d) -> d.stage)
    ).apply(i, EndFightData::new));
    public static final SavedDataType<EndFightData> TYPE = new SavedDataType<>(Identifier.parse("lunarcore:better_end_island"),
            () -> new EndFightData(true, false, 0, ""), CODEC, net.minecraft.util.datafix.DataFixTypes.NONE);
    boolean firstPortal, everSpawned;
    int kills;
    String stage;

    public EndFightData(boolean firstPortal, boolean everSpawned, int kills, String stage) {
        this.firstPortal = firstPortal;
        this.everSpawned = everSpawned;
        this.kills = kills;
        this.stage = stage;
        if (!stage.isEmpty() && com.yungnickyoung.minecraft.betterendisland.world.BetterDragonRespawnStage.byName(stage) == null)
            throw new IllegalArgumentException("Unknown saved dragon respawn stage");
    }
}
