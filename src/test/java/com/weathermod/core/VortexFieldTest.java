package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Pins the Rankine force field: where it reaches, the pull, the counter-clockwise tangential push with its cap,
 * the lift with its two limits, the exposure, and, by simulating a body with gravity, which tiers carry a body
 * aloft and which never do. The tier table and the shipped small vortices are repeated here as constants. Needs no
 * Minecraft bootstrap.
 */
class VortexFieldTest {
    private static final double GROUND_Y = 64.0D;
    private static final double TOP_Y = 160.0D;
    private static final double TOLERANCE = 1.0E-6D;
    /** Gravity and vertical drag of a living entity, per tick. */
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final int RISE_TICKS = 60;

    /** The tier table of the specification: core, top and influence radius, wind, pull, lift, rise cap, eject. */
    private static final VortexParams[] TORNADOES = {
        vortex(VortexKind.TORNADO, 0, 3.0F, 10.0F, 24.0F, 8.0F, 0.034F, 0.05F, 0.15F, 4.0F),
        vortex(VortexKind.TORNADO, 1, 5.0F, 16.0F, 36.0F, 13.0F, 0.057F, 0.07F, 0.20F, 8.0F),
        vortex(VortexKind.TORNADO, 2, 8.0F, 22.0F, 48.0F, 18.0F, 0.079F, 0.14F, 0.30F, 20.0F),
        vortex(VortexKind.TORNADO, 3, 12.0F, 30.0F, 64.0F, 23.0F, 0.102F, 0.17F, 0.40F, 40.0F),
        vortex(VortexKind.TORNADO, 4, 18.0F, 40.0F, 88.0F, 28.0F, 0.125F, 0.18F, 0.50F, 70.0F),
        vortex(VortexKind.TORNADO, 5, 26.0F, 55.0F, 120.0F, 33.0F, 0.148F, 0.22F, 0.50F, 0.0F),
    };

    /** The shipped small vortices: landspout, waterspout, gustnado, dust devil and fire whirl. */
    private static final VortexParams[] SMALL_VORTICES = {
        vortex(VortexKind.LANDSPOUT, 0, 2.5F, 6.0F, 16.0F, 8.0F, 0.034F, 0.05F, 0.15F, 4.0F),
        vortex(VortexKind.WATERSPOUT, 0, 4.0F, 10.0F, 24.0F, 10.0F, 0.04F, 0.06F, 0.15F, 6.0F),
        vortex(VortexKind.GUSTNADO, 0, 4.0F, 6.0F, 14.0F, 9.0F, 0.034F, 0.04F, 0.12F, 3.0F),
        vortex(VortexKind.DUST_DEVIL, 0, 1.8F, 3.0F, 8.0F, 5.0F, 0.02F, 0.03F, 0.1F, 2.0F),
        vortex(VortexKind.FIRE_WHIRL, 0, 3.0F, 5.0F, 12.0F, 9.0F, 0.04F, 0.06F, 0.15F, 6.0F),
    };

    private static final VortexParams EF3 = TORNADOES[3];

    private static VortexParams vortex(VortexKind kind, int tier, float coreRadius, float topRadius,
                                       float influenceRadius, float windSpeed, float pull, float lift,
                                       float riseCap, float ejectHeight) {
        return new VortexParams(kind, tier, coreRadius, topRadius, influenceRadius, WeatherType.Range.of(0.0F),
            windSpeed, pull, lift, riseCap, ejectHeight, 0.0F, 0.0F, 6.0F, 0.0F, WeatherType.UNLIMITED_SPEED);
    }

    /** A straight funnel standing on the storm centre, from {@link #GROUND_Y} to {@link #TOP_Y}. */
    private static FunnelSpine upright(VortexParams params, float strength, boolean grounded) {
        List<Vector3f> points = new ArrayList<>();
        int last = FunnelSpine.POINTS - 1;
        for (int i = 0; i <= last; i++) {
            points.add(new Vector3f(0.0F, (float) (GROUND_Y + (TOP_Y - GROUND_Y) * i / last), 0.0F));
        }
        float scale = VortexField.scale(strength);
        return new FunnelSpine(points, params.coreRadius() * scale, params.topRadius() * scale, strength,
            grounded ? 1.0F : 0.5F, grounded);
    }

    /** The acceleration of a body at rest, or null when the field does not act on it. */
    private static Vector3d atRest(VortexParams params, FunnelSpine spine, double x, double y, double z) {
        Vector3d out = new Vector3d(9.0D, 9.0D, 9.0D);
        boolean acts = VortexField.acceleration(params, spine, 0.0D, 0.0D, x, y, z, 0.0D, 0.0D, 0.0D, 1.0F, out);
        if (!acts) {
            assertEquals(new Vector3d(), out, "a body the field does not act on gets a zero acceleration");
            return null;
        }
        return out;
    }

    @Test
    void scaleKeepsHalfTheSizeAtZeroStrength() {
        assertEquals(0.5F, VortexField.scale(0.0F));
        assertEquals(0.75F, VortexField.scale(0.5F));
        assertEquals(1.0F, VortexField.scale(1.0F));
        assertEquals(1.0F, VortexField.scale(3.0F), "strength is clamped to 0..1");
        assertEquals(0.5F, VortexField.scale(-1.0F));
        assertEquals(0.5F, VortexField.scale(Float.NaN));

        assertEquals(64.0F, VortexField.influenceRadius(EF3, 1.0F));
        assertEquals(32.0F, VortexField.influenceRadius(EF3, 0.0F));
        assertEquals(0.5F, VortexField.liftScale(0.0F));
        assertEquals(0.65F, VortexField.liftScale(0.3F), 1.0E-6F);
        assertEquals(1.0F, VortexField.liftScale(1.0F));
    }

    @Test
    void rankineRisesInsideTheCoreAndFallsOutside() {
        assertEquals(0.0F, VortexField.rankine(0.0D, 12.0D), "no NaN on the axis");
        assertEquals(0.25F, VortexField.rankine(3.0D, 12.0D));
        assertEquals(0.5F, VortexField.rankine(6.0D, 12.0D));
        assertEquals(1.0F, VortexField.rankine(12.0D, 12.0D), "the profile peaks on the core radius");
        assertEquals(0.5F, VortexField.rankine(24.0D, 12.0D));
        assertEquals(0.25F, VortexField.rankine(48.0D, 12.0D));
        assertEquals(0.0F, VortexField.rankine(5.0D, 0.0D), "a vortex without a core has no profile");
        assertEquals(0.0F, VortexField.rankine(-1.0D, 12.0D));
    }

    @Test
    void edgeFadeIsFullInsideSeventyPercentAndZeroAtTheRadius() {
        assertEquals(1.0F, VortexField.edgeFade(0.0D, 100.0D));
        assertEquals(1.0F, VortexField.edgeFade(69.0D, 100.0D));
        assertEquals(1.0F, VortexField.edgeFade(70.0D, 100.0D), 1.0E-5F);
        assertEquals(0.5F, VortexField.edgeFade(85.0D, 100.0D), 1.0E-5F);
        assertEquals(0.1F, VortexField.edgeFade(97.0D, 100.0D), 1.0E-5F);
        assertEquals(0.0F, VortexField.edgeFade(100.0D, 100.0D));
        assertEquals(0.0F, VortexField.edgeFade(150.0D, 100.0D));
        assertEquals(0.0F, VortexField.edgeFade(0.0D, 0.0D), "a field without a reach never acts");
    }

    @Test
    void exposureIsFullUnderOpenSkyAndTheShelterFactorUnderARoof() {
        float[] expected = {0.0F, 0.0F, 0.2F, 0.4F, 0.7F, 1.0F};
        assertEquals(expected.length, VortexField.SHELTER_FACTOR.length);
        for (int tier = 0; tier < expected.length; tier++) {
            assertEquals(1.0F, VortexField.exposure(tier, true), "tier " + tier + " in the open");
            assertEquals(expected[tier], VortexField.exposure(tier, false), "tier " + tier + " under a roof");
        }
        assertEquals(0.0F, VortexField.exposure(-3, false), "a tier below the table counts as EF0");
        assertEquals(1.0F, VortexField.exposure(9, false), "a tier above the table counts as EF5");
    }

    @Test
    void nothingActsOutsideTheInfluenceRadius() {
        FunnelSpine spine = upright(EF3, 1.0F, true);

        assertTrue(atRest(EF3, spine, 60.0D, GROUND_Y, 0.0D) != null, "60 blocks out is inside the 64 block field");
        assertTrue(atRest(EF3, spine, 64.5D, GROUND_Y, 0.0D) == null);
        assertTrue(atRest(EF3, spine, 0.0D, GROUND_Y, -70.0D) == null);
        assertTrue(atRest(EF3, spine, 46.0D, GROUND_Y, 46.0D) == null, "the field is round, not square");

        FunnelSpine weak = upright(EF3, 0.3F, true);
        float reach = VortexField.influenceRadius(EF3, 0.3F);
        assertEquals(41.6F, reach, 1.0E-4F, "the influence radius shrinks with the strength");
        assertTrue(atRest(EF3, weak, reach - 0.5D, GROUND_Y, 0.0D) != null);
        assertTrue(atRest(EF3, weak, reach + 0.5D, GROUND_Y, 0.0D) == null);
    }

    @Test
    void nothingActsAboveOrBelowTheReach() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double x = 12.0D;

        assertTrue(atRest(EF3, spine, x, TOP_Y + VortexField.REACH_ABOVE_BLOCKS - 0.5D, 0.0D) != null);
        assertTrue(atRest(EF3, spine, x, TOP_Y + VortexField.REACH_ABOVE_BLOCKS + 0.5D, 0.0D) == null);
        assertTrue(atRest(EF3, spine, x, GROUND_Y - VortexField.REACH_BELOW_BLOCKS + 0.5D, 0.0D) != null);
        assertTrue(atRest(EF3, spine, x, GROUND_Y - VortexField.REACH_BELOW_BLOCKS - 0.5D, 0.0D) == null);
        assertEquals(8.0D, VortexField.REACH_ABOVE_BLOCKS);
        assertEquals(4.0D, VortexField.REACH_BELOW_BLOCKS);
    }

    @Test
    void aFunnelAloftAndAVortexWithoutStrengthDoNothing() {
        assertTrue(atRest(EF3, upright(EF3, 1.0F, false), 12.0D, GROUND_Y, 0.0D) == null,
            "a funnel that has not touched down pushes nothing");
        assertTrue(atRest(EF3, upright(EF3, 0.0F, true), 6.0D, GROUND_Y, 0.0D) == null,
            "strength zero is no vortex");
    }

    @Test
    void thePullIsLargestAtTheCoreRadiusAndEqualsPullTimesStrength() {
        for (float strength : new float[] {0.3F, 0.8F, 1.0F}) {
            FunnelSpine spine = upright(EF3, strength, true);
            double core = spine.baseRadius();

            double atCore = inward(atRest(EF3, spine, core, GROUND_Y, 0.0D), core, 0.0D);
            assertEquals(EF3.pull() * strength, atCore, TOLERANCE, "pull at the core radius, strength " + strength);

            for (double factor : new double[] {0.1D, 0.25D, 0.5D, 0.75D, 0.95D, 1.05D, 1.5D, 2.0D, 3.0D}) {
                double r = core * factor;
                double there = inward(atRest(EF3, spine, r, GROUND_Y, 0.0D), r, 0.0D);
                assertTrue(there < atCore, "the pull at " + factor + " core radii is weaker than at the core radius");
                assertTrue(there > 0.0D, "the pull always points to the axis");
            }
            double half = inward(atRest(EF3, spine, core * 0.5D, GROUND_Y, 0.0D), core * 0.5D, 0.0D);
            double twice = inward(atRest(EF3, spine, core * 2.0D, GROUND_Y, 0.0D), core * 2.0D, 0.0D);
            assertEquals(atCore * 0.5D, half, TOLERANCE, "solid-body core: the pull grows with the distance");
            assertEquals(atCore * 0.5D, twice, TOLERANCE, "free vortex outside: the pull falls with the distance");
        }
    }

    @Test
    void thePullPointsAtTheAxisFromEverySide() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double[][] spots = {{20.0D, 0.0D}, {0.0D, 20.0D}, {-20.0D, 0.0D}, {0.0D, -20.0D}, {14.0D, -14.0D}};
        for (double[] spot : spots) {
            Vector3d out = new Vector3d();
            // A body already at the target speed gets no tangential push, so what is left is the pull alone.
            double r = Math.hypot(spot[0], spot[1]);
            double tangentX = spot[1] / r;
            double tangentZ = -spot[0] / r;
            assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, spot[0], GROUND_Y, spot[1],
                tangentX * 5.0D, 0.0D, tangentZ * 5.0D, 1.0F, out));
            double along = -(out.x * spot[0] + out.z * spot[1]) / r;
            double across = out.x * tangentX + out.z * tangentZ;
            assertTrue(along > 0.0D, "pulled toward the axis at " + spot[0] + ", " + spot[1]);
            assertEquals(0.0D, across, TOLERANCE, "nothing but the pull acts on a body that is fast enough");
        }
    }

    @Test
    void theTangentialPushIsCounterClockwiseSeenFromAbove() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double r = spine.baseRadius();

        Vector3d east = atRest(EF3, spine, r, GROUND_Y, 0.0D);
        Vector3d north = atRest(EF3, spine, 0.0D, GROUND_Y, -r);
        Vector3d west = atRest(EF3, spine, -r, GROUND_Y, 0.0D);
        Vector3d south = atRest(EF3, spine, 0.0D, GROUND_Y, r);

        assertTrue(east.z < 0.0D, "a body east of the axis is turned north");
        assertTrue(north.x < 0.0D, "a body north of the axis is turned west");
        assertTrue(west.z > 0.0D, "a body west of the axis is turned south");
        assertTrue(south.x > 0.0D, "a body south of the axis is turned east");
    }

    @Test
    void theTangentialPushIsCappedAndStopsAtTheTargetSpeed() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double r = spine.baseRadius();
        double target = 23.0D / 20.0D;
        double cap = EF3.pull() * VortexField.TANGENTIAL_RATIO;

        Vector3d fromRest = atRest(EF3, spine, r, GROUND_Y, 0.0D);
        assertTrue(target * VortexField.TANGENTIAL_RESPONSE > cap, "from rest the response alone would exceed the cap");
        assertEquals(cap, -fromRest.z, TOLERANCE, "the push is capped at pull times the tangential ratio");
        assertEquals(-EF3.pull(), fromRest.x, TOLERANCE, "the pull is not touched by the tangential part");

        Vector3d out = new Vector3d();
        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D, -0.9D * target,
            1.0F, out));
        assertEquals(0.1D * target * VortexField.TANGENTIAL_RESPONSE, -out.z, TOLERANCE,
            "below the cap the push closes a share of the missing speed");

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D, -target, 1.0F,
            out));
        assertEquals(0.0D, out.z, TOLERANCE, "a body at the target speed is pushed no further");

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D, -3.0D, 1.0F, out));
        assertEquals(0.0D, out.z, TOLERANCE, "a body faster than the target is never braked");

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D, 2.0D, 1.0F, out));
        assertEquals(cap, -out.z, TOLERANCE, "a body moving against the vortex is turned at the cap");
    }

    @Test
    void theTargetSpeedFollowsTheRankineProfileAndTheStrength() {
        FunnelSpine spine = upright(EF3, 0.8F, true);
        double core = spine.baseRadius();
        double r = core * 2.0D;
        double target = 23.0D / 20.0D * 0.5D * 0.8F;
        Vector3d out = new Vector3d();

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D,
            -(target - 0.1D), 1.0F, out));
        assertEquals(0.1D * VortexField.TANGENTIAL_RESPONSE, -out.z, TOLERANCE,
            "two core radii out the target is half the wind speed times the strength");
        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, r, GROUND_Y, 0.0D, 0.0D, 0.0D,
            -(target + 0.01D), 1.0F, out));
        assertEquals(0.0D, out.z, TOLERANCE);
    }

    @Test
    void liftActsNearTheAxisOnly() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double core = spine.baseRadius();

        assertEquals(EF3.lift(), atRest(EF3, spine, core, GROUND_Y, 0.0D).y, TOLERANCE, "full lift at the core radius");
        assertEquals(EF3.lift() * 0.5D, atRest(EF3, spine, 0.0D, GROUND_Y, 0.0D).y, TOLERANCE,
            "half the lift on the axis, where the profile is zero");
        assertEquals(EF3.lift() * 0.5D, atRest(EF3, spine, core * 0.25D, GROUND_Y, 0.0D).y, TOLERANCE,
            "lift never falls below half inside the core");
        assertEquals(EF3.lift() * 0.75D, atRest(EF3, spine, core * 0.75D, GROUND_Y, 0.0D).y, TOLERANCE);
        assertTrue(atRest(EF3, spine, core * 1.45D, GROUND_Y, 0.0D).y > 0.0D, "lift reaches 1.5 core radii");
        assertEquals(0.0D, atRest(EF3, spine, core * 1.55D, GROUND_Y, 0.0D).y, "and stops beyond them");
        assertEquals(1.5F, VortexField.LIFT_RADIUS_FACTOR);
    }

    @Test
    void liftScalesWithHalfTheStrength() {
        for (float strength : new float[] {0.3F, 0.8F, 1.0F}) {
            FunnelSpine spine = upright(EF3, strength, true);
            double lift = atRest(EF3, spine, spine.baseRadius(), GROUND_Y, 0.0D).y;
            assertEquals(EF3.lift() * (0.5D + 0.5D * strength), lift, TOLERANCE, "lift at strength " + strength);
        }
    }

    @Test
    void liftStopsAboveTheEjectHeight() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double x = spine.baseRadius();

        assertTrue(atRest(EF3, spine, x, GROUND_Y + 39.5D, 0.0D).y > 0.0D, "an EF3 lifts up to 40 blocks");
        assertEquals(0.0D, atRest(EF3, spine, x, GROUND_Y + 40.5D, 0.0D).y, "and lets go above them");

        FunnelSpine weak = upright(EF3, 0.3F, true);
        double weakX = weak.baseRadius();
        assertTrue(atRest(EF3, weak, weakX, GROUND_Y + 25.5D, 0.0D).y > 0.0D,
            "at strength 0.3 the eject height is 0.65 of the type's: 26 blocks");
        assertEquals(0.0D, atRest(EF3, weak, weakX, GROUND_Y + 26.5D, 0.0D).y);

        VortexParams ef5 = TORNADOES[5];
        FunnelSpine tall = upright(ef5, 1.0F, true);
        double tallX = tall.baseRadius();
        assertTrue(atRest(ef5, tall, tallX, TOP_Y - 1.0D, 0.0D).y > 0.0D,
            "an eject height of 0 means the funnel top: an EF5 lifts all the way up");
        assertEquals(0.0D, atRest(ef5, tall, tallX, TOP_Y + 1.0D, 0.0D).y, "but not above the funnel");
    }

    @Test
    void liftStopsAtTheRiseCap() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double x = spine.baseRadius();
        Vector3d out = new Vector3d();

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, x, GROUND_Y, 0.0D, 0.0D, 0.39D, 0.0D, 1.0F, out));
        assertEquals(EF3.lift(), out.y, TOLERANCE, "just below the rise cap the lift is whole");

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, x, GROUND_Y, 0.0D, 0.0D, EF3.riseCap(), 0.0D,
            1.0F, out));
        assertEquals(0.0D, out.y, "at the rise cap no lift is added");
        assertEquals(-EF3.pull(), out.x, TOLERANCE, "the pull does not care about the rise cap");

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, x, GROUND_Y, 0.0D, 0.0D, 1.0D, 0.0D, 1.0F, out));
        assertEquals(0.0D, out.y);

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, x, GROUND_Y, 0.0D, 0.0D, -2.0D, 0.0D, 1.0F, out));
        assertEquals(EF3.lift(), out.y, TOLERANCE, "a falling body is lifted");
    }

    @Test
    void exposureScalesEveryPart() {
        FunnelSpine spine = upright(EF3, 0.8F, true);
        Vector3d open = new Vector3d();
        Vector3d sheltered = new Vector3d();
        float shelter = VortexField.exposure(3, false);

        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, 9.0D, GROUND_Y + 2.0D, 4.0D, 0.1D, 0.05D, -0.2D,
            1.0F, open));
        assertTrue(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, 9.0D, GROUND_Y + 2.0D, 4.0D, 0.1D, 0.05D, -0.2D,
            shelter, sheltered));

        assertTrue(open.x != 0.0D && open.y > 0.0D && open.z != 0.0D, "pull, push and lift all act here");
        assertEquals(open.x * shelter, sheltered.x, TOLERANCE);
        assertEquals(open.y * shelter, sheltered.y, TOLERANCE);
        assertEquals(open.z * shelter, sheltered.z, TOLERANCE);

        Vector3d none = new Vector3d(1.0D, 2.0D, 3.0D);
        assertFalse(VortexField.acceleration(EF3, spine, 0.0D, 0.0D, 9.0D, GROUND_Y + 2.0D, 4.0D, 0.1D, 0.05D, -0.2D,
            VortexField.exposure(1, false), none), "under a roof an EF1 moves nothing");
        assertEquals(new Vector3d(), none);
    }

    @Test
    void theForcesFadeTowardTheInfluenceRadius() {
        FunnelSpine spine = upright(EF3, 1.0F, true);
        double start = 64.0D * VortexField.EDGE_FADE_START;

        double inside = inward(atRest(EF3, spine, start - 1.0D, GROUND_Y, 0.0D), start - 1.0D, 0.0D);
        assertEquals(EF3.pull() * 12.0D / (start - 1.0D), inside, TOLERANCE, "no fade inside 70 % of the radius");

        double r = 54.4D;
        double fade = (64.0D - r) / (64.0D - start);
        double fading = inward(atRest(EF3, spine, r, GROUND_Y, 0.0D), r, 0.0D);
        assertEquals(EF3.pull() * 12.0D / r * fade, fading, 1.0E-5D, "halfway through the fade band half is left");
        assertEquals(0.5D, fade, 1.0E-6D);
    }

    @Test
    void theAxisFollowsTheSpineAndTheStormCentre() {
        List<Vector3f> points = new ArrayList<>();
        int last = FunnelSpine.POINTS - 1;
        for (int i = 0; i <= last; i++) {
            float t = i / (float) last;
            points.add(new Vector3f(48.0F * t, (float) (GROUND_Y + (TOP_Y - GROUND_Y) * t), 0.0F));
        }
        FunnelSpine leaning = new FunnelSpine(points, 12.0F, 30.0F, 1.0F, 1.0F, true);
        Vector3d out = new Vector3d();

        assertTrue(VortexField.acceleration(EF3, leaning, 1000.0D, -500.0D, 1010.0D, GROUND_Y, -500.0D, 0.0D, 0.0D,
            0.0D, 1.0F, out));
        assertTrue(out.x < 0.0D, "at the ground the axis is the storm centre: a body east of it is pulled west");

        double halfway = (GROUND_Y + TOP_Y) / 2.0D;
        assertTrue(VortexField.acceleration(EF3, leaning, 1000.0D, -500.0D, 1010.0D, halfway, -500.0D, 0.0D, 0.0D,
            0.0D, 1.0F, out));
        assertTrue(out.x > 0.0D, "halfway up the axis has leaned 24 blocks east: the same body is pulled east");
    }

    @Test
    void aBodyOnTheAxisGetsNoNaN() {
        for (VortexParams params : TORNADOES) {
            FunnelSpine spine = upright(params, 1.0F, true);
            Vector3d out = new Vector3d();

            assertTrue(VortexField.acceleration(params, spine, 0.0D, 0.0D, 0.0D, GROUND_Y + 1.0D, 0.0D, 0.3D, 0.0D,
                -0.3D, 1.0F, out));
            assertTrue(out.isFinite(), "finite acceleration on the axis of an EF" + params.tier());
            assertEquals(0.0D, out.x, "no direction to pull in on the axis");
            assertEquals(0.0D, out.z);
            assertEquals(params.lift() * 0.5D, out.y, TOLERANCE);
        }
    }

    @Test
    void tiersTwoToFiveCarryABodyAtEveryGroundedStrength() {
        assertTrue(TORNADOES[2].lift() * VortexField.liftScale(0.3F) > GRAVITY,
            "the weakest grounded EF2 out-lifts gravity");
        for (int tier = 2; tier <= 5; tier++) {
            VortexParams params = TORNADOES[tier];
            double wanted = params.ejectHeight() > 0.0F ? Math.min(4.0D, params.ejectHeight()) : 4.0D;
            for (float strength : new float[] {0.3F, 0.8F, 1.0F}) {
                double risen = highestRise(params, strength);
                assertTrue(risen >= wanted, "an EF" + tier + " at strength " + strength + " lifts a body " + wanted
                    + " blocks within " + RISE_TICKS + " ticks, it rose " + risen);
            }
        }
    }

    @Test
    void tiersZeroAndOneAndTheSmallVorticesNeverLiftABody() {
        assertTrue(TORNADOES[1].lift() * VortexField.liftScale(1.0F) < GRAVITY, "the strongest EF1 loses to gravity");
        for (int tier = 0; tier <= 1; tier++) {
            assertEquals(0.0D, highestRise(TORNADOES[tier], 1.0F), "an EF" + tier + " leaves a body on the ground");
        }
        for (VortexParams params : SMALL_VORTICES) {
            assertEquals(0.0D, highestRise(params, 1.0F),
                "a " + params.kind().getSerializedName() + " leaves a body on the ground");
        }
    }

    /**
     * Simulates a body with a living entity's gravity and vertical drag standing at the core radius under open
     * sky and returns how far above the ground it got within {@link #RISE_TICKS} ticks.
     */
    private static double highestRise(VortexParams params, float strength) {
        FunnelSpine spine = upright(params, strength, true);
        double x = spine.baseRadius();
        double y = GROUND_Y;
        double vy = 0.0D;
        double highest = 0.0D;
        Vector3d out = new Vector3d();
        for (int tick = 0; tick < RISE_TICKS; tick++) {
            VortexField.acceleration(params, spine, 0.0D, 0.0D, x, y, 0.0D, 0.0D, vy, 0.0D, 1.0F, out);
            double next = (vy + out.y - GRAVITY) * VERTICAL_DRAG;
            if (y <= GROUND_Y && next < 0.0D) {
                vy = 0.0D;
            } else {
                vy = next;
                y = Math.max(GROUND_Y, y + vy);
            }
            highest = Math.max(highest, y - GROUND_Y);
        }
        return highest;
    }

    /** The part of an acceleration that points from {@code (x, z)} toward the axis at the origin. */
    private static double inward(Vector3d acceleration, double x, double z) {
        double r = Math.hypot(x, z);
        return -(acceleration.x * x + acceleration.z * z) / r;
    }
}
