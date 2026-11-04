package com.lunarboreal.terrain.agent;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class NativeTerrainAgentTest {
    public record Box(int minY) {
        public Box moved(int x, int y, int z) { return new Box(minY + y); }
    }
    public record Position(int y) {
        public Position below(int offset) { return new Position(y - offset); }
    }
    public record SpawnInfo(int seaLevel, String dimension) {}

    @Test void bootstrapBridgeIsVisibleFromAnIsolatedPaperclipLoader() throws Exception {
        assertTrue(NativeTerrainHooks.isInstalled());
        assertNull(NativeTerrainHooks.class.getClassLoader());
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            Class<?> hooks = Class.forName(NativeTerrainHooks.class.getName(), true, isolated);
            assertSame(NativeTerrainHooks.class, hooks);
            assertEquals(true, hooks.getMethod("isInstalled").invoke(null));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(NativeTerrainAgent.class.getName(), false, isolated));
        }
    }

    @Test void monumentPolicyIsScopedAndVanillaIsUnchanged() {
        Object world = new Object(), generator = new Object(), other = new Object();
        NativeTerrainHooks.registerWorld(world, generator, -30, 128);
        Box box = new Box(39);
        assertSame(box, NativeTerrainHooks.monumentBounds(box));
        Object previous = NativeTerrainHooks.enterGenerator(generator);
        assertEquals(new Box(9), NativeTerrainHooks.monumentBounds(box));
        Object nested = NativeTerrainHooks.enterGenerator(other);
        assertSame(box, NativeTerrainHooks.monumentBounds(box));
        NativeTerrainHooks.leave(nested);
        assertEquals(new Box(9), NativeTerrainHooks.monumentBounds(box));
        NativeTerrainHooks.leave(previous);
        assertSame(box, NativeTerrainHooks.monumentBounds(box));
        NativeTerrainHooks.unregisterWorld(world, generator);
    }

    @Test void snowlineAndSpawnPacketUseTheSameOffset() {
        Object world = new Object(), generator = new Object(), biome = new Object();
        NativeTerrainHooks.registerWorld(world, generator, 0, 512);
        NativeTerrainHooks.registerBiome(biome, 512);
        assertEquals(new Position(88), NativeTerrainHooks.temperaturePosition(biome, new Position(600)));
        assertEquals(new SpawnInfo(575, "terrain"),
                NativeTerrainHooks.adjustSpawnInfo(world, new SpawnInfo(63, "terrain")));
        Position untouched = new Position(600);
        assertSame(untouched, NativeTerrainHooks.temperaturePosition(new Object(), untouched));
        NativeTerrainHooks.unregisterBiome(biome);
        NativeTerrainHooks.unregisterWorld(world, generator);
    }

    @Test void blendingRetainsTheIdentityOfEachSavedChunk() {
        Object first = new Object(), second = new Object();
        Object firstData = new Object(), secondData = new Object();
        AtomicInteger calls = new AtomicInteger();
        NativeTerrainHooks.registerBlendingHooks((world, tag) -> calls.incrementAndGet(),
                arguments -> arguments[2], (world, tag) -> List.of(world, tag));
        NativeTerrainHooks.afterCopy(first, firstData);
        NativeTerrainHooks.afterCopy(second, secondData);
        assertEquals(List.of(first, "one"), NativeTerrainHooks.afterWrite(firstData, "one"));
        assertEquals(List.of(second, "two"), NativeTerrainHooks.afterWrite(secondData, "two"));
        NativeTerrainHooks.beforeParse(first, "read");
        assertEquals(1, calls.get());
        NativeTerrainHooks.registerBlendingHooks(null, null, null);
    }

    @Test void transformsAllExpectedPaperEntryPoints() throws Exception {
        String[] types = {
                "net/minecraft/world/level/levelgen/structure/Structure",
                "net/minecraft/world/level/levelgen/structure/structures/OceanMonumentPieces$MonumentBuilding",
                "net/minecraft/world/level/chunk/storage/SerializableChunkData",
                "net/minecraft/world/level/biome/Biome",
                "net/minecraft/world/level/biome/BiomeManager",
                "net/minecraft/server/level/WorldGenRegion",
                "net/minecraft/server/level/ServerPlayer",
                "net/minecraft/world/level/chunk/storage/IOWorker",
                "net/minecraft/core/Holder$Reference",
                "net/minecraft/core/HolderSet$Direct",
                "net/minecraft/world/level/levelgen/blending/BlendingData",
                "net/minecraft/world/level/chunk/ChunkAccess",
                "net/minecraft/world/level/levelgen/Heightmap"
        };
        Assumptions.assumeTrue(getClass().getClassLoader().getResource(types[0] + ".class") != null);
        for (String name : types) {
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
                assertNotNull(stream, name);
                byte[] transformed = NativeTerrainAgent.transform(name, stream.readAllBytes(), getClass().getClassLoader());
                assertNotNull(transformed, name);
                AtomicInteger bridgeCalls = new AtomicInteger();
                new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method,
                                    String descriptor, boolean isInterface) {
                                if (owner.endsWith("/NativeTerrainHooks")) bridgeCalls.incrementAndGet();
                            }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
                assertTrue(bridgeCalls.get() > 0, name);
            }
        }
    }

    @Test void realChunkParserWriterAndOldChunkDetectorCallTheInstalledPolicies(@TempDir Path directory) throws Exception {
        nativeBootstrap();
        Object factory = containerFactory();
        Class<?> height = nativeClass("world.level.LevelHeightAccessor");
        Class<?> tagClass = nativeClass("nbt.CompoundTag");
        Object tag = tagClass.getConstructor().newInstance();
        AtomicInteger parsed = new AtomicInteger(), written = new AtomicInteger(), detected = new AtomicInteger();
        NativeTerrainHooks.registerBlendingHooks((world, data) -> {
            assertNull(world);
            assertSame(tag, data);
            parsed.incrementAndGet();
        }, arguments -> {
            detected.incrementAndGet();
            return true;
        }, (world, data) -> {
            written.incrementAndGet();
            return data;
        });
        try {
            Class<?> serialized = nativeClass("world.level.chunk.storage.SerializableChunkData");
            assertNull(serialized.getMethod("parse", height,
                    nativeClass("world.level.chunk.PalettedContainerFactory"), tagClass).invoke(null, null, factory, tag));
            assertEquals(1, parsed.get());
            Object position = nativeClass("world.level.ChunkPos").getConstructor(int.class, int.class).newInstance(0, 0);
            Object packedTicks = nativeClass("world.level.chunk.ChunkAccess$PackedTicks")
                    .getConstructor(List.class, List.class).newInstance(List.of(), List.of());
            Object chunkData = serialized.getConstructors()[0].newInstance(factory, position, -4, 0L, 0L,
                    nativeClass("world.level.chunk.status.ChunkStatus").getField("EMPTY").get(null), null, null,
                    nativeClass("world.level.chunk.UpgradeData").getField("EMPTY").get(null),
                    java.util.Map.of(), packedTicks,
                    Array.newInstance(Class.forName("it.unimi.dsi.fastutil.shorts.ShortList"), 0),
                    false, List.of(), List.of(), List.of(), tagClass.getConstructor().newInstance(), null);
            NativeTerrainHooks.afterCopy(factory, chunkData);
            assertNotNull(serialized.getMethod("write").invoke(chunkData));
            assertEquals(1, written.get());
            Class<?> worker = nativeClass("world.level.chunk.storage.IOWorker");
            Method isOld = worker.getDeclaredMethod("isOldChunk", tagClass);
            isOld.setAccessible(true);
            Class<?> storage = nativeClass("world.level.chunk.storage.RegionStorageInfo");
            Object info = storage.getConstructor(String.class, nativeClass("resources.ResourceKey"), String.class)
                    .newInstance("test", nativeClass("world.level.Level").getField("OVERWORLD").get(null), "chunk");
            var constructor = worker.getDeclaredConstructor(storage, Path.class, boolean.class);
            constructor.setAccessible(true);
            try (AutoCloseable instance = (AutoCloseable) constructor.newInstance(info, directory, false)) {
                assertEquals(true, isOld.invoke(instance, tag));
            }
            assertEquals(1, detected.get());
        } finally {
            NativeTerrainHooks.registerBlendingHooks(null, null, null);
        }
    }

    @Test void realBlenderRecognizesAdditionalSurfaceBlocksAndShrunkenChunkSections() throws Exception {
        nativeBootstrap();
        Class<?> height = nativeClass("world.level.LevelHeightAccessor");
        Object accessor = height.getMethod("create", int.class, int.class).invoke(null, -64, 384);
        Class<?> chunkPos = nativeClass("world.level.ChunkPos");
        Class<?> upgrade = nativeClass("world.level.chunk.UpgradeData");
        Class<?> blending = nativeClass("world.level.levelgen.blending.BlendingData");
        Class<?> chunk = nativeClass("world.level.chunk.ProtoChunk");
        Object proto = chunk.getConstructor(chunkPos, upgrade, height,
                nativeClass("world.level.chunk.PalettedContainerFactory"), blending)
                .newInstance(chunkPos.getConstructor(int.class, int.class).newInstance(0, 0),
                        upgrade.getField("EMPTY").get(null), accessor, containerFactory(), null);
        Class<?> blockPos = nativeClass("core.BlockPos");
        Object position = blockPos.getConstructor(int.class, int.class, int.class).newInstance(0, 70, 0);
        Object blackstone = nativeClass("world.level.block.Blocks").getField("BLACKSTONE").get(null);
        Object state = blackstone.getClass().getMethod("defaultBlockState").invoke(blackstone);
        chunk.getMethod("setBlockState", blockPos, nativeClass("world.level.block.state.BlockState"), int.class)
                .invoke(proto, position, state, 0);
        Class<?> packed = nativeClass("world.level.levelgen.blending.BlendingData$Packed");
        Object blender = blending.getMethod("unpack", packed).invoke(null,
                packed.getConstructor(int.class, int.class, Optional.class).newInstance(-4, 20, Optional.empty()));
        Method getHeight = blending.getDeclaredMethod("getHeightAtXZ", nativeClass("world.level.chunk.ChunkAccess"),
                int.class, int.class);
        getHeight.setAccessible(true);
        assertEquals(-64, getHeight.invoke(blender, proto, 0, 0));
        NativeTerrainHooks.registerChunkCompatibilityHooks((access, block) -> access == proto && block == blackstone,
                access -> false);
        try {
            assertEquals(70, getHeight.invoke(blender, proto, 0, 0));
        } finally {
            NativeTerrainHooks.registerChunkCompatibilityHooks(null, null);
        }
        assertEquals(-64, getHeight.invoke(blender, proto, 0, 0));
        Class<?> list = Class.forName("it.unimi.dsi.fastutil.shorts.ShortList");
        Object sections = Array.newInstance(list, 2);
        Method offsetList = nativeClass("world.level.chunk.ChunkAccess")
                .getMethod("getOrCreateOffsetList", sections.getClass(), int.class);
        Object valid = offsetList.invoke(null, sections, 1);
        assertSame(valid, Array.get(sections, 1));
        assertNotSame(offsetList.invoke(null, sections, 5), offsetList.invoke(null, sections, 5));
        assertNotNull(offsetList.invoke(null, sections, -1));
    }

    private static Object containerFactory() throws Exception {
        Object lookup = Class.forName("net.minecraft.data.registries.VanillaRegistries")
                .getMethod("createWorldLookup").invoke(null);
        Class<?> provider = nativeClass("core.HolderLookup$Provider");
        Object biomeLookup = provider.getMethod("lookupOrThrow", nativeClass("resources.ResourceKey"))
                .invoke(lookup, nativeClass("core.registries.Registries").getField("BIOME").get(null));
        Object biome = nativeClass("core.HolderGetter")
                .getMethod("getOrThrow", nativeClass("resources.ResourceKey"))
                .invoke(biomeLookup, nativeClass("world.level.biome.Biomes").getField("PLAINS").get(null));
        Class<?> idMap = nativeClass("core.IdMap");
        Class<?> idMapper = nativeClass("core.IdMapper");
        Object biomeIds = idMapper.getConstructor().newInstance();
        idMapper.getMethod("add", Object.class).invoke(biomeIds, biome);
        Class<?> strategy = nativeClass("world.level.chunk.Strategy");
        Object blocks = strategy.getMethod("createForBlockStates", idMap).invoke(null,
                nativeClass("world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null));
        Object biomes = strategy.getMethod("createForBiomes", idMap).invoke(null, biomeIds);
        Object air = nativeClass("world.level.block.Blocks").getField("AIR").get(null);
        Object airState = air.getClass().getMethod("defaultBlockState").invoke(air);
        return nativeClass("world.level.chunk.PalettedContainerFactory").getConstructors()[0]
                .newInstance(blocks, airState, null, biomes, biome, null, null);
    }

    private static Class<?> nativeClass(String name) throws ClassNotFoundException {
        return Class.forName("net.minecraft." + name);
    }

    private static void nativeBootstrap() throws Exception {
        Assumptions.assumeTrue(NativeTerrainHooks.isInstalled());
        nativeClass("SharedConstants").getMethod("tryDetectVersion").invoke(null);
        nativeClass("server.Bootstrap").getMethod("bootStrap").invoke(null);
        NativeTerrainHooks.requireInstalledHooks(NativeTerrainAgentTest.class.getClassLoader());
    }

    @Test void realMonumentAndReloadRegenerationKeepTheOffset() throws Exception {
        Assumptions.assumeTrue(NativeTerrainHooks.isInstalled());
        Class<?> bootstrap;
        try {
            bootstrap = Class.forName("net.minecraft.server.Bootstrap");
        } catch (ClassNotFoundException unavailable) {
            Assumptions.abort("Paper native test classpath is unavailable");
            return;
        }
        Class<?> version = Class.forName("net.minecraft.SharedConstants");
        version.getMethod("tryDetectVersion").invoke(null);
        bootstrap.getMethod("bootStrap").invoke(null);
        Class<?> randomSource = Class.forName("net.minecraft.util.RandomSource");
        Class<?> direction = Class.forName("net.minecraft.core.Direction");
        Object north = direction.getField("NORTH").get(null);
        Class<?> building = Class.forName(
                "net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces$MonumentBuilding");
        Object world = new Object(), generator = new Object();
        NativeTerrainHooks.registerWorld(world, generator, -30, 0);
        Object previous = NativeTerrainHooks.enterGenerator(generator);
        Object monument;
        try {
            monument = building.getConstructor(randomSource, int.class, int.class, direction)
                    .newInstance(randomSource.getMethod("create", long.class).invoke(null, 123456789L), 0, 0, north);
        } finally {
            NativeTerrainHooks.leave(previous);
        }
        assertEquals(9, minY(monument));
        Field childField = building.getDeclaredField("childPieces");
        childField.setAccessible(true);
        List<?> children = (List<?>) childField.get(monument);
        assertFalse(children.isEmpty());
        assertTrue(children.stream().allMatch(child -> minY(child) >= 9), "All rooms use the shifted parent");
        Class<?> container = Class.forName("net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer");
        Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
        Class<?> structure = Class.forName("net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure");
        Object pieces = container.getConstructor(List.class).newInstance(List.of(monument));
        Object chunk = chunkPos.getConstructor(int.class, int.class).newInstance(0, 0);
        previous = NativeTerrainHooks.enterWorld(world);
        Object restored;
        try {
            restored = structure.getMethod("regeneratePiecesAfterLoad", chunkPos, long.class, container)
                    .invoke(null, chunk, 123456789L, pieces);
        } finally {
            NativeTerrainHooks.leave(previous);
        }
        List<?> restoredPieces = (List<?>) container.getMethod("pieces").invoke(restored);
        assertEquals(9, minY(restoredPieces.getFirst()));
        Object vanilla = building.getConstructor(randomSource, int.class, int.class, direction)
                .newInstance(randomSource.getMethod("create", long.class).invoke(null, 123456789L), 0, 0, north);
        assertEquals(39, minY(vanilla));
        List<?> vanillaChildren = (List<?>) childField.get(vanilla);
        assertEquals(vanillaChildren.size(), children.size());
        for (int i = 0; i < children.size(); i++) {
            assertEquals(minY(vanillaChildren.get(i)) - 30, minY(children.get(i)), "Room " + i);
        }
        NativeTerrainHooks.unregisterWorld(world, generator);
    }

    private static int minY(Object piece) {
        try {
            Object box = piece.getClass().getMethod("getBoundingBox").invoke(piece);
            return (Integer) box.getClass().getMethod("minY").invoke(box);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
