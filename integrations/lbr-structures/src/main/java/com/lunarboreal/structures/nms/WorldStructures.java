package com.lunarboreal.structures.nms;

import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.config.StructureFamily;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import java.util.ArrayList;
import java.util.List;

/** All writes happen in WorldInitEvent, before chunks begin generating. */
public final class WorldStructures {
    private WorldStructures() {}
    private static final org.bukkit.NamespacedKey HISTORY = new org.bukkit.NamespacedKey("lunarcorestructures", "custom_structure_history");

    /** Flat and plugin-generated worlds have nothing to overlay and stay on their own generator. */
    public static boolean supported(World world) {
        return com.lunarboreal.api.service.NativeGeneratorDelegate.unwrap(((CraftWorld) world).getHandle().getChunkSource().getGenerator())
                instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
    }

    public static void install(World world, GenerationPolicy requested) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        var staging = com.lunarboreal.structures.retrofit.StagingWorlds.pending(world.getKey().toString());
        if (staging != null) {
            try { com.lunarboreal.structures.retrofit.StagingWorlds.initialize(world, staging); }
            catch (Exception e) { throw new IllegalStateException("Cannot initialize isolated generation", e); }
            return;
        }
        if (com.lunarboreal.structures.retrofit.StagingWorlds.internal(world.getKey().toString()))
            throw new IllegalStateException("Refusing to load an unowned staging dimension");
        com.lunarboreal.structures.retrofit.StagingWorlds.remember(world, level.getChunkSource().getGenerator());
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
                prepareFeatures(noise, policy, endActive);
                var overlay = new StructureChunkGenerator(noise, policy);
                PaperAccess.write(level.getChunkSource().chunkMap, "worldGenContext", new net.minecraft.world.level.chunk.status.WorldGenContext(
                        context.level(), overlay, context.structureManager(), context.lightEngine(), context.mainThreadExecutor(), context.unsavedListener()));
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Incompatible structure-state constructor", error); }
    }

    public static void prepareFeatures(net.minecraft.world.level.chunk.ChunkGenerator generator, GenerationPolicy policy, boolean endActive) {
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
            values.put(biome, builder.build());
        }
        java.util.function.Function<Holder<net.minecraft.world.level.biome.Biome>, net.minecraft.world.level.biome.BiomeGenerationSettings> getter =
                biome -> values.getOrDefault(biome, originalGetter.apply(biome));
        PaperAccess.write(generator, "generationSettingsGetter", getter);
        PaperAccess.write(generator, "featuresPerStep", com.google.common.base.Suppliers.memoize(() ->
                net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(List.copyOf(generator.getBiomeSource().possibleBiomes()),
                        biome -> getter.apply(biome).features(), true)));
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
