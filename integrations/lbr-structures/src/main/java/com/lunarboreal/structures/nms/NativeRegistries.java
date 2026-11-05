package com.lunarboreal.structures.nms;

import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Append-only registration before datapack decoding. No vanilla entry is replaced. */
public final class NativeRegistries {
    private NativeRegistries() {}
    private static final RegistrationInfo INFO = new RegistrationInfo(Optional.empty(), Lifecycle.stable());

    public static void register() {
        registerModule("com.yungnickyoung.minecraft.yungsapi.module.StructureTypeModule", BuiltInRegistries.STRUCTURE_TYPE);
        registerModule("com.yungnickyoung.minecraft.yungsapi.module.StructurePoolElementTypeModule", BuiltInRegistries.STRUCTURE_POOL_ELEMENT);
        registerModule("com.yungnickyoung.minecraft.yungsapi.module.StructurePlacementTypeModule", BuiltInRegistries.STRUCTURE_PLACEMENT);
        registerModule("com.yungnickyoung.minecraft.betterwitchhuts.module.StructureProcessorTypeModule", BuiltInRegistries.STRUCTURE_PROCESSOR);
        for (String family : new String[]{"bettermineshafts", "betterdungeons"}) {
            registerModule("com.yungnickyoung.minecraft." + family + ".module.StructureTypeModule", BuiltInRegistries.STRUCTURE_TYPE);
            registerModule("com.yungnickyoung.minecraft." + family + ".module.StructurePieceTypeModule", BuiltInRegistries.STRUCTURE_PIECE);
        }
        for (String family : new String[]{"betterdungeons", "betterstrongholds", "betterjungletemples", "betteroceanmonuments", "betterfortresses"})
            registerModule("com.yungnickyoung.minecraft." + family + ".module.StructureProcessorTypeModule", BuiltInRegistries.STRUCTURE_PROCESSOR);
        registerModule("com.yungnickyoung.minecraft.betterdeserttemples.module.StructureProcessorModule", BuiltInRegistries.STRUCTURE_PROCESSOR);
        registerModule("com.yungnickyoung.minecraft.betterendisland.module.StructureProcessorTypeModule", BuiltInRegistries.STRUCTURE_PROCESSOR);
        for (String family : new String[]{"betterstrongholds", "betterdeserttemples", "betterjungletemples"})
            registerModule("com.yungnickyoung.minecraft." + family + ".module.StructurePlacementTypeModule", BuiltInRegistries.STRUCTURE_PLACEMENT);
        for (String family : new String[]{"yungsextras", "yungsbridges"}) {
            registerModule("com.yungnickyoung.minecraft." + family + ".module.FeatureModule", BuiltInRegistries.FEATURE_TYPE);
            registerModule("com.yungnickyoung.minecraft." + family + ".module.PlacementModifierTypeModule", BuiltInRegistries.PLACEMENT_MODIFIER_TYPE);
        }
        append(BuiltInRegistries.STRUCTURE_POOL_ELEMENT,
                Map.of(Identifier.parse("lunarcore:processed_single"), ProcessedSinglePoolElement.TYPE));
        append(BuiltInRegistries.FEATURE_TYPE, Map.of(Identifier.parse("lunarcore:end_spike"), com.lunarboreal.structures.end.EndSpikeGenerator.CODEC,
                Identifier.parse("lunarcore:end_gateway"), com.lunarboreal.structures.end.EndGatewayGenerator.CODEC));
        append(BuiltInRegistries.STRUCTURE_PLACEMENT, Map.of(Identifier.parse("lunarcore:guarded"), GuardedPlacement.CODEC));
        append(BuiltInRegistries.FEATURE_TYPE, Map.of(Identifier.parse("lunarcore:guarded_feature"), GuardedFeature.CODEC));
    }

    @SuppressWarnings("unchecked")
    private static <T> void registerModule(String name, Registry<T> registry) {
        try {
            Class<?> type = Class.forName(name);
            String namespace = type.getAnnotation(AutoRegister.class).value();
            Map<Identifier, T> entries = new LinkedHashMap<>();
            for (var field : type.getFields()) {
                var annotation = field.getAnnotation(AutoRegister.class);
                if (annotation != null && Modifier.isStatic(field.getModifiers()))
                    entries.put(Identifier.fromNamespaceAndPath(namespace, annotation.value()), (T) field.get(null));
            }
            append(registry, entries);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot register " + name, e); }
    }

    public static <T> void append(Registry<T> registry, Map<Identifier, T> values) {
        try {
            var additions = new LinkedHashMap<Identifier, T>();
            values.forEach((key, value) -> {
                if (registry.containsKey(key)) {
                    if (registry.getValue(key) != value) throw new IllegalStateException("Conflicting YUNG registry entry: " + key);
                } else additions.put(key, value);
            });
            if (additions.isEmpty()) return;
            if (!(registry instanceof MappedRegistry<T> mapped)) throw new IllegalStateException("Unsupported registry implementation");
            var frozen = PaperAccess.field(MappedRegistry.class, "frozen");
            boolean wasFrozen = frozen.getBoolean(mapped);
            Object tags = PaperAccess.read(mapped, "allTags");
            frozen.setBoolean(mapped, false);
            try {
                additions.forEach((id, value) -> mapped.register(ResourceKey.create(registry.key(), id), value, INFO)
                        .bindComponents(DataComponentMap.EMPTY));
            } finally {
                if (wasFrozen) {
                    var unbound = PaperAccess.field(MappedRegistry.class, "allTags").getType().getDeclaredMethod("unbound");
                    unbound.setAccessible(true);
                    PaperAccess.write(mapped, "allTags", unbound.invoke(null));
                    try { mapped.freeze(); }
                    catch (RuntimeException | Error error) {
                        PaperAccess.write(mapped, "allTags", tags);
                        frozen.setBoolean(mapped, true);
                        throw error;
                    }
                }
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Paper 26.3 registry layout is incompatible", e); }
    }
}
