package com.lunarboreal.terrain.nms;

import com.lunarboreal.terrain.TerrainNamespace;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.Map;

/** Server-side codecs used only by the isolated terrain world's saved generation graph. */
public final class NativeTectonicFunctions {
    private static final Identifier SLOPE = Identifier.fromNamespaceAndPath(TerrainNamespace.CURRENT, "slope");
    private static final Identifier SEEDED_NOISE = Identifier.fromNamespaceAndPath(TerrainNamespace.CURRENT, "seeded_noise");
    private static final Identifier CONFIG_NOISE = Identifier.fromNamespaceAndPath(TerrainNamespace.CURRENT, "config_noise");
    private static final Codec<InclusiveRange<Integer>> INTEGER_RANGE = Codec.withAlternative(
            InclusiveRange.INT,
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("min_inclusive").orElse(Integer.MIN_VALUE)
                            .forGetter(InclusiveRange::minInclusive),
                    Codec.INT.fieldOf("max_inclusive").orElse(Integer.MAX_VALUE)
                            .forGetter(InclusiveRange::maxInclusive)
            ).apply(instance, InclusiveRange::new)));

    private NativeTectonicFunctions() {}

    /** Existing codecs may belong to the previous module class loader after a module reload. */
    public static void registerTypes() throws Exception {
        NativeTectonicBridge.appendEntries(BuiltInRegistries.MATERIAL_CONDITION_TYPE,
                Map.of(ResourceKey.create(Registries.MATERIAL_CONDITION_TYPE, SLOPE), SlopeCondition.CODEC,
                        ResourceKey.create(Registries.MATERIAL_CONDITION_TYPE, legacy(SLOPE)),
                        SlopeCondition.CODEC.xmap(value -> value, value -> value)));
        NativeTectonicBridge.appendEntries(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Map.of(
                ResourceKey.create(Registries.DENSITY_FUNCTION_TYPE, SEEDED_NOISE), SeededNoiseFunction.CODEC,
                ResourceKey.create(Registries.DENSITY_FUNCTION_TYPE, CONFIG_NOISE), ConfigNoiseFunction.CODEC,
                ResourceKey.create(Registries.DENSITY_FUNCTION_TYPE, legacy(SEEDED_NOISE)),
                SeededNoiseFunction.CODEC.xmap(value -> value, value -> value),
                ResourceKey.create(Registries.DENSITY_FUNCTION_TYPE, legacy(CONFIG_NOISE)),
                ConfigNoiseFunction.CODEC.xmap(value -> value, value -> value)));
    }

    private static Identifier legacy(Identifier current) {
        return Identifier.fromNamespaceAndPath(TerrainNamespace.LEGACY, current.getPath());
    }

    static Identifier seedAlias(Identifier original) {
        if (original.getNamespace().equals("tectonic") && original.getPath().startsWith("parameter/")) {
            return Identifier.withDefaultNamespace(original.getPath().substring("parameter/".length()));
        }
        return original;
    }

    private static Noise noise(Holder<NormalNoise> holder, DensityFunction.CompileContext context) {
        var key = holder.unwrapKey();
        if (key.isPresent()) {
            Identifier original = key.get().identifier();
            Identifier alias = seedAlias(original);
            if (!alias.equals(original)) return holder.value().create(context.createRandom(alias));
        }
        return context.createNoiseSampler(holder);
    }

    /** Lithostitched 26.3 checks each absolute gradient independently, including finite bounds. */
    public record SlopeCondition(InclusiveRange<Integer> heightDifference) implements MaterialCondition {
        public static final MapCodec<SlopeCondition> CODEC = INTEGER_RANGE.fieldOf("height_difference")
                .orElse(new InclusiveRange<>(4, Integer.MAX_VALUE))
                .xmap(SlopeCondition::new, SlopeCondition::heightDifference);

        @Override public ConditionEvaluator compile(MaterialRuleContext context) {
            return new MaterialRuleContext.LazyXZCondition(context) {
                @Override protected boolean compute() {
                    return heightDifference.isValueInRange(Math.abs(context.surfaceGradientX()))
                            || heightDifference.isValueInRange(Math.abs(context.surfaceGradientZ()));
                }
            };
        }

        @Override public MapCodec<SlopeCondition> codec() { return CODEC; }
    }

    /** Retains native NoiseFunction sampling while applying Tectonic's parameter seed aliases. */
    public record SeededNoiseFunction(NoiseFunction source) implements DensityFunction {
        public static final MapCodec<SeededNoiseFunction> CODEC =
                NoiseFunction.CODEC.xmap(SeededNoiseFunction::new, SeededNoiseFunction::source);

        @Override public DensitySampler compileSampler(CompileContext context) {
            Noise instance = noise(source.noise(), context);
            return source.compileSampler(new CompileContext() {
                @Override public Noise createNoiseSampler(Holder<NormalNoise> holder) {
                    return holder.equals(source.noise()) ? instance : context.createNoiseSampler(holder);
                }

                @Override public RandomSource createRandom(Identifier name) { return context.createRandom(name); }
                @Override public RandomSource createEndIslandRandom() { return context.createEndIslandRandom(); }
            });
        }

        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
            NoiseFunction rewritten = (NoiseFunction) source.rewriteChildren(rule);
            return rewritten == source ? this : new SeededNoiseFunction(rewritten);
        }

        @Override public Interval range() { return source.range(); }
        @Override public int domainAxes() { return source.domainAxes(); }
        @Override public MapCodec<SeededNoiseFunction> codec() { return CODEC; }
    }

    /** Preserves the pinned mod's float point arithmetic and its alternate volume sampling. */
    public record ConfigNoiseFunction(NoiseFunction source, float multiplier, float offset, boolean smooth)
            implements DensityFunction {
        public static final MapCodec<ConfigNoiseFunction> CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        NoiseFunction.CODEC.forGetter(ConfigNoiseFunction::source),
                        Codec.FLOAT.optionalFieldOf("multiplier", 1.0f).forGetter(ConfigNoiseFunction::multiplier),
                        Codec.FLOAT.optionalFieldOf("offset", 0.0f).forGetter(ConfigNoiseFunction::offset),
                        Codec.BOOL.optionalFieldOf("smooth", false).forGetter(ConfigNoiseFunction::smooth)
                ).apply(instance, ConfigNoiseFunction::new));

        @Override public DensitySampler compileSampler(CompileContext context) {
            return new ConfigSampler(noise(source.noise(), context),
                    source.shiftX().compileSampler(context), source.shiftZ().compileSampler(context),
                    (float) source.xzScale(), multiplier, offset, smooth);
        }

        @Override public DensityFunction rewriteChildren(DfRewriteRule rule) {
            NoiseFunction rewritten = (NoiseFunction) source.rewriteChildren(rule);
            return rewritten == source ? this : new ConfigNoiseFunction(rewritten, multiplier, offset, smooth);
        }

        @Override public Interval range() {
            Interval scaled = Interval.mul(source.range(), Interval.ofExact(multiplier));
            Interval shifted = Interval.add(scaled, Interval.ofExact(offset));
            // Smooth volume sampling omits the offset; point sampling still includes it.
            return smooth ? Interval.encapsulating(scaled, shifted) : shifted;
        }

        @Override public int domainAxes() {
            return AXIS_X | AXIS_Z | source.shiftX().domainAxes() | source.shiftZ().domainAxes();
        }

        @Override public MapCodec<ConfigNoiseFunction> codec() { return CODEC; }
    }

    private record ConfigSampler(Noise noise, DensitySampler shiftX, DensitySampler shiftZ,
                                 float scale, float multiplier, float offset, boolean smooth) implements DensitySampler {
        @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
            double shiftedX = x * scale + shiftX.sampleValue(context, x, y, z);
            double shiftedZ = z * scale + shiftZ.sampleValue(context, x, y, z);
            return noise.get(shiftedX, 0.0, shiftedZ) * multiplier + offset;
        }

        @Override public void sampleVolume(SamplerContext context, DensityBuffer target, DensityVolume volume) {
            shiftX.sampleVolume(context, target, volume);
            try (var zShift = context.acquireBuffer(volume)) {
                shiftZ.sampleVolume(context, zShift, volume);
                int index = 0;
                for (int z = 0; z < volume.sizeZ(); z++) {
                    double blockZ = volume.blockZ(z);
                    for (int x = 0; x < volume.sizeX(); x++) {
                        double blockX = volume.blockX(x);
                        for (int y = 0; y < volume.sizeY(); y++) {
                            double shiftedX = smooth ? (blockX + target.get(index)) * scale
                                    : blockX * scale + target.get(index);
                            double shiftedZ = smooth ? (blockZ + zShift.get(index)) * scale
                                    : blockZ * scale + zShift.get(index);
                            float sample = noise.get(shiftedX, 0.0, shiftedZ) * multiplier;
                            target.set(index++, smooth ? sample : sample + offset);
                        }
                    }
                }
            }
        }
    }
}
