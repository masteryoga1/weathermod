package com.weathermod.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Pins the arithmetic of flying blocks: the orbit time by tier and its limits, the damage a block deals at a
 * speed, and the direction it is launched in. Pure arithmetic, so no Minecraft bootstrap is needed.
 */
class FlyingBlockMathTest {
    private static final double EPSILON = 1.0E-9;
    private static final int DRAWS = 2_000;
    /** The throw speeds of tornado_ef0 to tornado_ef5, in blocks per second. */
    private static final float[] THROW_SPEEDS = {6.0F, 9.0F, 13.0F, 16.0F, 20.0F, 23.0F};

    private static int orbitTicksWithJitter(int tier, int jitterTicks) {
        return FlyingBlockMath.orbitTicks(tier, new FixedRandom(jitterTicks + FlyingBlockMath.ORBIT_JITTER_TICKS));
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(0.3F, FlyingBlockMath.LAUNCH_UP_MIN);
        assertEquals(0.5F, FlyingBlockMath.LAUNCH_UP_MAX);
        assertEquals(40, FlyingBlockMath.ORBIT_BASE_TICKS);
        assertEquals(24, FlyingBlockMath.ORBIT_TICKS_PER_TIER);
        assertEquals(20, FlyingBlockMath.ORBIT_JITTER_TICKS);
        assertEquals(0.3, FlyingBlockMath.DAMAGE_MIN_SPEED);
    }

    @Test
    void theEntityConstantsAreTheAgreedOnes() {
        assertEquals(600, FlyingBlockEntity.MAX_LIFE_TICKS);
        assertEquals(0.04, FlyingBlockEntity.GRAVITY);
        assertEquals(0.98, FlyingBlockEntity.DRAG);
        assertEquals(10, FlyingBlockEntity.DAMAGE_INTERVAL_TICKS);
    }

    @Test
    void withoutVariationTheOrbitTimeIsFortyPlusTwentyFourPerTier() {
        int[] expected = {40, 64, 88, 112, 136, 160};
        for (int tier = 0; tier <= 5; tier++) {
            assertEquals(expected[tier], orbitTicksWithJitter(tier, 0), "tier " + tier);
        }
    }

    @Test
    void theOrbitTimeVariesByTwentyTicksEitherWay() {
        assertEquals(92, orbitTicksWithJitter(3, -20), "112 - 20");
        assertEquals(132, orbitTicksWithJitter(3, 20), "112 + 20");
        assertEquals(44, orbitTicksWithJitter(1, -20), "64 - 20");
        assertEquals(84, orbitTicksWithJitter(1, 20), "64 + 20");
        assertEquals(107, orbitTicksWithJitter(3, -5), "112 - 5");
    }

    @Test
    void theOrbitTimeIsKeptWithinFortyAndOneHundredAndSixtyTicks() {
        assertEquals(40, orbitTicksWithJitter(0, -20), "40 - 20 is raised to the shortest orbit");
        assertEquals(60, orbitTicksWithJitter(0, 20));
        assertEquals(160, orbitTicksWithJitter(5, 20), "160 + 20 is cut to the longest orbit");
        assertEquals(140, orbitTicksWithJitter(5, -20));
        assertEquals(156, orbitTicksWithJitter(4, 20), "136 + 20 fits");
    }

    @Test
    void aTierOutsideTheScaleCountsAsTheNearestOne() {
        assertEquals(orbitTicksWithJitter(0, 0), orbitTicksWithJitter(-3, 0));
        assertEquals(orbitTicksWithJitter(5, 0), orbitTicksWithJitter(11, 0));
    }

    @Test
    void seededOrbitTimesStayWithinTheBoundsOfTheirTier() {
        RandomSource random = RandomSource.create(20240611L);
        for (int tier = 0; tier <= 5; tier++) {
            int centre = FlyingBlockMath.ORBIT_BASE_TICKS + FlyingBlockMath.ORBIT_TICKS_PER_TIER * tier;
            int lowest = Math.max(40, centre - FlyingBlockMath.ORBIT_JITTER_TICKS);
            int highest = Math.min(160, centre + FlyingBlockMath.ORBIT_JITTER_TICKS);
            for (int draw = 0; draw < DRAWS; draw++) {
                int ticks = FlyingBlockMath.orbitTicks(tier, random);
                assertTrue(ticks >= lowest && ticks <= highest,
                    "tier " + tier + ": " + ticks + " outside " + lowest + ".." + highest);
            }
        }
    }

    @Test
    void damageGrowsWithSpeedBetweenOneAndTenPoints() {
        assertEquals(1.0F, FlyingBlockMath.impactDamage(0.0), "at rest");
        assertEquals(2.2F, FlyingBlockMath.impactDamage(0.3), 1.0E-6F, "at the slowest speed that hurts");
        assertEquals(3.0F, FlyingBlockMath.impactDamage(0.5), 1.0E-6F);
        assertEquals(5.0F, FlyingBlockMath.impactDamage(1.0), 1.0E-6F, "one block per tick");
        assertEquals(9.0F, FlyingBlockMath.impactDamage(2.0), 1.0E-6F);
        assertEquals(10.0F, FlyingBlockMath.impactDamage(2.25), "1 + 4 * 2.25 is the most there is");
    }

    @Test
    void damageIsClamped() {
        assertEquals(10.0F, FlyingBlockMath.impactDamage(3.0), "13 is cut to 10");
        assertEquals(10.0F, FlyingBlockMath.impactDamage(1000.0));
        assertEquals(10.0F, FlyingBlockMath.impactDamage(Double.POSITIVE_INFINITY));
        assertEquals(1.0F, FlyingBlockMath.impactDamage(-2.0), "a negative speed counts as rest");
        assertEquals(1.0F, FlyingBlockMath.impactDamage(Double.NaN));
    }

    @Test
    void aBlockEastOfTheAxisIsThrownNorth() {
        Vector3d out = new Vector3d();
        FlyingBlockMath.launchVelocity(5.0, 0.0, 20.0F, 0.4F, out);
        assertEquals(0.0, out.x, EPSILON);
        assertEquals(-1.0, out.z, EPSILON, "20 blocks per second are 1 block per tick, toward negative z");
        assertEquals(0.4F, out.y, EPSILON, "the upward part is passed through");
    }

    @Test
    void theLaunchTurnsCounterClockwiseSeenFromAbove() {
        Vector3d out = new Vector3d();
        FlyingBlockMath.launchVelocity(0.0, -5.0, 20.0F, 0.3F, out);
        assertEquals(-1.0, out.x, EPSILON, "north of the axis: thrown west");
        assertEquals(0.0, out.z, EPSILON);

        FlyingBlockMath.launchVelocity(-5.0, 0.0, 20.0F, 0.3F, out);
        assertEquals(0.0, out.x, EPSILON);
        assertEquals(1.0, out.z, EPSILON, "west of the axis: thrown south");

        FlyingBlockMath.launchVelocity(0.0, 5.0, 20.0F, 0.3F, out);
        assertEquals(1.0, out.x, EPSILON, "south of the axis: thrown east");
        assertEquals(0.0, out.z, EPSILON);
    }

    @Test
    void theLaunchIsTangentialAtTheThrowSpeedWhereverTheBlockIs() {
        Vector3d out = new Vector3d();
        double[][] offsets = {{3.0, 4.0}, {-7.5, 2.0}, {0.25, -0.1}, {-12.0, -30.0}, {40.0, 0.5}};
        for (int tier = 0; tier < THROW_SPEEDS.length; tier++) {
            double speed = THROW_SPEEDS[tier] / 20.0;
            for (double[] offset : offsets) {
                double dx = offset[0];
                double dz = offset[1];
                FlyingBlockMath.launchVelocity(dx, dz, THROW_SPEEDS[tier], 0.5F, out);
                assertEquals(0.0, out.x * dx + out.z * dz, 1.0E-9, "no part toward or away from the axis");
                assertEquals(speed, Math.sqrt(out.x * out.x + out.z * out.z), EPSILON, "the throw speed per tick");
                assertTrue(dz * out.x - dx * out.z > 0.0, "counter-clockwise seen from above");
                assertEquals(0.5F, out.y, EPSILON);
            }
        }
    }

    @Test
    void theDistanceFromTheAxisDoesNotChangeTheSpeed() {
        Vector3d near = new Vector3d();
        Vector3d far = new Vector3d();
        FlyingBlockMath.launchVelocity(1.0, 1.0, 16.0F, 0.3F, near);
        FlyingBlockMath.launchVelocity(30.0, 30.0, 16.0F, 0.3F, far);
        assertEquals(near.x, far.x, EPSILON);
        assertEquals(near.z, far.z, EPSILON);
    }

    @Test
    void aBlockOnTheAxisGoesStraightUp() {
        Vector3d out = new Vector3d(9.0, 9.0, 9.0);
        FlyingBlockMath.launchVelocity(0.0, 0.0, 23.0F, 0.45F, out);
        assertEquals(0.0, out.x);
        assertEquals(0.45F, out.y, EPSILON);
        assertEquals(0.0, out.z);
    }

    /** A random source whose {@code nextInt(bound)} always answers the same number. */
    private static final class FixedRandom implements RandomSource {
        private final int value;

        FixedRandom(int value) {
            this.value = value;
        }

        @Override
        public RandomSource fork() {
            return new FixedRandom(value);
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            throw new UnsupportedOperationException("not needed by flying blocks");
        }

        @Override
        public void setSeed(long seed) {
        }

        @Override
        public int nextInt() {
            return value;
        }

        @Override
        public int nextInt(int bound) {
            return Math.max(0, Math.min(bound - 1, value));
        }

        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public boolean nextBoolean() {
            return false;
        }

        @Override
        public float nextFloat() {
            return 0.0F;
        }

        @Override
        public double nextDouble() {
            return 0.0;
        }

        @Override
        public double nextGaussian() {
            return 0.0;
        }
    }
}
