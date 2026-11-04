package com.lunarboreal.structures.retrofit;

import com.lunarboreal.api.service.NativeGeneratorDelegate;
import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.nms.PaperAccess;
import com.lunarboreal.structures.nms.StructureChunkGenerator;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Temporary isolated dimensions for replay; no user world is used as the generation buffer. */
public final class StagingWorlds {
    public static final String NAMESPACE = "lunarcore_structure_stage";
    private static final Map<String, Spec> PENDING = new HashMap<>();
    private static final Map<UUID, Function<Holder<Biome>, BiomeGenerationSettings>> ORIGINAL_FEATURES = new HashMap<>();
    public record Spec(ServerLevel source, RetrofitCandidate candidate, boolean replace,
                       Function<Holder<Biome>, BiomeGenerationSettings> features) {}
    public record Pair(World baseline, World replacement, StructureStart replacementStart, Path baselinePath, Path replacementPath) {}

    public static void remember(World world, ChunkGenerator generator) {
        ORIGINAL_FEATURES.put(world.getUID(), generator.generationSettingsGetter);
    }
    public static Spec pending(String key) { return PENDING.get(key); }
    public static boolean internal(String key) { return key.startsWith(NAMESPACE + ":"); }

    public static Pair create(World world, RetrofitCandidate candidate) throws Exception {
        ServerLevel source = ((CraftWorld) world).getHandle();
        var features = ORIGINAL_FEATURES.get(world.getUID());
        if (features == null) throw new IllegalStateException("Cannot replay this world's original generation settings");
        World baseline = createOne(new Spec(source, candidate, false, features));
        World replacement = null;
        try {
            replacement = createOne(new Spec(source, candidate, true, features));
            var generator = ((CraftWorld) replacement).getHandle().getChunkSource().getGenerator();
            if (!(generator instanceof StageGenerator stage) || stage.start == null || !stage.start.isValid())
                throw new IllegalStateException("YUNG structure cannot be generated at the original location");
            return new Pair(baseline, replacement, stage.start, baseline.getWorldFolder().toPath(), replacement.getWorldFolder().toPath());
        } catch (Exception e) {
            Bukkit.unloadWorld(baseline, false);
            if (replacement != null) Bukkit.unloadWorld(replacement, false);
            throw e;
        }
    }

    private static World createOne(Spec spec) throws Exception {
        var source = spec.source;
        var original = NativeGeneratorDelegate.unwrap(source.getChunkSource().getGenerator());
        if (!(original instanceof NoiseBasedChunkGenerator noise)) throw new IllegalStateException("Unsupported generation history");
        var clone = new NoiseBasedChunkGenerator(noise.getBiomeSource(), noise.generatorSettings());
        var key = new NamespacedKey(NAMESPACE, "job_" + UUID.randomUUID().toString().replace("-", ""));
        var dimensionKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(key.toString()));
        Path folder = source.getServer().storageSource.getDimensionPath(dimensionKey).toAbsolutePath().normalize();
        Path root = source.getServer().storageSource.getLevelDirectory().path().toRealPath();
        Path expected = root.resolve("dimensions").resolve(NAMESPACE).resolve(key.getKey());
        if (!folder.equals(expected)) throw new IllegalStateException("Unexpected staging storage path");
        for (Path p = folder; p != null && p.startsWith(root); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalStateException("Staging paths cannot contain symbolic links");
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Staging path already exists");
        Files.createDirectories(folder);
        var settings = new WorldGenSettings(new net.minecraft.world.level.levelgen.WorldOptions(source.getSeed(), true, false),
                new WorldDimensions(Map.of(LevelStem.OVERWORLD, new LevelStem(source.dimensionTypeRegistration(), clone))));
        LevelStorageSource.writeWorldGenSettings(source.registryAccess(), folder, settings);
        PENDING.put(key.toString(), spec);
        try {
            World created = WorldCreator.ofKey(key).environment(World.Environment.NORMAL).seed(source.getSeed()).generateStructures(true).createWorld();
            if (created == null) throw new IllegalStateException("Staging world creation failed");
            return created;
        } finally { PENDING.remove(key.toString()); }
    }

    public static void initialize(World world, Spec spec) throws Exception {
        ServerLevel level = ((CraftWorld) world).getHandle();
        var noise = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        PaperAccess.write(noise, "generationSettingsGetter", spec.features);
        PaperAccess.write(noise, "featuresPerStep", com.google.common.base.Suppliers.memoize(() ->
                FeatureSorter.buildFeaturesPerStep(List.copyOf(noise.getBiomeSource().possibleBiomes()), b -> spec.features.apply(b).features(), true)));
        if (spec.replace && spec.candidate.family() == com.lunarboreal.structures.config.StructureFamily.DUNGEONS)
            com.lunarboreal.structures.nms.WorldStructures.prepareFeatures(noise,
                    new GenerationPolicy(Map.of(com.lunarboreal.structures.config.StructureFamily.DUNGEONS, true), false), false);
        var state = level.getChunkSource().getGeneratorState();
        var sets = state.possibleStructureSets().stream().filter(h -> h.value().structures().stream().allMatch(e ->
                e.structure().unwrapKey().orElseThrow().identifier().getNamespace().equals("minecraft"))).toList();
        if (spec.replace) sets = sets.stream().filter(h -> h.value().structures().stream().noneMatch(e ->
                spec.candidate.family().vanillaStructures.contains(e.structure().unwrapKey().orElseThrow().identifier().toString()))).toList();
        var constructor = ChunkGeneratorStructureState.class.getDeclaredConstructor(RandomState.class, BiomeSource.class, long.class,
                ChunkPos.class, long.class, List.class, org.spigotmc.SpigotWorldConfig.class);
        constructor.setAccessible(true);
        var sourceState = spec.source.getChunkSource().getGeneratorState();
        var replacementState = constructor.newInstance(state.randomState(), noise.getBiomeSource(), sourceState.getLevelSeed(), sourceState.getDimensionOrigin(),
                (long) PaperAccess.read(sourceState, "concentricRingsSeed"), sets, sourceState.config);
        replacementState.ensureStructuresGenerated();
        PaperAccess.write(level.getChunkSource().chunkMap, "chunkGeneratorState", replacementState);
        var stage = new StageGenerator(noise, spec, level);
        var context = level.getChunkSource().chunkMap.worldGenContext;
        PaperAccess.write(level.getChunkSource().chunkMap, "worldGenContext", new net.minecraft.world.level.chunk.status.WorldGenContext(level, stage,
                context.structureManager(), context.lightEngine(), context.mainThreadExecutor(), context.unsavedListener()));
    }

    public static void unload(Pair pair) {
        if (!Bukkit.unloadWorld(pair.baseline, false) || !Bukkit.unloadWorld(pair.replacement, false))
            throw new IllegalStateException("Unable to unload staging world; files were retained");
    }

    /** Only these freshly allocated dimensions may be removed, after both have unloaded. */
    public static void delete(Pair pair) throws java.io.IOException {
        for (Path path : List.of(pair.baselinePath, pair.replacementPath)) {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.getParent().getFileName().toString().equals(NAMESPACE)
                    || !normalized.getFileName().toString().matches("job_[a-f0-9]{32}") || Files.isSymbolicLink(normalized))
                throw new java.io.IOException("Refusing to delete an unowned staging path");
            if (!Files.exists(normalized)) continue;
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, java.io.IOException error) throws java.io.IOException {
                    if (error != null) throw error;
                    Files.delete(directory); return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static final class StageGenerator extends StructureChunkGenerator {
        private final Spec spec;
        private final StructureStart start;
        StageGenerator(NoiseBasedChunkGenerator generator, Spec spec, ServerLevel level) {
            super(generator, GenerationPolicy.vanilla());
            this.spec = spec;
            this.start = spec.replace ? plan(level, generator, spec.candidate) : null;
        }
        @Override public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager manager,
                ChunkAccess chunk, StructureTemplateManager templates, ResourceKey<Level> dimension) {
            super.createStructures(registries, state, manager, chunk, templates, dimension);
            if (start != null && chunk.getPos().equals(spec.candidate.start())) chunk.setStartForStructure(start.getStructure(), start);
        }
    }

    private static StructureStart plan(ServerLevel level, ChunkGenerator generator, RetrofitCandidate candidate) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var random = level.getChunkSource().randomState();
        var candidates = registry.listElements().filter(h -> h.key().identifier().getNamespace().equals(candidate.family().namespace))
                .filter(h -> candidate.dungeonOrigin() == null || h.key().identifier().getPath().equals("small_dungeon"))
                .sorted(Comparator.comparing(h -> h.key().identifier().toString())).toList();
        for (var holder : candidates) {
            var structure = holder.value();
            var start = structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(),
                    random.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED), random,
                    level.getStructureTemplateManager(), level.getSeed(), candidate.start(), 0, level, structure.biomes()::contains);
            if (!start.isValid()) continue;
            if (candidate.dungeonOrigin() != null) {
                int dy = candidate.dungeonOrigin().getY() - start.getBoundingBox().minY();
                start.getPieces().forEach(piece -> piece.move(0, dy, 0));
                return new StructureStart(structure, candidate.start(), 0,
                        new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(start.getPieces()));
            }
            return start;
        }
        throw new IllegalStateException("No compatible YUNG layout at the original structure");
    }
}
