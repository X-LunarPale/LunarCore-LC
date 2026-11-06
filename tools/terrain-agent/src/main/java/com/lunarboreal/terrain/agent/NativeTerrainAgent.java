package com.lunarboreal.terrain.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/** Startup hooks for Paper's otherwise inaccessible temperature and structure lifecycle. */
public final class NativeTerrainAgent {
    private static JarFile bootstrapBridge;
    private static final String PREFIX = "net/minecraft/";
    private static final String HOOK = "com/lunarboreal/terrain/agent/NativeTerrainHooks";
    private static final Type HOOK_TYPE = Type.getObjectType(HOOK);
    private static final Type OBJECT = Type.getType(Object.class);
    private static final String STRUCTURE = PREFIX + "world/level/levelgen/structure/Structure";
    private static final String MONUMENT = PREFIX + "world/level/levelgen/structure/structures/OceanMonumentPieces$MonumentBuilding";
    private static final String CHUNK = PREFIX + "world/level/chunk/storage/SerializableChunkData";
    private static final String BIOME = PREFIX + "world/level/biome/Biome";
    private static final String BIOME_MANAGER = PREFIX + "world/level/biome/BiomeManager";
    private static final String REGION = PREFIX + "server/level/WorldGenRegion";
    private static final String PLAYER = PREFIX + "server/level/ServerPlayer";
    private static final String IO_WORKER = PREFIX + "world/level/chunk/storage/IOWorker";
    private static final String HOLDER = PREFIX + "core/Holder$Reference";
    private static final String DIRECT_HOLDERS = PREFIX + "core/HolderSet$Direct";
    private static final String BLENDING = PREFIX + "world/level/levelgen/blending/BlendingData";
    private static final String CHUNK_ACCESS = PREFIX + "world/level/chunk/ChunkAccess";
    private static final String HEIGHTMAP = PREFIX + "world/level/levelgen/Heightmap";
    private static final Map<String, Set<String>> REQUIRED = Map.ofEntries(
            Map.entry(STRUCTURE, Set.of("generate")),
            Map.entry(MONUMENT, Set.of("bounds")),
            Map.entry(CHUNK, Set.of("read", "parse", "copyOf", "write")),
            Map.entry(BIOME, Set.of("temperature")),
            Map.entry(BIOME_MANAGER, Set.of("getBiome", "getNoiseBiomeAtPosition", "getNoiseBiomeAtQuart")),
            Map.entry(REGION, Set.of("biomeManager")),
            Map.entry(PLAYER, Set.of("spawnInfo")),
            Map.entry(IO_WORKER, Set.of("oldChunk")),
            Map.entry(HOLDER, Set.of("biomeAlias")),
            Map.entry(DIRECT_HOLDERS, Set.of("biomeSetAlias")),
            Map.entry(BLENDING, Set.of("surface")),
            Map.entry(CHUNK_ACCESS, Set.of("postProcessing")),
            Map.entry(HEIGHTMAP, Set.of("heightWarning")));

    private NativeTerrainAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        installBootstrapBridge(instrumentation);
        instrumentation.addTransformer(new Transformer());
        NativeTerrainHooks.installed();
        System.out.println("[LC Terrain] Native world hooks installed");
    }

    private static void installBootstrapBridge(Instrumentation instrumentation) {
        try (InputStream resource = NativeTerrainAgent.class.getResourceAsStream("/META-INF/LC-Terrain-Bridge.jar")) {
            if (resource == null) throw new IOException("Missing LC-Terrain-Bridge.jar");
            Path extracted = Files.createTempFile("lc-terrain-bridge-", ".jar");
            extracted.toFile().deleteOnExit();
            Files.copy(resource, extracted, StandardCopyOption.REPLACE_EXISTING);
            bootstrapBridge = new JarFile(extracted.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapBridge);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the native terrain bridge", failure);
        }
    }

    public static void requireTransformations(ClassLoader loader) {
        for (String name : REQUIRED.keySet()) {
            try {
                Class.forName(name.replace('/', '.'), false, loader);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Paper native terrain class is unavailable: " + name, failure);
            }
            if (!NativeTerrainHooks.hasTransformation(name)) {
                throw new IllegalStateException("Paper native terrain hooks are unavailable: " + name
                        + "; restart with the current LC-Terrain-Agent.jar");
            }
        }
    }

    static byte[] transform(String name, byte[] bytes, ClassLoader loader) {
        if (!REQUIRED.containsKey(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        Hierarchy hierarchy = new Hierarchy(loader);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String first, String second) {
                return hierarchy.common(first, second);
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private final Set<String> hooks = new java.util.HashSet<>();

            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                if (name.equals(STRUCTURE) && methodName.equals("generate")
                        && descriptor.contains("Lnet/minecraft/world/level/chunk/ChunkGenerator;")) {
                    hooks.add("generate");
                    return scoped(visitor, access, methodName, descriptor, 3, "enterGenerator");
                }
                if (name.equals(CHUNK) && methodName.equals("read")
                        && descriptor.startsWith("(Lnet/minecraft/server/level/ServerLevel;")) {
                    hooks.add("read");
                    return scoped(visitor, access, methodName, descriptor, 0, "enterWorld");
                }
                return new AdviceAdapter(Opcodes.ASM9, visitor, access, methodName, descriptor) {
                    @Override protected void onMethodEnter() {
                        if (name.equals(BIOME) && methodName.equals("getHeightAdjustedTemperature")
                                && descriptor.equals("(Lnet/minecraft/core/BlockPos;I)F")) {
                            loadThis();
                            loadArg(0);
                            invokeStatic(HOOK_TYPE, new Method("temperaturePosition",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                            checkCast(Type.getObjectType(PREFIX + "core/BlockPos"));
                            storeArg(0);
                            hooks.add("temperature");
                        } else if (name.equals(CHUNK) && methodName.equals("parse")
                                && descriptor.startsWith("(Lnet/minecraft/world/level/LevelHeightAccessor;")) {
                            loadArg(0);
                            loadArg(2);
                            invokeStatic(HOOK_TYPE, new Method("beforeParse", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                            hooks.add("parse");
                        } else if (name.equals(CHUNK_ACCESS) && methodName.equals("getOrCreateOffsetList")
                                && descriptor.equals("([Lit/unimi/dsi/fastutil/shorts/ShortList;I)"
                                    + "Lit/unimi/dsi/fastutil/shorts/ShortList;")) {
                            Label valid = new Label();
                            loadArg(0);
                            loadArg(1);
                            invokeStatic(HOOK_TYPE, new Method("outsideSections", "([Ljava/lang/Object;I)Z"));
                            ifZCmp(EQ, valid);
                            Type list = Type.getObjectType("it/unimi/dsi/fastutil/shorts/ShortArrayList");
                            newInstance(list);
                            dup();
                            invokeConstructor(list, new Method("<init>", "()V"));
                            returnValue();
                            mark(valid);
                            hooks.add("postProcessing");
                        }
                    }

                    @Override public void visitMethodInsn(int opcode, String owner, String called,
                            String calledDescriptor, boolean isInterface) {
                        if (name.equals(BLENDING) && methodName.equals("getHeightAtXZ")
                                && owner.equals("java/util/List") && called.equals("contains")
                                && calledDescriptor.equals("(Ljava/lang/Object;)Z")) {
                            loadArg(0);
                            invokeStatic(HOOK_TYPE, new Method("isSurfaceBlock",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"));
                            hooks.add("surface");
                            return;
                        }
                        if (name.equals(HEIGHTMAP) && methodName.equals("setRawData")
                                && owner.equals("org/slf4j/Logger") && called.equals("warn")
                                && calledDescriptor.equals("(Ljava/lang/String;[Ljava/lang/Object;)V")) {
                            Label log = new Label();
                            Label done = new Label();
                            loadArg(0);
                            invokeStatic(HOOK_TYPE, new Method("suppressHeightmapWarning", "(Ljava/lang/Object;)Z"));
                            ifZCmp(EQ, log);
                            pop();
                            pop2();
                            goTo(done);
                            mark(log);
                            super.visitMethodInsn(opcode, owner, called, calledDescriptor, isInterface);
                            mark(done);
                            hooks.add("heightWarning");
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, called, calledDescriptor, isInterface);
                        if (name.equals(MONUMENT) && methodName.equals("<init>") && called.equals("makeBoundingBox")
                                && calledDescriptor.endsWith("Lnet/minecraft/world/level/levelgen/structure/BoundingBox;")) {
                            super.visitMethodInsn(INVOKESTATIC, HOOK, "monumentBounds",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(CHECKCAST, PREFIX + "world/level/levelgen/structure/BoundingBox");
                            hooks.add("bounds");
                        }
                    }

                    @Override protected void onMethodExit(int opcode) {
                        if (opcode == IRETURN && name.equals(HOLDER) && methodName.equals("is")
                                && descriptor.equals("(Lnet/minecraft/resources/ResourceKey;)Z")) {
                            int original = newLocal(Type.BOOLEAN_TYPE);
                            storeLocal(original);
                            loadThis();
                            loadArg(0);
                            loadLocal(original);
                            invokeStatic(HOOK_TYPE, new Method("matchesBiomeAlias",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
                            hooks.add("biomeAlias");
                        } else if (opcode == IRETURN && name.equals(DIRECT_HOLDERS)
                                && methodName.equals("contains")
                                && descriptor.equals("(Lnet/minecraft/core/Holder;)Z")) {
                            int original = newLocal(Type.BOOLEAN_TYPE);
                            storeLocal(original);
                            loadThis();
                            loadArg(0);
                            loadLocal(original);
                            invokeStatic(HOOK_TYPE, new Method("matchesBiomeSet",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
                            hooks.add("biomeSetAlias");
                        } else if (opcode == RETURN && name.equals(REGION) && methodName.equals("<init>")) {
                            loadThis();
                            invokeStatic(HOOK_TYPE, new Method("inheritBiomeManager", "(Ljava/lang/Object;)V"));
                            hooks.add("biomeManager");
                        } else if (opcode == ARETURN && name.equals(BIOME_MANAGER)
                                && (methodName.equals("getBiome") || methodName.equals("getNoiseBiomeAtPosition")
                                    || methodName.equals("getNoiseBiomeAtQuart"))
                                && Type.getReturnType(descriptor).getSort() == Type.OBJECT) {
                            loadThis();
                            swap();
                            invokeStatic(HOOK_TYPE, new Method("resolveBiome",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                            checkCast(Type.getReturnType(descriptor));
                            hooks.add(methodName);
                        } else if (opcode == ARETURN && name.equals(PLAYER) && methodName.equals("createCommonSpawnInfo")) {
                            int result = newLocal(OBJECT);
                            storeLocal(result);
                            loadArg(0);
                            loadLocal(result);
                            invokeStatic(HOOK_TYPE, new Method("adjustSpawnInfo",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                            checkCast(Type.getReturnType(descriptor));
                            hooks.add("spawnInfo");
                        } else if (opcode == ARETURN && name.equals(CHUNK) && methodName.equals("copyOf")) {
                            dup();
                            loadArg(0);
                            swap();
                            invokeStatic(HOOK_TYPE, new Method("afterCopy", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                            hooks.add("copyOf");
                        } else if (opcode == ARETURN && name.equals(CHUNK) && methodName.equals("write")
                                && descriptor.equals("()Lnet/minecraft/nbt/CompoundTag;")) {
                            loadThis();
                            swap();
                            invokeStatic(HOOK_TYPE, new Method("afterWrite",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                            checkCast(Type.getObjectType(PREFIX + "nbt/CompoundTag"));
                            hooks.add("write");
                        } else if (opcode == IRETURN && name.equals(IO_WORKER) && methodName.equals("isOldChunk")
                                && descriptor.equals("(Lnet/minecraft/nbt/CompoundTag;)Z")) {
                            int result = newLocal(Type.BOOLEAN_TYPE);
                            storeLocal(result);
                            loadThis();
                            invokeVirtual(Type.getObjectType(IO_WORKER),
                                    new Method("storageInfo", "()Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;"));
                            loadArg(0);
                            loadLocal(result);
                            invokeStatic(HOOK_TYPE, new Method("isOldChunk",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z"));
                            hooks.add("oldChunk");
                        }
                    }
                };
            }

            @Override public void visitEnd() {
                if (!hooks.containsAll(REQUIRED.get(name))) {
                    Set<String> missing = new java.util.HashSet<>(REQUIRED.get(name));
                    missing.removeAll(hooks);
                    throw new IllegalStateException("Missing terrain hooks in " + name + ": " + missing);
                }
                NativeTerrainHooks.transformed(name);
                super.visitEnd();
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /** Reading headers avoids recursively loading a class while it is being transformed. */
    private static final class Hierarchy {
        private final ClassLoader loader;
        private final Map<String, ClassReader> readers = new HashMap<>();

        private Hierarchy(ClassLoader loader) { this.loader = loader; }

        private ClassReader reader(String name) {
            return readers.computeIfAbsent(name, key -> {
                try (InputStream stream = loader == null
                        ? ClassLoader.getSystemResourceAsStream(key + ".class")
                        : loader.getResourceAsStream(key + ".class")) {
                    if (stream == null) throw new IllegalStateException("Missing native class " + key);
                    return new ClassReader(stream);
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot read native class " + key, failure);
                }
            });
        }

        private boolean assignable(String base, String value) {
            if (base.equals(value) || base.equals("java/lang/Object")) return true;
            if (value.startsWith("[")) {
                if (base.equals("java/lang/Cloneable") || base.equals("java/io/Serializable")) return true;
                if (!base.startsWith("[")) return false;
                String a = base.substring(1), b = value.substring(1);
                if (a.length() == 1 || b.length() == 1) return a.equals(b);
                return assignable(element(a), element(b));
            }
            if (base.startsWith("[")) return false;
            ClassReader valueReader = reader(value);
            if (valueReader.getSuperName() != null && assignable(base, valueReader.getSuperName())) return true;
            for (String parent : valueReader.getInterfaces()) if (assignable(base, parent)) return true;
            return false;
        }

        private String common(String first, String second) {
            if (assignable(first, second)) return first;
            if (assignable(second, first)) return second;
            if (first.startsWith("[") && second.startsWith("[")) {
                String a = first.substring(1), b = second.substring(1);
                if (a.length() == 1 || b.length() == 1) return "java/lang/Object";
                String common = common(element(a), element(b));
                return common.startsWith("[") ? "[" + common : "[L" + common + ";";
            }
            if (first.startsWith("[") || second.startsWith("[")
                    || (reader(first).getAccess() & Opcodes.ACC_INTERFACE) != 0
                    || (reader(second).getAccess() & Opcodes.ACC_INTERFACE) != 0) return "java/lang/Object";
            String parent = reader(first).getSuperName();
            while (parent != null && !assignable(parent, second)) parent = reader(parent).getSuperName();
            return parent == null ? "java/lang/Object" : parent;
        }

        private static String element(String descriptor) {
            return descriptor.startsWith("L") ? descriptor.substring(1, descriptor.length() - 1) : descriptor;
        }
    }

    private static MethodVisitor scoped(MethodVisitor visitor, int access, String name, String descriptor,
            int argument, String enter) {
        return new AdviceAdapter(Opcodes.ASM9, visitor, access, name, descriptor) {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();
            private int previous;

            @Override protected void onMethodEnter() {
                loadArg(argument);
                invokeStatic(HOOK_TYPE, new Method(enter, "(Ljava/lang/Object;)Ljava/lang/Object;"));
                previous = newLocal(OBJECT);
                storeLocal(previous);
                mark(start);
            }

            @Override protected void onMethodExit(int opcode) {
                if (opcode != ATHROW) {
                    loadLocal(previous);
                    invokeStatic(HOOK_TYPE, new Method("leave", "(Ljava/lang/Object;)V"));
                }
            }

            @Override public void visitMaxs(int stack, int locals) {
                mark(end);
                visitTryCatchBlock(start, end, handler, null);
                mark(handler);
                int exception = newLocal(Type.getType(Throwable.class));
                storeLocal(exception);
                loadLocal(previous);
                invokeStatic(HOOK_TYPE, new Method("leave", "(Ljava/lang/Object;)V"));
                loadLocal(exception);
                mv.visitInsn(ATHROW);
                super.visitMaxs(stack, locals);
            }
        };
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                ProtectionDomain domain, byte[] bytes) {
            try {
                return NativeTerrainAgent.transform(name, bytes, loader);
            } catch (RuntimeException failure) {
                System.err.println("[LC Terrain] Cannot install " + name + ": " + failure);
                return null;
            }
        }
    }
}
