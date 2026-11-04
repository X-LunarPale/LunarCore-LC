package com.lunarboreal.terrain.nms;

import com.lunarboreal.terrain.TerrainOptions;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Keeps Tectonic climate changes local to its worlds. */
public final class NativeTectonicClimate {
    static final ResourceKey<Biome> ICE_FREE_FROZEN_OCEAN = ResourceKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath("lbr_terrain", "ice_free_frozen_ocean"));
    private static final String HOOKS = "com.lunarboreal.terrain.agent.NativeTerrainHooks";
    private static final Map<Object, WorldClimate> WORLDS = new IdentityHashMap<>();

    private record WorldClimate(Object generator, BiomeManager manager, TemperatureBiomes resolver) {}

    private NativeTectonicClimate() {}

    public static void initialize(Registry<Biome> biomes) throws Exception {
        if (!biomes.containsKey(ICE_FREE_FROZEN_OCEAN)) {
            Biome original = biomes.getValueOrThrow(Biomes.FROZEN_OCEAN);
            NativeTectonicBridge.appendEntries(biomes, Map.of(ICE_FREE_FROZEN_OCEAN, copy(original,
                    new Biome.ClimateSettings(true, 0.5F, Biome.TemperatureModifier.FROZEN, 0.5F))));
        }
        Holder.Reference<Biome> original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        Holder.Reference<Biome> replacement = biomes.getOrThrow(ICE_FREE_FROZEN_OCEAN);
        replacement.bindComponents(original.components());
        Method bindTag = HolderSet.Named.class.getDeclaredMethod("bind", java.util.List.class);
        bindTag.setAccessible(true);
        for (var tag : biomes.listTags().toList()) {
            var values = new ArrayList<Holder<Biome>>(tag.stream().toList());
            if (original.is(tag.key()) && !values.contains(replacement)) {
                values.add(replacement);
                bindTag.invoke(tag, values);
            }
        }
        NativeTectonicBridge.refreshTags(biomes);
        hook("registerBiomeAlias", new Class<?>[]{Object.class, Object.class},
                ICE_FREE_FROZEN_OCEAN, Biomes.FROZEN_OCEAN);
    }

    public static MultiNoiseBiomeSource biomeSource(HolderLookup.Provider registries, TerrainOptions options) {
        var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        if (!options.flag("oceans.remove_frozen_ocean_ice")) return MultiNoiseBiomeSource.createFromPreset(preset);
        Holder<Biome> replacement = registries.lookupOrThrow(Registries.BIOME).getOrThrow(ICE_FREE_FROZEN_OCEAN);
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(preset.value().parameters()
                .values().stream().map(entry -> Pair.of(entry.getFirst(),
                        entry.getSecond().is(Biomes.FROZEN_OCEAN) ? replacement : entry.getSecond())).toList()));
    }

    public static void attach(ServerLevel level, TerrainOptions options) throws Exception {
        attach(level, level.getChunkSource().getGenerator(), level.getBiomeManager(), options);
    }

    static synchronized void attach(Object level, Object generator, BiomeManager manager,
                                    TerrainOptions options) throws Exception {
        detach(level);
        int offset = (int) options.number("general.snow_start_offset");
        var resolver = new TemperatureBiomes(offset);
        hook("registerWorld", new Class<?>[]{Object.class, Object.class, int.class, int.class},
                level, generator,
                ((Number) options.advanced().getOrDefault("oceans.monument_offset", 0)).intValue(), offset);
        try {
            hook("registerBiomeManager", new Class<?>[]{Object.class, Function.class}, manager, resolver);
            WORLDS.put(level, new WorldClimate(generator, manager, resolver));
        } catch (Exception failure) {
            hook("unregisterWorld", new Class<?>[]{Object.class, Object.class}, level, generator);
            throw failure;
        }
    }

    public static void detach(ServerLevel level) throws Exception {
        detach((Object) level);
    }

    static synchronized void detach(Object level) throws Exception {
        WorldClimate climate = WORLDS.remove(level);
        if (climate == null) return;
        try {
            hook("unregisterBiomeManager", new Class<?>[]{Object.class}, climate.manager());
        } finally {
            try {
                hook("unregisterWorld", new Class<?>[]{Object.class, Object.class}, level, climate.generator());
            } finally {
                climate.resolver().close();
            }
        }
    }

    public static synchronized void close() throws Exception {
        Exception failure = null;
        for (Object level : new ArrayList<>(WORLDS.keySet())) {
            try {
                detach(level);
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    static Biome copy(Biome original, Biome.ClimateSettings climate) throws ReflectiveOperationException {
        Constructor<Biome> constructor = Biome.class.getDeclaredConstructor(Biome.ClimateSettings.class,
                EnvironmentAttributeMap.class, BiomeSpecialEffects.class, BiomeGenerationSettings.class);
        constructor.setAccessible(true);
        return constructor.newInstance(climate, original.getAttributes(), original.getSpecialEffects(),
                original.getGenerationSettings());
    }

    static Object hook(String name, Class<?>[] signature, Object... values) throws Exception {
        Class<?> type = Class.forName(HOOKS, true, ClassLoader.getSystemClassLoader());
        Method method = type.getMethod(name, signature);
        return method.invoke(null, values);
    }

    private static final class TemperatureBiomes implements Function<Object, Object> {
        private final int offset;
        private final Map<Holder<Biome>, Holder<Biome>> copies = new IdentityHashMap<>();
        private boolean closed;

        private TemperatureBiomes(int offset) {
            this.offset = offset;
        }

        @Override
        public synchronized Object apply(Object value) {
            if (value instanceof TemperatureHolder temperature) {
                if (!closed && temperature.owner == this) return value;
                value = temperature.original;
            }
            if (closed || offset == 0) return value;
            @SuppressWarnings("unchecked") Holder<Biome> holder = (Holder<Biome>) value;
            return copies.computeIfAbsent(holder, original -> {
                try {
                    Biome biome = copy(original.value(), original.value().climateSettings);
                    hook("registerBiome", new Class<?>[]{Object.class, int.class}, biome, offset);
                    return new TemperatureHolder(this, original, biome);
                } catch (Exception failure) {
                    throw new IllegalStateException("Cannot apply Tectonic world temperature", failure);
                }
            });
        }

        private synchronized void close() throws Exception {
            closed = true;
            try {
                for (Holder<Biome> biome : copies.values()) {
                    hook("unregisterBiome", new Class<?>[]{Object.class}, biome.value());
                }
            } finally {
                copies.clear();
            }
        }
    }

    /** Query-only holder; chunk palettes retain registered biome holders and IDs. */
    private static final class TemperatureHolder extends Holder.Reference<Biome> {
        private final TemperatureBiomes owner;
        private final Holder<Biome> original;
        private final Biome value;

        private TemperatureHolder(TemperatureBiomes owner, Holder<Biome> original, Biome value) {
            super(Type.STAND_ALONE, null, original.unwrapKey().orElse(null), value);
            this.owner = owner;
            this.original = original;
            this.value = value;
        }

        @Override public Biome value() { return value; }
        @Override public boolean isBound() { return original.isBound(); }
        @Override public boolean areComponentsBound() { return original.areComponentsBound(); }
        @Override public boolean is(Identifier id) { return original.is(id); }
        @Override public boolean is(ResourceKey<Biome> key) { return original.is(key); }
        @Override public boolean is(Predicate<ResourceKey<Biome>> predicate) { return original.is(predicate); }
        @Override public boolean is(TagKey<Biome> tag) { return original.is(tag); }
        @Override public boolean is(Holder<Biome> holder) { return original.is(holder); }
        @Override public Stream<TagKey<Biome>> tags() { return original.tags(); }
        @Override public DataComponentMap components() { return original.components(); }
        @Override public Either<ResourceKey<Biome>, Biome> unwrap() {
            return original.unwrapKey().<Either<ResourceKey<Biome>, Biome>>map(Either::left)
                    .orElseGet(() -> Either.right(value));
        }
        @Override public Optional<ResourceKey<Biome>> unwrapKey() { return original.unwrapKey(); }
        @Override public Kind kind() { return original.kind(); }
        @Override public boolean canSerializeIn(HolderOwner<Biome> owner) { return original.canSerializeIn(owner); }
    }
}
