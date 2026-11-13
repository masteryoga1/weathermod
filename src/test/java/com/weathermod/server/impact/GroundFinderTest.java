package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Pins the column scan that finds the ground under a ceiling: floors below and above the hint, the limits of the
 * scan, caves and ledges, and that a fluid surface counts as ground when the predicate says so. Only the pure
 * {@link GroundFinder#scan} is exercised, so no Minecraft bootstrap is needed.
 */
class GroundFinderTest {
    private static final int MIN_Y = -64;
    private static final int UP = GroundFinder.CEILING_SCAN_UP_BLOCKS;
    private static final int DOWN = GroundFinder.CEILING_SCAN_DOWN_BLOCKS;

    /** A column of rock up to and including {@code topSolidY} with nothing above it. */
    private static IntPredicate rockUpTo(int topSolidY) {
        return y -> y <= topSolidY;
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(5, GroundFinder.SAMPLE_INTERVAL_TICKS);
        assertEquals(8, GroundFinder.CEILING_SCAN_UP_BLOCKS);
        assertEquals(48, GroundFinder.CEILING_SCAN_DOWN_BLOCKS);
    }

    @Test
    void aFloorBelowTheHintIsFound() {
        assertEquals(OptionalInt.of(61), GroundFinder.scan(rockUpTo(60), 70, MIN_Y, UP, DOWN),
            "the first free block above the floor");
        assertEquals(OptionalInt.of(70), GroundFinder.scan(rockUpTo(69), 70, MIN_Y, UP, DOWN),
            "a hint that is the ground height already is confirmed");
        assertEquals(OptionalInt.of(23), GroundFinder.scan(rockUpTo(22), 70, MIN_Y, UP, DOWN),
            "a floor exactly as far down as the scan goes");
    }

    @Test
    void aFloorAboveTheHintIsFoundWithinTheWayUp() {
        assertEquals(OptionalInt.of(76), GroundFinder.scan(rockUpTo(75), 70, MIN_Y, UP, DOWN),
            "the hint lies five blocks inside rising ground");
        assertEquals(OptionalInt.of(79), GroundFinder.scan(rockUpTo(78), 70, MIN_Y, UP, DOWN),
            "the highest floor the scan can see: its top block is the first one examined");
    }

    @Test
    void nothingIsFoundInSolidRock() {
        assertEquals(OptionalInt.empty(), GroundFinder.scan(y -> true, 70, MIN_Y, UP, DOWN));
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(79), 70, MIN_Y, UP, DOWN),
            "rock that rises above the way up has no free block within the scan");
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(200), 70, MIN_Y, UP, DOWN));
    }

    @Test
    void nothingIsFoundInOpenAir() {
        assertEquals(OptionalInt.empty(), GroundFinder.scan(y -> false, 70, MIN_Y, UP, DOWN));
    }

    @Test
    void nothingIsFoundBeyondTheWayDown() {
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(21), 70, MIN_Y, UP, DOWN),
            "a floor 49 blocks under the hint is out of reach");
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(0), 70, MIN_Y, UP, DOWN));
    }

    @Test
    void nothingIsFoundBelowTheBottomOfTheWorld() {
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(-65), -40, MIN_Y, UP, DOWN),
            "rock below the lowest block of the world is never looked at");
        assertEquals(OptionalInt.of(-63), GroundFinder.scan(rockUpTo(-64), -40, MIN_Y, UP, DOWN),
            "the lowest block of the world can be the floor");
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(-70), -100, MIN_Y, UP, DOWN),
            "a hint far below the world finds nothing");
    }

    @Test
    void theScanNeverReadsOutsideItsLimits() {
        AtomicInteger lowest = new AtomicInteger(Integer.MAX_VALUE);
        AtomicInteger highest = new AtomicInteger(Integer.MIN_VALUE);
        AtomicInteger calls = new AtomicInteger();
        IntPredicate watching = y -> {
            lowest.set(Math.min(lowest.get(), y));
            highest.set(Math.max(highest.get(), y));
            calls.incrementAndGet();
            return false;
        };

        GroundFinder.scan(watching, 70, MIN_Y, UP, DOWN);
        assertEquals(22, lowest.get(), "48 blocks under the hint");
        assertEquals(79, highest.get(), "one above the 8 blocks over the hint, to know whether the top is free");
        assertEquals(UP + DOWN + 2, calls.get(), "every height is read once");

        lowest.set(Integer.MAX_VALUE);
        GroundFinder.scan(watching, -40, MIN_Y, UP, DOWN);
        assertEquals(MIN_Y, lowest.get(), "the bottom of the world ends the scan early");
    }

    @Test
    void aCaveFloorIsFoundFromInsideTheCaveAndFromTheRockAboveIt() {
        // Rock up to 60, a cave from 61 to 79, rock from 80 upward: the Nether in small.
        IntPredicate cave = y -> y <= 60 || y >= 80;

        assertEquals(OptionalInt.of(61), GroundFinder.scan(cave, 70, MIN_Y, UP, DOWN), "from inside the cave");
        assertEquals(OptionalInt.of(61), GroundFinder.scan(cave, 61, MIN_Y, UP, DOWN), "from its floor");
        assertEquals(OptionalInt.of(61), GroundFinder.scan(cave, 78, MIN_Y, UP, DOWN), "from under its roof");
        assertEquals(OptionalInt.of(61), GroundFinder.scan(cave, 90, MIN_Y, UP, DOWN),
            "a hint inside the roof looks down through the rock into the cave");
        assertEquals(OptionalInt.of(61), GroundFinder.scan(cave, 50, 0, 20, DOWN),
            "a hint inside the floor looks up to its surface");
        assertEquals(OptionalInt.empty(), GroundFinder.scan(cave, 50, 0, UP, DOWN),
            "but only as far as the way up");
    }

    @Test
    void theHighestFloorWithinTheScanWins() {
        // A floor up to 50 and a ledge from 64 to 66 with air between and above.
        IntPredicate ledge = y -> y <= 50 || (y >= 64 && y <= 66);

        assertEquals(OptionalInt.of(67), GroundFinder.scan(ledge, 70, MIN_Y, UP, DOWN), "from above the ledge");
        assertEquals(OptionalInt.of(67), GroundFinder.scan(ledge, 60, MIN_Y, UP, DOWN),
            "from under the ledge, when its top is within the way up");
        assertEquals(OptionalInt.of(51), GroundFinder.scan(ledge, 52, MIN_Y, UP, DOWN),
            "far enough under the ledge the floor below it is the ground");
    }

    @Test
    void aFluidCountedAsGroundStopsTheScanOnItsSurface() {
        // A lake bed up to 19 with lava from 20 to 31.
        IntPredicate rock = y -> y <= 19;
        IntPredicate fluid = y -> y >= 20 && y <= 31;
        IntPredicate rockOrFluid = rock.or(fluid);

        assertEquals(OptionalInt.of(32), GroundFinder.scan(rockOrFluid, 40, MIN_Y, UP, DOWN),
            "a fire whirl stands on the lava, not on the lake bed");
        assertEquals(OptionalInt.of(32), GroundFinder.scan(rockOrFluid, 25, MIN_Y, UP, DOWN),
            "a hint inside the fluid rises to its surface");
        assertEquals(OptionalInt.of(20), GroundFinder.scan(rock, 40, MIN_Y, UP, DOWN),
            "without the fluid the scan would sink to the bed");
    }

    @Test
    void negativeDistancesCountAsZero() {
        assertEquals(OptionalInt.of(71), GroundFinder.scan(rockUpTo(70), 70, MIN_Y, -5, -5),
            "only the hint itself is examined");
        assertEquals(OptionalInt.empty(), GroundFinder.scan(rockUpTo(69), 70, MIN_Y, -5, -5));
        assertEquals(OptionalInt.of(70), GroundFinder.scan(rockUpTo(69), 70, MIN_Y, 0, 1));
    }

    @Test
    void heightsAtTheEndsOfTheIntRangeDoNotOverflow() {
        AtomicInteger calls = new AtomicInteger();
        IntPredicate counting = y -> {
            calls.incrementAndGet();
            return false;
        };

        assertEquals(OptionalInt.empty(), GroundFinder.scan(counting, Integer.MAX_VALUE, MIN_Y, UP, DOWN));
        assertEquals(OptionalInt.empty(), GroundFinder.scan(counting, Integer.MAX_VALUE - 4, MIN_Y, UP, DOWN));
        assertEquals(0, calls.get(), "a scan that would start above the int range reads nothing");

        assertEquals(OptionalInt.empty(),
            GroundFinder.scan(counting, Integer.MIN_VALUE + 10, Integer.MIN_VALUE, UP, DOWN));
        assertTrue(calls.get() <= UP + DOWN + 2, "the scan ends at the lowest int instead of wrapping around");

        assertEquals(OptionalInt.empty(), GroundFinder.scan(counting, 10, 500, UP, DOWN),
            "a world whose bottom lies above the scan has no ground");
    }
}
