package com.lunarboreal.land;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real event filter and XYZ index without starting a Paper server. */
class SpongeProtectionTest {
    private static final World WORLD = stub(World.class, "getName", "world");

    static Stream<Arguments> borders() {
        List<Arguments> cases = new ArrayList<>();
        // Include underground and high-altitude claims as well as ordinary positive Y.
        for (int bottom : new int[]{-60, 10, 300}) {
            int[][] inside = {{-16,bottom+2,0},{16,bottom+2,0},{0,bottom+2,-16},
                    {0,bottom+2,16},{0,bottom,0},{0,bottom+4,0}};
            int[][] outside = {{-17,bottom+2,0},{17,bottom+2,0},{0,bottom+2,-17},
                    {0,bottom+2,17},{0,bottom-1,0},{0,bottom+5,0}};
            String[] faces = {"-X", "+X", "-Z", "+Z", "-Y", "+Y"};
            for (int i = 0; i < faces.length; i++) {
                for (boolean outward : new boolean[]{false, true}) {
                    cases.add(Arguments.of(faces[i], bottom, inside[i], outside[i], outward));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, minY={1}, outward={4}")
    @MethodSource("borders")
    void landAndWildernessBordersKeepOnlySameRegion(String face, int bottom, int[] inside,
                                                   int[] outside, boolean outward) throws Exception {
        LandModule module = module(true);
        module.manager().add(claim("inside", bottom));
        assertFiltered(module, outward ? inside : outside, outward ? outside : inside);
    }

    @ParameterizedTest(name = "{0}, minY={1}, outward={4}")
    @MethodSource("borders")
    void neighboringClaimsAreSeparateEvenWithSameOwner(String face, int bottom, int[] inside,
                                                       int[] outside, boolean outward) throws Exception {
        LandModule module = module(true);
        Land first = claim("inside", bottom);
        Land neighbor = new Land("neighbor", first.owner, "owner", "world",
                outside[0], outside[2], outside[0], outside[2], 1);
        neighbor.minY = neighbor.maxY = outside[1];
        module.manager().add(first);
        module.manager().add(neighbor);
        assertFiltered(module, outward ? inside : outside, outward ? outside : inside);
    }

    private void assertFiltered(LandModule module, int[] source, int[] across) {
        BlockState allowed = state(source), denied = state(across);
        // Mixed lists must retain allowed absorption instead of cancelling the whole event.
        SpongeAbsorbEvent event = event(source, allowed, denied);
        new ProtectionListener(module).onSponge(event);
        assertEquals(List.of(allowed), event.getBlocks());
        assertFalse(event.isCancelled());
    }

    @Test void sameClaimAndWildernessStillAbsorbAtDistinctPositions() throws Exception {
        LandModule module = module(true);
        module.manager().add(claim("inside", 10));
        for (int[][] pair : new int[][][]{
                {{-16,10,-16},{-15,11,-15}}, {{100,100,100},{101,101,101}}}) {
            BlockState water = state(pair[1]);
            SpongeAbsorbEvent event = event(pair[0], water);
            new ProtectionListener(module).onSponge(event);
            assertEquals(List.of(water), event.getBlocks());
            assertFalse(event.isCancelled());
        }
    }

    @Test void disabledLiquidProtectionLeavesCrossBorderTargetsUntouched() throws Exception {
        LandModule module = module(false);
        module.manager().add(claim("inside", 10));
        for (int[][] pair : new int[][][]{
                {{16,12,0},{17,12,0}}, {{17,12,0},{16,12,0}}}) {
            BlockState water = state(pair[1]);
            SpongeAbsorbEvent event = event(pair[0], water);
            new ProtectionListener(module).onSponge(event);
            assertEquals(List.of(water), event.getBlocks());
            assertFalse(event.isCancelled());
        }
    }

    private static Land claim(String name, int bottom) {
        Land land = new Land(name, UUID.randomUUID(), "owner", "world", -16, -16, 16, 16, 1);
        land.minY = bottom;
        land.maxY = bottom + 4;
        return land;
    }

    private static LandModule module(boolean protect) throws Exception {
        LandModule module = new LandModule();
        YamlConfiguration config = new YamlConfiguration();
        config.set("protection.liquid-flow", protect);
        var field = LandModule.class.getDeclaredField("settings");
        field.setAccessible(true);
        field.set(module, new Settings(config));
        return module;
    }

    private static SpongeAbsorbEvent event(int[] source, BlockState... targets) {
        Location position = location(source);
        Block sponge = Block.class.cast(Proxy.newProxyInstance(Block.class.getClassLoader(),
                new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> WORLD;
                    case "getX" -> position.getBlockX();
                    case "getY" -> position.getBlockY();
                    case "getZ" -> position.getBlockZ();
                    default -> throw new AssertionError("Unexpected block method: " + method);
                }));
        return new SpongeAbsorbEvent(sponge, new ArrayList<>(List.of(targets)));
    }

    private static BlockState state(int[] xyz) {
        return stub(BlockState.class, "getLocation", location(xyz));
    }

    private static Location location(int[] xyz) {
        return new Location(WORLD, xyz[0], xyz[1], xyz[2]);
    }

    private static <T> T stub(Class<T> type, String getter, Object value) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " stub";
                    default -> {
                        if (method.getName().equals(getter)) yield value;
                        throw new AssertionError("Unexpected method: " + method);
                    }
                }));
    }
}
