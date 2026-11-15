package com.lunarboreal.teleport;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RtpCircleTest {
    @Test void allBlockCentersStayInsideSmallDefaultAndMaximumCircles() {
        Random random = new Random(20261115L);
        for (int radius : new int[]{1, 1000, 30_000_000}) {
            for (int i = 0; i < 10_000; i++) {
                int[] offset = RtpManager.circleOffset(random, radius);
                assertEquals(2, offset.length);
                assertTrue(offset[0] >= -radius && offset[0] < radius);
                assertTrue(offset[1] >= -radius && offset[1] < radius);
                double x = offset[0] + .5;
                double z = offset[1] + .5;
                assertTrue(x * x + z * z <= (double) radius * radius,
                        "The centered landing block must stay inside the requested circle");
            }
        }
    }

    @Test void radiusOneCanChooseAllFourBlocksAroundTheOrigin() {
        Random random = new Random(7231L);
        Set<String> blocks = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            int[] offset = RtpManager.circleOffset(random, 1);
            blocks.add(offset[0] + "," + offset[1]);
        }
        assertEquals(Set.of("-1,-1", "-1,0", "0,-1", "0,0"), blocks);
    }

    @Test void fixedSeedCoversAllQuadrantsAndTheAreaOfTheCircle() {
        Random random = new Random(161803398L);
        int count = 20_000;
        int[] quadrants = new int[4];
        int inner = 0;
        int outer = 0;
        double maxX = 0;
        double maxZ = 0;
        for (int i = 0; i < count; i++) {
            int[] offset = RtpManager.circleOffset(random, 1000);
            double x = offset[0] + .5;
            double z = offset[1] + .5;
            double squared = x * x + z * z;
            quadrants[(x < 0 ? 1 : 0) | (z < 0 ? 2 : 0)]++;
            if (squared <= 250_000d) inner++;
            if (squared >= 562_500d) outer++;
            maxX = Math.max(maxX, Math.abs(x));
            maxZ = Math.max(maxZ, Math.abs(z));
        }
        for (int quadrant : quadrants)
            assertTrue(quadrant > count * .20 && quadrant < count * .30);
        // Uniform area sampling places one quarter of the points inside r/2.
        assertTrue(inner > count * .20 && inner < count * .30);
        assertTrue(outer > count * .35 && outer < count * .55);
        assertTrue(maxX > 900 && maxZ > 900);
    }

    @Test void suppliedRandomGeneratorMakesSamplingReproducible() {
        Random first = new Random(42L);
        Random second = new Random(42L);
        for (int i = 0; i < 100; i++)
            assertArrayEquals(RtpManager.circleOffset(first, 1000), RtpManager.circleOffset(second, 1000));
    }

    @Test void rejectsZeroNegativeAndOverLimitRadii() {
        for (int radius : new int[]{0, -1, Integer.MIN_VALUE, 30_000_001, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> RtpManager.circleOffset(new Random(7L), radius));
    }
}
