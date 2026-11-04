package com.lunarboreal.terrain.nms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lunarboreal.terrain.TectonicModel;
import com.lunarboreal.terrain.TerrainHeight;
import com.lunarboreal.terrain.TerrainOptions;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.papermc.paper.ServerBuildInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Paper 26.3 adapter. Each world keeps its own native generation graph.
 * No vanilla registry entry or primary-world generation setting is replaced.
 */
public final class NativeTectonicBridge implements Listener {
    private static final String NAMESPACE = "lbr_terrain";
    private static final RegistrationInfo OWN_REGISTRATION =
            new RegistrationInfo(Optional.empty(), Lifecycle.stable());

    private MinecraftServer server;
    private RegistryAccess.Frozen registries;
    private boolean initialized;
    private final Map<TerrainOptions, ExpectedSettings> configurations = new LinkedHashMap<>();
    private final Map<String, NativeTectonicFeatures.Prepared> pending = new LinkedHashMap<>();
    private final Map<String, TerrainOptions> pendingOptions = new LinkedHashMap<>();
    private final Map<String, Throwable> setupErrors = new LinkedHashMap<>();
    private final Set<String> configured = new HashSet<>();
    private final List<TerrainHeight> registeredHeights = new ArrayList<>();

    private record ExpectedSettings(NoiseGeneratorSettings settings, JsonElement canonical) {}

    /** Called on the main thread before players can receive their registry snapshot. */
    public void initialize() throws Exception {
        initialize(TerrainHeight.DEFAULTS);
    }

    public void initialize(Collection<TerrainHeight> heights) throws Exception {
        requireMainThread();
        var build = ServerBuildInfo.buildInfo();
        if (!build.minecraftVersionId().equals("26.3")
                || !build.brandId().equals(ServerBuildInfo.BRAND_PAPER_ID)) {
            throw new IllegalStateException("Native Tectonic requires Paper 26.3");
        }
        requireNativeLayout();
        TerrainBlendingHooks.install();
        server = ((CraftServer) Bukkit.getServer()).getServer();
        registries = server.registryAccess();
        NativeTectonicFunctions.registerTypes();
        Registry<DimensionType> dimensions = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        DimensionType overworld = dimensions.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);
        Map<ResourceKey<DimensionType>, DimensionType> dimensionEntries = new LinkedHashMap<>();
        for (TerrainHeight height : new java.util.LinkedHashSet<>(heights)) {
            if (Bukkit.getWorlds().isEmpty() || dimensions.containsKey(dimensionTypeKey(height))) {
                dimensionEntries.put(dimensionTypeKey(height), withHeight(overworld, height));
                registeredHeights.add(height);
            }
        }

        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Map<ResourceKey<NormalNoise>, NormalNoise> noiseEntries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : TectonicModel.noises().entrySet()) {
            Identifier id = Identifier.parse(entry.getKey());
            if (!id.getNamespace().equals("tectonic")) {
                throw new IllegalStateException("Refusing to replace a non-Tectonic noise: " + id);
            }
            noiseEntries.put(ResourceKey.create(Registries.NOISE, id),
                    NormalNoise.DIRECT_CODEC.parse(ops, entry.getValue()).getOrThrow());
        }
        Registry<NormalNoise> noises = registries.lookupOrThrow(Registries.NOISE);
        validateEntries(dimensions, dimensionEntries);
        validateEntries(noises, noiseEntries);
        boolean missing = dimensionEntries.keySet().stream().anyMatch(key -> !dimensions.containsKey(key))
                || noiseEntries.keySet().stream().anyMatch(key -> !noises.containsKey(key));
        if (missing && !Bukkit.getWorlds().isEmpty()) {
            throw new IllegalStateException("Restart with Native Tectonic enabled to register terrain before login begins");
        }
        appendEntries(noises, noiseEntries);
        appendEntries(dimensions, dimensionEntries);
        NativeTectonicClimate.initialize(registries.lookupOrThrow(Registries.BIOME));
        initialized = true;
    }

    public void close() throws Exception {
        initialized = false;
        Exception failure = null;
        try {
            NativeTectonicClimate.close();
        } catch (Exception cleanup) {
            failure = cleanup;
        }
        try {
            TerrainBlendingHooks.uninstall();
        } catch (Exception cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        pending.clear();
        pendingOptions.clear();
        configured.clear();
        setupErrors.clear();
        configurations.clear();
        if (failure != null) throw failure;
    }

    static void requireNativeLayout() {
        try {
            MappedRegistry.class.getDeclaredField("frozen");
            Field allTags = MappedRegistry.class.getDeclaredField("allTags");
            allTags.getType().getDeclaredMethod("unbound");
            MappedRegistry.class.getDeclaredMethod("refreshTagsInHolders");
            ChunkGenerator.class.getDeclaredField("generationSettingsGetter");
            ChunkGenerator.class.getDeclaredField("featuresPerStep");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Paper 26.3 native terrain API is incompatible", e);
        }
    }

    public List<Integer> heights() {
        return registeredHeights.stream().filter(range -> range.minY() == -64).map(TerrainHeight::height).toList();
    }

    public List<Map<String, Object>> heightRanges() {
        return registeredHeights.stream().map(TerrainHeight::map).toList();
    }

    public boolean supports(TerrainOptions options) {
        return registeredHeights.contains(options.heightRange());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        NamespacedKey key = event.getWorld().getKey();
        NativeTectonicFeatures.Prepared prepared = pending.get(key.getKey());
        if (!key.getNamespace().equals(NAMESPACE) || prepared == null) return;
        try {
            var level = ((CraftWorld) event.getWorld()).getHandle();
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
                throw new IOException("Terrain decoration requires the native noise generator");
            }
            NativeTectonicFeatures.install(generator, prepared);
            NativeTectonicClimate.attach(level, pendingOptions.get(key.getKey()));
            configured.add(key.getKey());
        } catch (Exception | LinkageError failure) {
            setupErrors.put(key.getKey(), failure);
        }
    }

    private void configureWorld(World world, TerrainOptions options) throws Exception {
        var level = ((CraftWorld) world).getHandle();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            throw new IOException("Terrain decoration requires the native noise generator");
        }
        NativeTectonicFeatures.install(generator, NativeTectonicFeatures.prepare(
                generator, level.getChunkSource().randomState(), registries, options));
        NativeTectonicClimate.attach(level, options);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) throws Exception {
        if (event.getWorld().getKey().getNamespace().equals(NAMESPACE)) {
            NativeTectonicClimate.detach(((CraftWorld) event.getWorld()).getHandle());
        }
    }

    /**
     * Persists an isolated WorldGenSettings first, then lets Paper perform its
     * regular world initialization, events, storage and chunk-system setup.
     */
    public World create(String name, long seed, TerrainOptions options, boolean existing) throws Exception {
        requireMainThread();
        if (!initialized) throw new IllegalStateException("Native Tectonic is not initialized");
        if (!supports(options)) {
            throw new IllegalArgumentException("This world height requires a full server restart with the updated terrain module");
        }
        if (!name.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid terrain world name");
        NamespacedKey bukkitKey = new NamespacedKey(NAMESPACE, name);
        World loaded = Bukkit.getWorld(bukkitKey);
        if (loaded != null) {
            verifyLoaded(loaded, seed, options);
            configureWorld(loaded, options);
            return loaded;
        }
        if (Bukkit.getWorld(name) != null) throw new IllegalArgumentException("World name is already loaded");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(NAMESPACE, name));
        Path folder = checkedDimensionPath(key);
        boolean folderExists = Files.exists(folder, LinkOption.NOFOLLOW_LINKS);
        if (folderExists != existing) throw new IOException("Terrain world directory changed; reload and retry");
        NoiseGeneratorSettings nativeSettings = expectedSettings(options).settings();
        var prototype = new NoiseBasedChunkGenerator(NativeTectonicClimate.biomeSource(registries, options),
                Holder.direct(nativeSettings));
        NativeTectonicFeatures.Prepared prepared = NativeTectonicFeatures.prepare(prototype,
                RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, nativeSettings),
                registries, options);

        if (existing) {
            verifySettings(readSettings(key), seed, options);
        } else {
            Holder<DimensionType> dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE)
                    .getOrThrow(dimensionTypeKey(options.heightRange()));
            var worldSettings = new WorldGenSettings(
                    new net.minecraft.world.level.levelgen.WorldOptions(seed, true, false),
                    new WorldDimensions(Map.of(LevelStem.OVERWORLD, new LevelStem(dimension, prototype))));
            Files.createDirectories(folder);
            LevelStorageSource.writeWorldGenSettings(registries, folder, worldSettings);
            verifySettings(readSettings(key), seed, options);
        }

        pending.put(name, prepared);
        pendingOptions.put(name, options);
        configured.remove(name);
        setupErrors.remove(name);
        World world;
        try {
            world = WorldCreator.ofKey(bukkitKey)
                    .environment(World.Environment.NORMAL)
                    .seed(seed)
                    .generateStructures(true)
                    .createWorld();
        } finally {
            pending.remove(name);
            pendingOptions.remove(name);
        }
        if (world == null) throw new IOException("Paper did not finish creating the terrain world");
        Throwable failure = setupErrors.remove(name);
        if (failure != null) throw new IOException("Native terrain decoration setup failed", failure);
        if (!configured.remove(name)) throw new IOException("Paper did not initialize the terrain decoration hooks");
        verifyLoaded(world, seed, options);
        return world;
    }

    private WorldGenSettings readSettings(ResourceKey<Level> key) throws IOException {
        var result = LevelStorageSource.readExistingSavedData(server.storageSource, key, registries, WorldGenSettings.TYPE);
        return result.result().orElseThrow(() -> new IOException(
                "Terrain WorldGenSettings is missing or unreadable; refusing vanilla regeneration"));
    }

    private void verifySettings(WorldGenSettings settings, long seed, TerrainOptions options) throws IOException {
        LevelStem stem = settings.dimensions().get(LevelStem.OVERWORLD)
                .orElseThrow(() -> new IOException("Terrain overworld settings are missing"));
        if (settings.options().seed() != seed
                || !stem.type().is(dimensionTypeKey(options.heightRange()))
                || stem.type().value().minY() != options.minY()
                || stem.type().value().height() != options.height()
                || !(stem.generator() instanceof NoiseBasedChunkGenerator noise)
                || noise.generatorSettings().value().noiseSettings().height() != options.height()
                || noise.generatorSettings().value().noiseSettings().minY() != options.minY()) {
            throw new IOException("Saved terrain world generation does not match its registration");
        }
        verifyNoise(((NoiseBasedChunkGenerator) stem.generator()).generatorSettings().value(), options);
    }

    private void verifyLoaded(World world, long seed, TerrainOptions options) throws IOException {
        if (world.getSeed() != seed || world.getMinHeight() != options.minY()
                || world.getMaxHeight() != options.maxY()
                || !((CraftWorld) world).getHandle().dimensionTypeRegistration().is(dimensionTypeKey(options.heightRange()))
                || !(((CraftWorld) world).getHandle().getChunkSource().getGenerator()
                instanceof NoiseBasedChunkGenerator noise)) {
            throw new IOException("Paper loaded unexpected terrain generation; the world has not been marked ready");
        }
        verifyNoise(noise.generatorSettings().value(), options);
    }

    private ExpectedSettings expectedSettings(TerrainOptions options) throws IOException {
        ExpectedSettings previous = configurations.get(options);
        if (previous != null) return previous;
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                ops, TectonicModel.noiseSettings(options)).getOrThrow();
        if (settings.noiseSettings().minY() != options.minY() || settings.noiseSettings().height() != options.height()) {
            throw new IOException("Tectonic noise settings returned an unexpected height");
        }
        var expected = new ExpectedSettings(settings,
                NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow());
        configurations.put(options, expected);
        return expected;
    }

    private void verifyNoise(NoiseGeneratorSettings settings, TerrainOptions options) throws IOException {
        JsonElement canonical = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(
                registries.createSerializationContext(JsonOps.INSTANCE), settings).getOrThrow();
        if (!expectedSettings(options).canonical().equals(canonical)) {
            throw new IOException("Saved Tectonic generation parameters differ from the world registration");
        }
    }

    private Path checkedDimensionPath(ResourceKey<Level> key) throws IOException {
        Path root = server.storageSource.getLevelDirectory().path().toRealPath();
        Path target = server.storageSource.getDimensionPath(key).toAbsolutePath().normalize();
        Path expected = root.resolve("dimensions").resolve(NAMESPACE).resolve(key.identifier().getPath());
        if (!target.equals(expected)) throw new IOException("Unexpected native terrain dimension path");
        Path cursor = root;
        for (Path component : root.relativize(target)) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize()))) {
                throw new IOException("Terrain directory must not redirect through links");
            }
        }
        return target;
    }

    private static DimensionType withHeight(DimensionType original, TerrainHeight height) {
        return new DimensionType(original.hasFixedTime(), original.hasSkyLight(), original.hasCeiling(),
                original.hasEnderDragonFight(), original.coordinateScale(), height.minY(), height.height(), height.height(),
                original.infiniburn(), original.ambientLight(), original.monsterSettings(), original.skybox(),
                original.cardinalLightType(), original.attributes(), original.timelines(), original.defaultClock());
    }

    private static ResourceKey<DimensionType> dimensionTypeKey(TerrainHeight height) {
        return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.fromNamespaceAndPath(NAMESPACE, height.dimensionName()));
    }

    private static <T> void validateEntries(Registry<T> registry, Map<ResourceKey<T>, T> entries) {
        entries.forEach((key, value) -> {
            T previous = registry.getValue(key);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException("Conflicting native terrain registry entry: " + key.identifier());
            }
        });
    }

    /** Append-only startup adapter; preserve existing holders, IDs and tag memberships. */
    static <T> void appendEntries(Registry<T> registry, Map<ResourceKey<T>, T> entries) throws Exception {
        var missing = new LinkedHashMap<ResourceKey<T>, T>();
        entries.forEach((key, value) -> {
            if (!registry.containsKey(key)) missing.put(key, value);
        });
        if (missing.isEmpty()) return;
        if (!(registry instanceof MappedRegistry<T> mapped)) {
            throw new IllegalStateException("Unsupported Paper registry implementation");
        }
        Field frozen = MappedRegistry.class.getDeclaredField("frozen");
        Field allTags = MappedRegistry.class.getDeclaredField("allTags");
        frozen.setAccessible(true);
        allTags.setAccessible(true);
        if (!frozen.getBoolean(mapped)) {
            throw new IllegalStateException("Terrain registries were not frozen at plugin startup");
        }
        Method unbound = allTags.getType().getDeclaredMethod("unbound");
        unbound.setAccessible(true);
        Object existingTags = allTags.get(mapped);
        frozen.setBoolean(mapped, false);
        try {
            for (var entry : missing.entrySet()) {
                mapped.register(entry.getKey(), entry.getValue(), OWN_REGISTRATION)
                        .bindComponents(DataComponentMap.EMPTY);
            }
        } finally {
            // Reloaded tags may be absent from frozenTags; retain their current named sets.
            allTags.set(mapped, unbound.invoke(null));
            try {
                mapped.freeze();
                allTags.set(mapped, existingTags);
                refreshTags(mapped);
            } catch (Exception | Error failure) {
                allTags.set(mapped, existingTags);
                frozen.setBoolean(mapped, true);
                throw failure;
            }
        }
    }

    static void refreshTags(Registry<?> registry) throws ReflectiveOperationException {
        Method refresh = MappedRegistry.class.getDeclaredMethod("refreshTagsInHolders");
        refresh.setAccessible(true);
        refresh.invoke(registry);
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Native terrain operations require the server thread");
    }
}
