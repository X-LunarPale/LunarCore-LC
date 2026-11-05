package com.lunarboreal.structures.nms;

import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.config.StructureFamily;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import java.util.ArrayList;
import java.util.List;

/** All writes happen in WorldInitEvent, before chunks begin generating. */
public final class WorldStructures {
    private WorldStructures() {}
    private static final org.bukkit.NamespacedKey HISTORY = new org.bukkit.NamespacedKey("lunarcorestructures", "custom_structure_history");
    private static final TagKey<Biome> DESERT_DECORATIONS = biomes("yungsextras:has_structure/desert_decorations");
    private static final TagKey<Biome> SWAMP_STRUCTURES = biomes("yungsextras:has_structure/swamp_structures");
    private static final TagKey<Biome> VANILLA_DESERT_WELL = biomes("yungsextras:has_structure/vanilla_desert_well");
    private static final TagKey<Biome> BRIDGE_BIOMES = biomes("yungsbridges:has_structure/bridge");
    private static TagKey<Biome> biomes(String id) { return TagKey.create(Registries.BIOME, net.minecraft.resources.Identifier.parse(id)); }

    /** Feature-only YUNG projects: placed per biome and, unlike structures, saved in no chunk record. */
    public record Decoration(Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature> feature, TagKey<Biome> biomes) {}

    /** Flat and plugin-generated worlds have nothing to overlay and stay on their own generator. */
    public static boolean supported(World world) {
        return com.lunarboreal.api.service.NativeGeneratorDelegate.unwrap(((CraftWorld) world).getHandle().getChunkSource().getGenerator())
                instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
    }

    public static void install(World world, GenerationPolicy requested) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        boolean supported = supported(world);
        var policy = supported ? requested : GenerationPolicy.vanilla();
        boolean endActive = com.lunarboreal.structures.end.EndIslandController.install(level, policy.enabled(StructureFamily.END_ISLAND));
        var state = level.getChunkSource().getGeneratorState();
        List<Holder<StructureSet>> selected = select(state.possibleStructureSets(), policy);
        // Paper eagerly computes placements in ChunkMap's constructor. Build a fresh,
        // world-owned state instead of altering a state with running ring futures.
        try {
            var constructor = net.minecraft.world.level.chunk.ChunkGeneratorStructureState.class.getDeclaredConstructor(
                    net.minecraft.world.level.levelgen.RandomState.class, net.minecraft.world.level.biome.BiomeSource.class,
                    long.class, net.minecraft.world.level.ChunkPos.class, long.class, List.class, org.spigotmc.SpigotWorldConfig.class);
            constructor.setAccessible(true);
            var replacement = constructor.newInstance(state.randomState(), level.getChunkSource().getGenerator().getBiomeSource(),
                    state.getLevelSeed(), state.getDimensionOrigin(), (long) PaperAccess.read(state, "concentricRingsSeed"), selected, state.config);
            GuardedPlacement.bind(replacement, policy);
            replacement.ensureStructuresGenerated();
            PaperAccess.write(level.getChunkSource().chunkMap, "chunkGeneratorState", replacement);
            if (policy.anyEnabled()) world.getPersistentDataContainer().set(HISTORY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            if (supported && (policy.anyEnabled() || world.getPersistentDataContainer().has(HISTORY))) {
                var context = level.getChunkSource().chunkMap.worldGenContext;
                var noise = (net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator) context.generator();
                var overlay = new StructureChunkGenerator(noise, policy, prepareFeatures(noise, policy, endActive, decorations(level, policy)));
                PaperAccess.write(level.getChunkSource().chunkMap, "worldGenContext", new net.minecraft.world.level.chunk.status.WorldGenContext(
                        context.level(), overlay, context.structureManager(), context.lightEngine(), context.mainThreadExecutor(), context.unsavedListener()));
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Incompatible structure-state constructor", error); }
    }

    /** Sorted so every biome appends in one order; FeatureSorter rejects conflicting orders. */
    private static List<Decoration> decorations(ServerLevel level, GenerationPolicy policy) {
        var result = new ArrayList<Decoration>();
        level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).listElements()
                .sorted(java.util.Comparator.comparing(feature -> feature.key().identifier().toString()))
                .forEach(feature -> {
                    var id = feature.key().identifier();
                    if (policy.enabled(StructureFamily.EXTRAS) && id.getNamespace().equals(StructureFamily.EXTRAS.namespace))
                        result.add(new Decoration(feature, id.getPath().startsWith("desert/") ? DESERT_DECORATIONS : SWAMP_STRUCTURES));
                    else if (policy.enabled(StructureFamily.BRIDGES) && id.toString().equals("yungsbridges:bridge_list"))
                        result.add(new Decoration(feature, BRIDGE_BIOMES));
                });
        return List.copyOf(result);
    }

    /** Worlds created from one preset share a generator, so the result must stay with this world's overlay. */
    public static java.util.function.Function<Holder<Biome>, net.minecraft.world.level.biome.BiomeGenerationSettings> prepareFeatures(
            net.minecraft.world.level.chunk.ChunkGenerator generator, GenerationPolicy policy, boolean endActive, List<Decoration> decorations) {
        var originalGetter = generator.generationSettingsGetter;
        var values = new java.util.HashMap<Holder<net.minecraft.world.level.biome.Biome>, net.minecraft.world.level.biome.BiomeGenerationSettings>();
        var replacements = new java.util.IdentityHashMap<net.minecraft.world.level.levelgen.placement.PlacedFeature, Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature>>();
        for (var biome : generator.getBiomeSource().possibleBiomes()) {
            var old = originalGetter.apply(biome);
            var builder = new net.minecraft.world.level.biome.BiomeGenerationSettings.PlainBuilder();
            old.getCarvers().forEach(builder::addCarver);
            for (int step = 0; step < old.features().size(); step++)
                for (var feature : old.features().get(step)) {
                    String id = feature.unwrapKey().map(k -> k.identifier().toString()).orElse("");
                    if (policy.enabled(StructureFamily.DUNGEONS) && (id.equals("minecraft:monster_room") || id.equals("minecraft:monster_room_deep"))) continue;
                    if (policy.enabled(StructureFamily.EXTRAS) && id.equals("minecraft:desert_well") && biome.is(VANILLA_DESERT_WELL)) continue;
                    var original = feature.value().feature().value();
                    net.minecraft.world.level.levelgen.feature.Feature replacement = original;
                    if (endActive && policy.enabled(StructureFamily.END_ISLAND)) {
                        if (original instanceof net.minecraft.world.level.levelgen.feature.EndSpikeFeature spike)
                            replacement = new com.lunarboreal.structures.end.EndSpikeGenerator(spike);
                        else if (original instanceof net.minecraft.world.level.levelgen.feature.EndGatewayFeature gateway)
                            replacement = new com.lunarboreal.structures.end.EndGatewayGenerator(gateway);
                    }
                    replacement = GuardedFeature.wrap(replacement, id);
                    if (replacement == original) builder.addFeature(step, feature);
                    else {
                        var prepared = replacement;
                        builder.addFeature(step, replacements.computeIfAbsent(feature.value(), f -> Holder.direct(
                                new net.minecraft.world.level.levelgen.placement.PlacedFeature(Holder.direct(prepared), f.placement()))));
                    }
                }
            for (var decoration : decorations)
                if (biome.is(decoration.biomes())) builder.addFeature(GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal(), decoration.feature());
            values.put(biome, builder.build());
        }
        return biome -> values.getOrDefault(biome, originalGetter.apply(biome));
    }

    public static List<Holder<StructureSet>> select(List<Holder<StructureSet>> original, GenerationPolicy policy) {
        var result = new ArrayList<Holder<StructureSet>>();
        for (var holder : original) {
            var before = holder.value().structures();
            var after = before.stream().filter(entry -> {
                var key = entry.structure().unwrapKey().orElseThrow().identifier();
                if (key.toString().equals("betterdungeons:small_nether_dungeon")) return false; // Upstream default.
                for (var family : StructureFamily.values()) {
                    if (key.getNamespace().equals(family.namespace)) return policy.enabled(family);
                    if (family.vanillaStructures.contains(key.toString())) return !policy.enabled(family);
                }
                return true;
            }).toList();
            if (after.isEmpty()) continue;
            result.add(after.size() == before.size() ? holder : Holder.direct(new StructureSet(after, holder.value().placement())));
        }
        return List.copyOf(result);
    }
}
