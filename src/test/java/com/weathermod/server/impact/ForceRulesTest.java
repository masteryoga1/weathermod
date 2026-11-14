package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.config.FallDamageMode;
import com.weathermod.core.FunnelSpine;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure rules of pushing entities: the fall damage policy and its grace period, the mass of a heavy
 * body, the roof probe, the test for standing inside a funnel and the cadence on which a pushed entity is synced.
 * The rules that need a level or an entity are covered by the smoke-test checklist. Needs no Minecraft bootstrap.
 */
class ForceRulesTest {
    private static final float EPSILON = 1.0E-6F;
    private static final float GROUND_Y = 64.0F;
    private static final float TOP_Y = 112.0F;
    private static final double MID_Y = 88.0D;
    private static final float BASE_RADIUS = 3.0F;
    private static final float TOP_RADIUS = 5.0F;
    private static final double CENTRE_X = 100.0D;
    private static final double CENTRE_Z = -50.0D;
    private static final int HEAD_Y = 70;

    /**
     * A grounded funnel from {@link #GROUND_Y} to {@link #TOP_Y} whose axis leans evenly toward the east and
     * stands {@code lean} blocks east of the storm centre at its top.
     */
    private static FunnelSpine funnel(float lean) {
        List<Vector3f> points = new ArrayList<>();
        int last = FunnelSpine.POINTS - 1;
        for (int i = 0; i <= last; i++) {
            float fraction = i / (float) last;
            points.add(new Vector3f(lean * fraction, GROUND_Y + (TOP_Y - GROUND_Y) * fraction, 0.0F));
        }
        return new FunnelSpine(points, BASE_RADIUS, TOP_RADIUS, 1.0F, 1.0F, true);
    }

    private static boolean inside(FunnelSpine spine, double offsetX, double y, double offsetZ) {
        return ForceRules.insideFunnel(spine, CENTRE_X, CENTRE_Z, CENTRE_X + offsetX, y, CENTRE_Z + offsetZ);
    }

    @Test
    void constantsAreTheSpecifiedOnes() {
        assertEquals(128, ForceRules.MAX_FORCED_ENTITIES_PER_STORM_TICK);
        assertEquals(48.0D, ForceRules.WIND_FORCE_PLAYER_RANGE_BLOCKS);
        assertEquals(2, ForceRules.WIND_FORCE_INTERVAL_TICKS);
        assertEquals(5, ForceRules.FORCE_SYNC_INTERVAL_TICKS);
        assertEquals(6, ForceRules.ROOF_PROBE_BLOCKS);
        assertEquals(200, ForceRules.VORTEX_FALL_GRACE_TICKS);
        assertEquals(0.5F, ForceRules.REDUCED_FALL_FACTOR);
        assertEquals("weathermod:vortex_tick", ForceRules.VORTEX_STAMP_KEY);
        assertEquals(80, ForceRules.FIRE_WHIRL_BURN_TICKS);
        assertEquals(0.25F, ForceRules.MIN_MASS);
        assertEquals(0.75F, ForceRules.MASS_RESISTANCE_WEIGHT);
    }

    @Test
    void fullFallDamageLeavesTheMultiplierAlone() {
        assertEquals(1.0F, ForceRules.fallMultiplier(FallDamageMode.FULL, 1.0F));
        assertEquals(0.7F, ForceRules.fallMultiplier(FallDamageMode.FULL, 0.7F));
        assertEquals(0.0F, ForceRules.fallMultiplier(FallDamageMode.FULL, 0.0F));
    }

    @Test
    void reducedFallDamageHalvesTheMultiplier() {
        assertEquals(0.5F, ForceRules.fallMultiplier(FallDamageMode.REDUCED, 1.0F), EPSILON);
        assertEquals(0.3F, ForceRules.fallMultiplier(FallDamageMode.REDUCED, 0.6F), EPSILON);
        assertEquals(0.0F, ForceRules.fallMultiplier(FallDamageMode.REDUCED, 0.0F), EPSILON);
    }

    @Test
    void noFallDamageGivesZeroWhateverTheMultiplier() {
        assertEquals(0.0F, ForceRules.fallMultiplier(FallDamageMode.NONE, 1.0F));
        assertEquals(0.0F, ForceRules.fallMultiplier(FallDamageMode.NONE, 3.5F));
    }

    @Test
    void aFallCountsForTheGracePeriodAfterTheVortex() {
        assertTrue(ForceRules.recentlyInVortex(1000L, 1000L), "the tick of the contact itself");
        assertTrue(ForceRules.recentlyInVortex(1000L, 1001L));
        assertTrue(ForceRules.recentlyInVortex(1000L, 1200L), "the last tick of the grace period");
        assertFalse(ForceRules.recentlyInVortex(1000L, 1201L), "one tick after the grace period");
        assertFalse(ForceRules.recentlyInVortex(1000L, 50_000L));
    }

    @Test
    void aStampFromTheFutureDoesNotCount() {
        assertFalse(ForceRules.recentlyInVortex(1000L, 999L));
        assertFalse(ForceRules.recentlyInVortex(1000L, 0L));
    }

    @Test
    void aStampOfZeroIsRecentInAYoungWorld() {
        assertTrue(ForceRules.recentlyInVortex(0L, 0L), "why a missing stamp must not be read as zero");
        assertTrue(ForceRules.recentlyInVortex(0L, 200L));
        assertFalse(ForceRules.recentlyInVortex(0L, 201L));
    }

    @Test
    void massFallsWithKnockbackResistanceToItsFloor() {
        assertEquals(1.0F, ForceRules.mass(0.0D), EPSILON);
        assertEquals(0.625F, ForceRules.mass(0.5D), EPSILON);
        assertEquals(0.25F, ForceRules.mass(1.0D), EPSILON, "an iron golem still feels a quarter of the push");
        assertEquals(0.925F, ForceRules.mass(0.1D), EPSILON);
    }

    @Test
    void massClampsTheResistance() {
        assertEquals(1.0F, ForceRules.mass(-2.0D), EPSILON);
        assertEquals(0.25F, ForceRules.mass(7.0D), EPSILON);
        assertEquals(0.25F, ForceRules.mass(Double.POSITIVE_INFINITY), EPSILON);
        assertEquals(1.0F, ForceRules.mass(Double.NaN), EPSILON, "a resistance that is not a number counts as none");
    }

    @Test
    void massNeverLeavesItsRange() {
        for (double resistance = -0.5D; resistance <= 1.5D; resistance += 0.05D) {
            float mass = ForceRules.mass(resistance);
            assertTrue(mass >= ForceRules.MIN_MASS && mass <= 1.0F, "mass out of range at " + resistance);
        }
    }

    @Test
    void aBlockWithinTheProbeShelters() {
        int blocks = ForceRules.ROOF_PROBE_BLOCKS;
        assertFalse(ForceRules.openAbove(y -> y == HEAD_Y + 1, HEAD_Y, blocks), "a block right above the head");
        assertFalse(ForceRules.openAbove(y -> y == HEAD_Y + 3, HEAD_Y, blocks));
        assertFalse(ForceRules.openAbove(y -> y == HEAD_Y + 6, HEAD_Y, blocks), "the last block the probe reaches");
        assertFalse(ForceRules.openAbove(y -> y > HEAD_Y, HEAD_Y, blocks), "solid all the way up");
    }

    @Test
    void aBlockBeyondTheProbeDoesNotShelter() {
        int blocks = ForceRules.ROOF_PROBE_BLOCKS;
        assertTrue(ForceRules.openAbove(y -> y == HEAD_Y + 7, HEAD_Y, blocks), "one block above the probe");
        assertTrue(ForceRules.openAbove(y -> y >= HEAD_Y + 7, HEAD_Y, blocks));
        assertTrue(ForceRules.openAbove(y -> false, HEAD_Y, blocks), "nothing above at all");
    }

    @Test
    void theProbeStartsAboveTheHead() {
        int blocks = ForceRules.ROOF_PROBE_BLOCKS;
        assertTrue(ForceRules.openAbove(y -> y <= HEAD_Y, HEAD_Y, blocks), "the head's own block and the ground");
        assertTrue(ForceRules.openAbove(y -> true, HEAD_Y, 0), "a probe of no blocks finds nothing");
    }

    @Test
    void theProbeAsksEveryHeightOnceFromTheHeadUp() {
        List<Integer> asked = new ArrayList<>();
        assertTrue(ForceRules.openAbove(y -> {
            asked.add(y);
            return false;
        }, HEAD_Y, 6));
        assertEquals(List.of(71, 72, 73, 74, 75, 76), asked);
    }

    @Test
    void insideTheRadiusAtMidHeight() {
        FunnelSpine spine = funnel(0.0F);
        assertEquals(3.4352753F, spine.radiusAt(MID_Y), 1.0E-4F, "3 + 2 * 0.5^2.2 at half height");
        assertTrue(inside(spine, 0.0D, MID_Y, 0.0D), "on the axis");
        assertTrue(inside(spine, 3.4D, MID_Y, 0.0D));
        assertTrue(inside(spine, 0.0D, MID_Y, -3.4D));
        assertTrue(inside(spine, 2.4D, MID_Y, 2.4D), "3.39 blocks from the axis");
    }

    @Test
    void outsideTheRadiusAtMidHeight() {
        FunnelSpine spine = funnel(0.0F);
        assertFalse(inside(spine, 3.5D, MID_Y, 0.0D));
        assertFalse(inside(spine, 0.0D, MID_Y, 3.5D));
        assertFalse(inside(spine, 2.5D, MID_Y, -2.5D), "3.54 blocks from the axis");
        assertFalse(inside(spine, 40.0D, MID_Y, 0.0D));
    }

    @Test
    void theFunnelWidensWithHeight() {
        FunnelSpine spine = funnel(0.0F);
        assertTrue(inside(spine, 2.9D, GROUND_Y, 0.0D), "the base radius at the ground");
        assertFalse(inside(spine, 3.1D, GROUND_Y, 0.0D));
        assertTrue(inside(spine, 4.9D, TOP_Y, 0.0D), "the top radius at the top");
        assertFalse(inside(spine, 5.1D, TOP_Y, 0.0D));
        assertFalse(inside(spine, 4.9D, MID_Y, 0.0D), "what is inside at the top is outside half way up");
    }

    @Test
    void belowTheGroundPointIsOutside() {
        FunnelSpine spine = funnel(0.0F);
        assertTrue(inside(spine, 0.0D, GROUND_Y, 0.0D), "the ground point itself is inside");
        assertFalse(inside(spine, 0.0D, GROUND_Y - 0.01D, 0.0D));
        assertFalse(inside(spine, 0.0D, GROUND_Y - 3.0D, 0.0D));
    }

    @Test
    void aboveTheTopIsOutside() {
        FunnelSpine spine = funnel(0.0F);
        assertTrue(inside(spine, 0.0D, TOP_Y, 0.0D), "the top point itself is inside");
        assertFalse(inside(spine, 0.0D, TOP_Y + 0.01D, 0.0D));
        assertFalse(inside(spine, 0.0D, TOP_Y + 8.0D, 0.0D));
    }

    @Test
    void aHeightThatIsNotANumberIsOutside() {
        assertFalse(inside(funnel(0.0F), 0.0D, Double.NaN, 0.0D));
    }

    @Test
    void theTestFollowsALeaningAxis() {
        FunnelSpine spine = funnel(12.0F);
        assertTrue(inside(spine, 6.0D, MID_Y, 0.0D), "the axis stands 6 blocks east at half height");
        assertTrue(inside(spine, 9.0D, MID_Y, 0.0D), "3 blocks from the leaning axis");
        assertFalse(inside(spine, 0.0D, MID_Y, 0.0D), "the storm centre is 6 blocks from the axis there");
        assertFalse(inside(spine, 6.0D, MID_Y, 3.6D));
        assertTrue(inside(spine, 0.0D, GROUND_Y, 0.0D), "the funnel still stands on the storm centre");
        assertTrue(inside(spine, 12.0D, TOP_Y, 0.0D));
        assertFalse(inside(spine, 0.0D, TOP_Y, 0.0D), "12 blocks from the axis at the top");
    }

    @Test
    void syncIsDueOnOneTickInFive() {
        int[] entityIds = {0, 1, 2, 3, 4, 7, 99, 12_345, 2_000_000_000};
        for (int entityId : entityIds) {
            for (int start = 0; start <= 40; start++) {
                int due = 0;
                for (int tick = start; tick < start + ForceRules.FORCE_SYNC_INTERVAL_TICKS; tick++) {
                    if (ForceRules.syncDue(tick, entityId)) {
                        due++;
                    }
                }
                assertEquals(1, due, "entity " + entityId + " in the five ticks from " + start);
            }
        }
    }

    @Test
    void syncComesRoundEveryFiveTicks() {
        for (int entityId = 0; entityId < 20; entityId++) {
            for (int tick = 0; tick < 100; tick++) {
                assertEquals(ForceRules.syncDue(tick, entityId),
                    ForceRules.syncDue(tick + ForceRules.FORCE_SYNC_INTERVAL_TICKS, entityId),
                    "entity " + entityId + " at tick " + tick);
            }
        }
    }

    @Test
    void syncStaggersNeighbouringIds() {
        for (int tick = 0; tick < 50; tick++) {
            int due = 0;
            for (int entityId = 100; entityId < 100 + ForceRules.FORCE_SYNC_INTERVAL_TICKS; entityId++) {
                if (ForceRules.syncDue(tick, entityId)) {
                    due++;
                }
            }
            assertEquals(1, due, "of five neighbouring ids exactly one is due at tick " + tick);
            assertFalse(ForceRules.syncDue(tick, 7) && ForceRules.syncDue(tick, 8), "neighbours share no tick");
        }
    }

    @Test
    void syncFollowsTheSumOfTickAndId() {
        assertTrue(ForceRules.syncDue(0, 0));
        assertTrue(ForceRules.syncDue(3, 2));
        assertTrue(ForceRules.syncDue(13, 7));
        assertFalse(ForceRules.syncDue(3, 3));
        assertFalse(ForceRules.syncDue(1, 0));
    }
}
