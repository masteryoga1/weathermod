package com.weathermod.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.client.particle.CanopyMath.BlockKind;
import com.weathermod.client.particle.CanopyMath.LeafSite;
import com.weathermod.core.Headings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Checks the canopy rules against small hand-built worlds: which face of a column sheds a leaf and at what height,
 * what keeps a face from shedding, that the sampler around the camera finds the canopy often enough to show a gale,
 * within its bounds, and from where the leaves off a pile of leaf blocks can and cannot be seen. Needs no Minecraft
 * bootstrap.
 */
class CanopyMathTest {
    private static final double TOLERANCE = 1.0E-9D;
    /** Y of the ground surface block of the test worlds; everything at or below it is solid. */
    private static final int GROUND = 63;
    /** Eye height of a player standing on the ground of the test worlds. */
    private static final double EYE_Y = GROUND + 1 + 1.62D;
    private static final double NO_LIMIT_LOW = -1000.0D;
    private static final double NO_LIMIT_HIGH = 1000.0D;
    /** Expected leaves per tick in the 14 blocks per second gale of the in-game test. */
    private static final float GALE = EmitterMath.leafExpected(14.0F, 14.0F, 1.0D);
    private static final int TEN_SECONDS = 200;
    /** The pile of leaf blocks of the in-game test spans these columns: 6 blocks along x and 7 along z. */
    private static final int PILE_WEST = 4;
    private static final int PILE_EAST = 9;
    private static final int PILE_NORTH = -3;
    private static final int PILE_SOUTH = 3;
    private static final int PILE_HEIGHT = 4;
    /** Heading and speed of the wind of the in-game test: {@code /weathermod wind set 90 14}, an east wind. */
    private static final float GALE_HEADING = 90.0F;
    private static final float GALE_SPEED = 14.0F;
    /** The vanilla particle tick subtracts this times the particle's gravity from its vertical speed. */
    private static final double VANILLA_GRAVITY_STEP = 0.04D;
    /** The vanilla particle tick multiplies the velocity by this after the move. */
    private static final double VANILLA_FRICTION = 0.98D;
    /** Half the height of the screen at one block of depth, for the default 70 degree field of view. */
    private static final double VIEW_HALF_HEIGHT = Math.tan(Math.toRadians(35.0D));
    /** Half the width of a 16:9 screen at one block of depth. */
    private static final double VIEW_HALF_WIDTH = VIEW_HALF_HEIGHT * 16.0D / 9.0D;
    /** The viewer of the sightline tests looks up by this much, toward the top of the pile. */
    private static final double VIEW_PITCH = Math.toRadians(20.0D);
    /** Farther away than this a leaf is less than 16 pixels wide on a 4K screen and is not counted as seen. */
    private static final double SIGHT_RANGE = 32.0D;
    /** Distance between the points of a sightline that are tested for a block in the way. */
    private static final double SIGHT_STEP = 0.1D;
    private static final int WATCH_TICKS = 400;
    private static final int WATCH_WARM_UP = 100;
    private static final int WATCH_EVERY = 4;

    @Test
    void aColumnWithoutLeavesOnTopShedsNothing() {
        World world = new World();
        assertTrue(site(world, 0.5D, 0.5D, true).isEmpty(), "bare ground");
        world.solid(0, GROUND + 1, 0);
        assertTrue(site(world, 0.5D, 0.5D, false).isEmpty(), "a block that is not a leaf block");
        assertEquals(2, world.heightReads, "one heightmap read per column");
        assertEquals(2, world.blockReads, "one block read to see the column has no leaves on top");
    }

    @Test
    void aTreeUnderARoofShedsNothing() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).solid(0, GROUND + 9, 0);
        assertTrue(site(world, 0.5D, 0.5D, true).isEmpty());
        assertTrue(site(world, 0.5D, 0.5D, false).isEmpty());
    }

    @Test
    void anUnloadedColumnShedsNothing() {
        int minBuildHeight = -64;
        Optional<LeafSite> found = CanopyMath.leafSite((x, z) -> minBuildHeight, (x, y, z) -> BlockKind.AIR,
            0.5D, 0.5D, 1.0D, 0.0D, true, NO_LIMIT_LOW, NO_LIMIT_HIGH);
        assertTrue(found.isEmpty());
    }

    @Test
    void theTopFaceShedsJustAboveTheCanopy() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0);
        LeafSite found = site(world, 0.5D, 0.5D, true).orElseThrow();
        assertTrue(found.fromTop());
        assertEquals(GROUND + 7 + CanopyMath.LEAF_LIFT, found.y(), TOLERANCE);
    }

    @Test
    void theUndersideShedsBelowTheLowestLeafOfTheColumn() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0);
        LeafSite found = site(world, 0.5D, 0.5D, false).orElseThrow();
        assertFalse(found.fromTop());
        assertEquals(GROUND + 4 - CanopyMath.LEAF_DROP, found.y(), TOLERANCE);
        assertEquals(0.3D, CanopyMath.LEAF_DROP, TOLERANCE);
        assertEquals(0.05D, CanopyMath.LEAF_LIFT, TOLERANCE);
    }

    @Test
    void aTopFaceBlockedDownwindFallsBackToTheUnderside() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).leaves(1, GROUND + 5, GROUND + 7, 0);
        LeafSite found = site(world, 0.5D, 0.5D, true).orElseThrow();
        assertFalse(found.fromTop(), "the canopy steps up downwind, so the top leaf would hit it at once");
        assertEquals(GROUND + 4 - CanopyMath.LEAF_DROP, found.y(), TOLERANCE);
    }

    @Test
    void anUndersideOnTheGroundFallsBackToTheTopFace() {
        World world = new World().leaves(0, GROUND + 1, GROUND + 4, 0);
        LeafSite found = site(world, 0.5D, 0.5D, false).orElseThrow();
        assertTrue(found.fromTop(), "a leaf block resting on the ground can only shed off its top");
        assertEquals(GROUND + 5 + CanopyMath.LEAF_LIFT, found.y(), TOLERANCE);
    }

    @Test
    void leavesOverALogShedOnlyOffTheTop() {
        World world = new World().solid(0, GROUND + 1, 0).solid(0, GROUND + 2, 0).leaves(0, GROUND + 3, GROUND + 4, 0);
        LeafSite found = site(world, 0.5D, 0.5D, false).orElseThrow();
        assertTrue(found.fromTop());
        world.solid(1, GROUND + 5, 0);
        assertTrue(site(world, 0.5D, 0.5D, false).isEmpty(), "and not at all once the top is blocked downwind");
    }

    @Test
    void anUndersideBlockedDownwindFallsBackToTheTopFace() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).leaves(1, GROUND + 3, GROUND + 5, 0);
        LeafSite found = site(world, 0.5D, 0.5D, false).orElseThrow();
        assertTrue(found.fromTop(), "the canopy steps down downwind, so the underside leaf would hit it at once");
    }

    @Test
    void aColumnBoxedInOnBothFacesShedsNothing() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).leaves(1, GROUND + 3, GROUND + 7, 0);
        assertTrue(site(world, 0.5D, 0.5D, true).isEmpty());
        assertTrue(site(world, 0.5D, 0.5D, false).isEmpty());
    }

    @Test
    void snowOnTheCanopyKeepsTheTopFaceFromShedding() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).passable(0, GROUND + 7, 0);
        LeafSite found = site(world, 0.5D, 0.5D, true).orElseThrow();
        assertFalse(found.fromTop(), "a snow layer does not raise the heightmap but covers the top face");
    }

    @Test
    void theDownwindBlockFollowsTheWind() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0).leaves(0, GROUND + 5, GROUND + 7, -1);
        assertTrue(top(world, 0.5D, 0.5D, 1.0D, 0.0D).fromTop(), "an east wind is not blocked by a block to the north");
        assertFalse(top(world, 0.5D, 0.5D, 0.0D, -1.0D).fromTop(), "a north wind is");
        assertTrue(top(world, 0.5D, 0.5D, 0.0D, 1.0D).fromTop(), "a south wind blows away from it");
        double diagonal = Math.sqrt(0.5D);
        assertTrue(top(world, 0.2D, 0.9D, diagonal, -diagonal).fromTop(),
            "one step along a north-east wind from the south-west corner is still inside the column");
        assertFalse(top(world, 0.2D, 0.2D, diagonal, -diagonal).fromTop(),
            "from the north-west corner the same step ends in the block to the north");
        assertTrue(top(world, 0.5D, 0.5D, 0.0D, 0.0D).fromTop(), "without a wind direction only the column counts");
    }

    @Test
    void theHeightWindowAppliesToEachFace() {
        World world = new World().leaves(0, GROUND + 4, GROUND + 6, 0);
        double topY = GROUND + 7 + CanopyMath.LEAF_LIFT;
        double undersideY = GROUND + 4 - CanopyMath.LEAF_DROP;
        LeafSite belowTheTop = CanopyMath.leafSite(world, world, 0.5D, 0.5D, 1.0D, 0.0D, true,
            NO_LIMIT_LOW, topY - 0.01D).orElseThrow();
        assertFalse(belowTheTop.fromTop(), "the top face is above the window, the underside is not");
        LeafSite aboveTheUnderside = CanopyMath.leafSite(world, world, 0.5D, 0.5D, 1.0D, 0.0D, false,
            undersideY + 0.01D, NO_LIMIT_HIGH).orElseThrow();
        assertTrue(aboveTheUnderside.fromTop(), "the underside is below the window, the top face is not");
        assertTrue(CanopyMath.leafSite(world, world, 0.5D, 0.5D, 1.0D, 0.0D, true,
            undersideY + 0.01D, topY - 0.01D).isEmpty(), "neither face is inside the window");
        assertTrue(CanopyMath.leafSite(world, world, 0.5D, 0.5D, 1.0D, 0.0D, true, topY, topY).isPresent(),
            "the window includes its ends");
    }

    @Test
    void aCanopyThickerThanTheScanDepthHasNoUnderside() {
        int bottom = GROUND + 10;
        World scannable = new World().leaves(0, bottom, bottom + CanopyMath.LEAF_SCAN_DEPTH - 1, 0);
        LeafSite found = site(scannable, 0.5D, 0.5D, false).orElseThrow();
        assertFalse(found.fromTop());
        assertEquals(bottom - CanopyMath.LEAF_DROP, found.y(), TOLERANCE);
        World tooThick = new World().leaves(0, bottom, bottom + CanopyMath.LEAF_SCAN_DEPTH, 0);
        assertTrue(site(tooThick, 0.5D, 0.5D, false).orElseThrow().fromTop(),
            "the search gives up on the underside and takes the top face");
        assertEquals(8, CanopyMath.LEAF_SCAN_DEPTH);
    }

    @Test
    void aColumnNeverCostsMoreThanAFixedNumberOfLookups() {
        int bottom = GROUND + 10;
        int top = bottom + CanopyMath.LEAF_SCAN_DEPTH - 1;
        World world = new World().leaves(0, bottom, top, 0).solid(1, top + 1, 0).solid(1, bottom - 1, 0);
        assertTrue(site(world, 0.5D, 0.5D, true).isEmpty());
        int worst = 2 + 2 + CanopyMath.LEAF_SCAN_DEPTH + 2;
        assertEquals(worst, world.heightReads + world.blockReads,
            "the column, both cells of the top face, the walk down and both cells below");
        World again = new World().leaves(0, bottom, top, 0).solid(1, top + 1, 0).solid(1, bottom - 1, 0);
        assertTrue(site(again, 0.5D, 0.5D, false).isEmpty());
        assertEquals(worst, again.heightReads + again.blockReads, "the same whichever face is tried first");
    }

    @Test
    void insetInBlockKeepsThePointInsideItsBlockAndAwayFromTheEdges() {
        assertEquals(3.15D, CanopyMath.insetInBlock(3.0D), TOLERANCE);
        assertEquals(3.5D, CanopyMath.insetInBlock(3.5D), TOLERANCE);
        assertEquals(3.85D, CanopyMath.insetInBlock(4.0D - 1.0E-12D), 1.0E-9D);
        assertEquals(-0.325D, CanopyMath.insetInBlock(-0.25D), TOLERANCE, "block -1, three quarters of the way in");
        assertEquals(-0.85D, CanopyMath.insetInBlock(-1.0D), TOLERANCE);
        RandomSource random = RandomSource.create(5L);
        for (int i = 0; i < 2_000; i++) {
            double coordinate = (random.nextDouble() - 0.5D) * 200.0D;
            double inset = CanopyMath.insetInBlock(coordinate);
            double block = Math.floor(coordinate);
            assertTrue(inset >= block + CanopyMath.LEAF_EDGE_MARGIN - TOLERANCE, "too close to the low edge");
            assertTrue(inset <= block + 1.0D - CanopyMath.LEAF_EDGE_MARGIN + TOLERANCE, "too close to the high edge");
        }
    }

    @Test
    void aLeafBlockPileNearTheCameraShedsSeveralLeavesASecondInAGale() {
        // Supply only: how many leaves the 6 x 4 x 7 pile of the in-game test gives up, resting on the ground and
        // floating two blocks above it. Whether its viewer gets to see them is the next test.
        World resting = pile(GROUND + 1);
        World floating = pile(GROUND + 3);
        List<Leaf> offTheGround = shedFor(resting, 11L, GALE, TEN_SECONDS);
        assertTrue(offTheGround.size() >= 80, "at least eight leaves a second, got " + offTheGround.size());
        assertTrue(offTheGround.stream().allMatch(Leaf::fromTop), "a pile on the ground only sheds off its top");
        List<Leaf> offTheFloating = shedFor(floating, 12L, GALE, TEN_SECONDS);
        assertTrue(offTheFloating.size() >= 80, "got " + offTheFloating.size() + " in 10 s");
        long fromTop = offTheFloating.stream().filter(Leaf::fromTop).count();
        assertTrue(fromTop > offTheFloating.size() / 4 && fromTop < offTheFloating.size() * 3L / 4L,
            "a floating pile sheds off both faces, got " + fromTop + " of " + offTheFloating.size() + " off the top");
    }

    @Test
    void leavesOffAPileOnTheGroundShowFromTheSideAndFromDownwindButNotFromUpwind() {
        // Off a pile that rests on the ground every leaf leaves the top face, 2.4 blocks above the eye, and is
        // carried downwind half a block a tick while it climbs a tenth of one at most: far flatter than the sightline
        // of a viewer who stands upwind, 3.5 blocks from the pile, which climbs 0.68 blocks per block over its edge.
        World resting = pile(GROUND + 1);
        double middleX = (PILE_WEST + PILE_EAST + 1) / 2.0D;
        Sight fromUpwind = watch(resting, 0.5D, 0.5D, 1.0D, 0.0D, 51L);
        assertTrue(fromUpwind.shed() >= 100, "the pile sheds all the while, got " + fromUpwind.shed());
        assertEquals(0, fromUpwind.mostInSight(),
            "west of the pile, with the wind at the viewer's back, the pile hides every leaf wherever they look");
        Sight fromTheSide = watch(resting, middleX, PILE_SOUTH + 4.5D, 0.0D, -1.0D, 52L);
        assertTrue(fromTheSide.meanInView() >= 3.0D,
            "south of the pile the leaves cross the screen, on average " + fromTheSide.meanInView() + " at a time");
        Sight fromDownwind = watch(resting, PILE_EAST + 4.5D, 0.5D, -1.0D, 0.0D, 53L);
        assertTrue(fromDownwind.meanInView() >= 1.5D,
            "east of the pile the leaves come over its top at the viewer, on average " + fromDownwind.meanInView());

        Sight underAFloatingPile = watch(pile(GROUND + 3), 0.5D, 0.5D, 1.0D, 0.0D, 54L);
        assertTrue(underAFloatingPile.meanInView() >= 10.0D,
            "a pile two blocks up drops leaves from its underside at eye height, which show even from upwind: "
                + underAFloatingPile.meanInView());
    }

    @Test
    void aLoneTreeShedsVisiblyAndAForestSuppliesEveryLeafTheGaleAsksFor() {
        World loneTree = new World();
        plantOak(loneTree, 6, 0, 5);
        int fromTheTree = shedFor(loneTree, 21L, GALE, TEN_SECONDS).size();
        assertTrue(fromTheTree >= 50, "a single oak 6 blocks away sheds a few leaves a second, got " + fromTheTree);

        World forest = new World();
        RandomSource layout = RandomSource.create(3L);
        for (int gridX = -30; gridX <= 30; gridX += 5) {
            for (int gridZ = -30; gridZ <= 30; gridZ += 5) {
                int x = gridX + layout.nextInt(3) - 1;
                int z = gridZ + layout.nextInt(3) - 1;
                if (Math.abs(x) > 1 || Math.abs(z) > 1) {
                    plantOak(forest, x, z, 4 + layout.nextInt(3));
                }
            }
        }
        RandomSource random = RandomSource.create(22L);
        int wanted = 0;
        int shed = 0;
        for (int tick = 0; tick < TEN_SECONDS; tick++) {
            int now = EmitterMath.stochasticCount(GALE, random);
            wanted += now;
            shed += CanopyMath.shedLeaves(random, forest, forest, 0.5D, EYE_Y, 0.5D, 1.0D, 0.0D, now,
                (x, y, z, fromTop) -> { });
        }
        assertTrue(wanted >= 45 * 10, "the gale asks for about fifty leaves a second, got " + wanted + " in 10 s");
        assertTrue(shed >= wanted * 0.95D, "a forest answers nearly every leaf: " + shed + " of " + wanted);
        int inABreeze = shedFor(forest, 23L, EmitterMath.leafExpected(4.5F, 4.5F, 1.0D), TEN_SECONDS).size();
        assertTrue(inABreeze >= 1 && inABreeze <= 30, "a breeze sheds the odd leaf, got " + inABreeze + " in 10 s");
    }

    @Test
    void everyLeafAppearsInTheRingAroundTheCameraInsideTheParticleLimiter() {
        World world = new World();
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                int bottom = GROUND + 2 + Math.floorMod(x * 7 + z * 13, 22);
                world.leaves(x, bottom, bottom + 1 + Math.floorMod(x + z, 3), z);
            }
        }
        double camX = 0.3D;
        double camZ = -0.7D;
        List<Leaf> leaves = shedFor(world, camX, camZ, 31L, 3.0F, 400);
        assertTrue(leaves.size() > 500, "the canopy is everywhere, got " + leaves.size());
        double inset = Math.hypot(CanopyMath.LEAF_EDGE_MARGIN, CanopyMath.LEAF_EDGE_MARGIN);
        boolean sawTop = false;
        boolean sawUnderside = false;
        for (Leaf leaf : leaves) {
            double horizontal = Math.hypot(leaf.x() - camX, leaf.z() - camZ);
            assertTrue(horizontal >= EmitterMath.LEAF_MIN_DISTANCE - inset, "too close: " + horizontal);
            assertTrue(horizontal <= EmitterMath.LEAF_DISTANCE + inset, "too far: " + horizontal);
            assertTrue(leaf.y() >= EYE_Y - EmitterMath.LEAF_BELOW_CAMERA, "too low: " + leaf.y());
            assertTrue(leaf.y() <= EYE_Y + EmitterMath.LEAF_ABOVE_CAMERA, "too high: " + leaf.y());
            double distance = Math.sqrt(horizontal * horizontal + (leaf.y() - EYE_Y) * (leaf.y() - EYE_Y));
            assertTrue(distance < 32.0D, "the vanilla particle path would drop a leaf " + distance + " blocks away");
            sawTop |= leaf.fromTop();
            sawUnderside |= !leaf.fromTop();
        }
        assertTrue(sawTop && sawUnderside, "both faces of the canopy shed");
    }

    @Test
    void theSamplerNeverExceedsWhatWasAskedNorItsTries() {
        World plains = new World();
        RandomSource random = RandomSource.create(41L);
        int[] calls = new int[1];
        assertEquals(0, CanopyMath.shedLeaves(random, plains, plains, 0.5D, EYE_Y, 0.5D, 1.0D, 0.0D, 4,
            (x, y, z, fromTop) -> calls[0]++));
        assertEquals(0, calls[0]);
        assertEquals(4 * EmitterMath.LEAF_TRIES_PER_LEAF, plains.heightReads, "open country costs the tries, no more");
        assertEquals(4 * EmitterMath.LEAF_TRIES_PER_LEAF, plains.blockReads);

        World canopy = new World();
        for (int x = -22; x <= 22; x++) {
            for (int z = -22; z <= 22; z++) {
                canopy.leaves(x, GROUND + 4, GROUND + 5, z);
            }
        }
        assertEquals(5, CanopyMath.shedLeaves(random, canopy, canopy, 0.5D, EYE_Y, 0.5D, 1.0D, 0.0D, 5,
            (x, y, z, fromTop) -> calls[0]++));
        assertEquals(5, calls[0], "the sink sees each leaf once");
        assertEquals(5, canopy.heightReads, "under a closed canopy the first try for each leaf succeeds");
        assertEquals(0, CanopyMath.shedLeaves(random, canopy, canopy, 0.5D, EYE_Y, 0.5D, 1.0D, 0.0D, 0,
            (x, y, z, fromTop) -> calls[0]++));
        assertEquals(0, CanopyMath.shedLeaves(random, canopy, canopy, 0.5D, EYE_Y, 0.5D, 1.0D, 0.0D, -3,
            (x, y, z, fromTop) -> calls[0]++));
        assertEquals(5, calls[0]);
        assertEquals(5, canopy.heightReads, "asking for nothing reads nothing");
    }

    private static Optional<LeafSite> site(World world, double x, double z, boolean preferTop) {
        return CanopyMath.leafSite(world, world, x, z, 1.0D, 0.0D, preferTop, NO_LIMIT_LOW, NO_LIMIT_HIGH);
    }

    private static LeafSite top(World world, double x, double z, double downwindX, double downwindZ) {
        return CanopyMath.leafSite(world, world, x, z, downwindX, downwindZ, true, NO_LIMIT_LOW, NO_LIMIT_HIGH)
            .orElseThrow();
    }

    private static List<Leaf> shedFor(World world, long seed, float expectedPerTick, int ticks) {
        return shedFor(world, 0.5D, 0.5D, seed, expectedPerTick, ticks);
    }

    /** Runs the sampler as the emitter does, with an east wind, for a camera at eye height over the ground. */
    private static List<Leaf> shedFor(World world, double camX, double camZ, long seed, float expectedPerTick,
                                      int ticks) {
        RandomSource random = RandomSource.create(seed);
        List<Leaf> leaves = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            int wanted = EmitterMath.stochasticCount(expectedPerTick, random);
            int shed = CanopyMath.shedLeaves(random, world, world, camX, EYE_Y, camZ, 1.0D, 0.0D, wanted,
                (x, y, z, fromTop) -> leaves.add(new Leaf(x, y, z, fromTop)));
            assertTrue(shed <= wanted, "never more leaves than the tick asked for");
        }
        return leaves;
    }

    /** The 6 x 4 x 7 pile of leaf blocks of the in-game test, 4 to 9 blocks east of the origin, on a bottom layer. */
    private static World pile(int bottomY) {
        World world = new World();
        for (int x = PILE_WEST; x <= PILE_EAST; x++) {
            for (int z = PILE_NORTH; z <= PILE_SOUTH; z++) {
                world.leaves(x, bottomY, bottomY + PILE_HEIGHT - 1, z);
            }
        }
        return world;
    }

    /**
     * Lets the gale of the in-game test blow for a while and reports what a viewer standing on the ground at a place
     * and looking along a level direction, pitched up by {@link #VIEW_PITCH}, gets to see of the leaves in flight. A
     * leaf counts once it is at least half grown, within {@link #SIGHT_RANGE} and not hidden behind a block.
     */
    private static Sight watch(World world, double camX, double camZ, double lookX, double lookZ, long seed) {
        RandomSource random = RandomSource.create(seed);
        double[] wind = EmitterMath.windVelocity(GALE_HEADING, GALE_SPEED, EmitterMath.LEAF_DRIFT);
        double downwindX = Headings.directionX(GALE_HEADING);
        double downwindZ = Headings.directionZ(GALE_HEADING);
        List<Flight> flights = new ArrayList<>();
        int shed = 0;
        int samples = 0;
        long inView = 0L;
        int mostInSight = 0;
        for (int tick = 0; tick < WATCH_TICKS; tick++) {
            flights.removeIf(flight -> !flight.tick(wind[0], wind[1]));
            int wanted = EmitterMath.stochasticCount(GALE, random);
            shed += CanopyMath.shedLeaves(random, world, world, camX, EYE_Y, camZ, downwindX, downwindZ, wanted,
                (x, y, z, fromTop) -> flights.add(Flight.launch(random, x, y, z, wind, fromTop)));
            if (tick < WATCH_WARM_UP || tick % WATCH_EVERY != 0) {
                continue;
            }
            samples++;
            int inSight = 0;
            for (Flight flight : flights) {
                double dx = flight.x - camX;
                double dy = flight.y - EYE_Y;
                double dz = flight.z - camZ;
                if (EmitterMath.leafScale(flight.age, flight.lifetime) < 0.5F
                    || dx * dx + dy * dy + dz * dz > SIGHT_RANGE * SIGHT_RANGE
                    || !hasSightline(world, camX, EYE_Y, camZ, dx, dy, dz)) {
                    continue;
                }
                inSight++;
                if (isOnScreen(dx, dy, dz, lookX, lookZ)) {
                    inView++;
                }
            }
            mostInSight = Math.max(mostInSight, inSight);
        }
        return new Sight(shed, inView / (double) samples, mostInSight);
    }

    /** Whether no block, leaf blocks included, lies between a viewer and the point at an offset from the viewer. */
    private static boolean hasSightline(World world, double camX, double camY, double camZ, double dx, double dy,
                                        double dz) {
        int steps = Mth.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / SIGHT_STEP);
        for (int step = 1; step < steps; step++) {
            double along = step / (double) steps;
            BlockKind kind = world.kindAt(Mth.floor(camX + dx * along), Mth.floor(camY + dy * along),
                Mth.floor(camZ + dz * along));
            if (kind != BlockKind.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the point at an offset from a viewer lies on a 16:9 screen with the default field of view, for a
     * viewer who looks along the level unit direction {@code (lookX, lookZ)} pitched up by {@link #VIEW_PITCH}.
     */
    private static boolean isOnScreen(double dx, double dy, double dz, double lookX, double lookZ) {
        double level = dx * lookX + dz * lookZ;
        double right = dz * lookX - dx * lookZ;
        double depth = level * Math.cos(VIEW_PITCH) + dy * Math.sin(VIEW_PITCH);
        double up = dy * Math.cos(VIEW_PITCH) - level * Math.sin(VIEW_PITCH);
        return depth > 0.0D && Math.abs(right) <= depth * VIEW_HALF_WIDTH && Math.abs(up) <= depth * VIEW_HALF_HEIGHT;
    }

    /** A small oak: a trunk, two wide layers of leaves around its top and two narrow ones above. */
    private static void plantOak(World world, int x, int z, int trunkHeight) {
        int trunkTop = GROUND + trunkHeight;
        for (int y = GROUND + 1; y <= trunkTop; y++) {
            world.solid(x, y, z);
        }
        for (int layer = -2; layer <= 1; layer++) {
            int radius = layer < 0 ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean trimmedCorner = layer == -1 && Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    if (!trimmedCorner && world.isFree(x + dx, trunkTop + layer, z + dz)) {
                        world.leaves(x + dx, trunkTop + layer, trunkTop + layer, z + dz);
                    }
                }
            }
        }
    }

    /** A leaf the sampler reported. */
    private record Leaf(double x, double y, double z, boolean fromTop) {
    }

    /**
     * What a viewer gets to see of the leaves off a canopy.
     *
     * @param shed        leaves torn off while watching
     * @param meanInView  mean number of leaves on the screen with a free sightline
     * @param mostInSight most leaves with a free sightline at once, on the screen or not
     */
    private record Sight(int shed, double meanInView, int mostInSight) {
    }

    /**
     * A leaf in flight through open air, moved the way {@code WindLeafParticle} and the vanilla particle tick move
     * one: pulled toward the wind, then gravity, the step and the vanilla friction, and gone when its life runs out
     * or it reaches the ground. The flutter is left out, it shifts a leaf by less than a tenth of a block. Collisions
     * are left out too: a leaf off a flat pile meets nothing but the ground.
     */
    private static final class Flight {
        private final int lifetime;
        private double x;
        private double y;
        private double z;
        private double xd;
        private double yd;
        private double zd;
        private int age;

        private Flight(double x, double y, double z, double xd, double yd, double zd, int lifetime) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.xd = xd;
            this.yd = yd;
            this.zd = zd;
            this.lifetime = lifetime;
        }

        /** Starts a leaf as the emitter and the particle do: at the wind's velocity, kicked up or falling. */
        static Flight launch(RandomSource random, double x, double y, double z, double[] wind, boolean fromTop) {
            double kickRange = WeatherParticleEmitter.LEAF_KICK_MAX - WeatherParticleEmitter.LEAF_KICK_MIN;
            double rise = fromTop
                ? WeatherParticleEmitter.LEAF_KICK_MIN + random.nextDouble() * kickRange
                : WeatherParticleEmitter.LEAF_FALL_SPEED;
            int lifeRange = WindLeafParticle.MAX_LIFETIME - WindLeafParticle.MIN_LIFETIME;
            int lifetime = WindLeafParticle.MIN_LIFETIME + random.nextInt(lifeRange + 1);
            return new Flight(x, y, z, wind[0], rise, wind[1], lifetime);
        }

        /** Moves the leaf by one tick in a wind with the given carrying velocity; false once the leaf is gone. */
        boolean tick(double windX, double windZ) {
            xd = EmitterMath.leafPull(xd, windX);
            zd = EmitterMath.leafPull(zd, windZ);
            if (age++ >= lifetime) {
                return false;
            }
            yd -= VANILLA_GRAVITY_STEP * WindLeafParticle.GRAVITY;
            x += xd;
            y += yd;
            z += zd;
            xd *= VANILLA_FRICTION;
            yd *= VANILLA_FRICTION;
            zd *= VANILLA_FRICTION;
            return y > GROUND + 1;
        }
    }

    private record Position(int x, int y, int z) {
    }

    private record Column(int x, int z) {
    }

    /**
     * A flat world of solid ground up to {@link #GROUND} with a few blocks placed above it. It answers both lookups
     * the canopy rules use and counts how often each is asked.
     */
    private static final class World implements CanopyMath.HeightLookup, CanopyMath.BlockLookup {
        private final Map<Position, BlockKind> blocks = new HashMap<>();
        private final Map<Column, Integer> tops = new HashMap<>();
        private int heightReads;
        private int blockReads;

        /** Fills a column with leaf blocks from one height to another, both included. */
        World leaves(int x, int fromY, int toY, int z) {
            for (int y = fromY; y <= toY; y++) {
                place(x, y, z, BlockKind.LEAVES, true);
            }
            return this;
        }

        /** Places a block that is neither air nor leaves and blocks motion, such as a log or a roof. */
        World solid(int x, int y, int z) {
            place(x, y, z, BlockKind.OTHER, true);
            return this;
        }

        /** Places a block the heightmap looks through, such as a snow layer. */
        World passable(int x, int y, int z) {
            place(x, y, z, BlockKind.OTHER, false);
            return this;
        }

        boolean isFree(int x, int y, int z) {
            return y > GROUND && !blocks.containsKey(new Position(x, y, z));
        }

        private void place(int x, int y, int z, BlockKind kind, boolean blocksMotion) {
            blocks.put(new Position(x, y, z), kind);
            if (blocksMotion) {
                tops.merge(new Column(x, z), y, Math::max);
            }
        }

        @Override
        public int heightAt(int blockX, int blockZ) {
            heightReads++;
            return tops.getOrDefault(new Column(blockX, blockZ), GROUND) + 1;
        }

        @Override
        public BlockKind kindAt(int blockX, int blockY, int blockZ) {
            blockReads++;
            if (blockY <= GROUND) {
                return BlockKind.OTHER;
            }
            return blocks.getOrDefault(new Position(blockX, blockY, blockZ), BlockKind.AIR);
        }
    }
}
