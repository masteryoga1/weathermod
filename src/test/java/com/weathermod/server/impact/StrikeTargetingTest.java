package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.Position;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Pins where a storm's lightning comes down: always inside the storm, most of the time in sight of a player but
 * never on top of one, and otherwise anywhere in the inner disc. Uses seeded picks and needs no Minecraft
 * bootstrap.
 */
class StrikeTargetingTest {
    private static final int PICKS = 10_000;
    /** Slack for distances that should equal a limit, in blocks. */
    private static final double SLACK = 1.0E-6D;
    /** A share over 10 000 picks may be this far from its expectation: six standard deviations. */
    private static final double SHARE_TOLERANCE = 0.03D;

    /** A player's position; the height plays no part. */
    private record Point(double x, double y, double z) implements Position {
    }

    private static double distance(double[] spot, double x, double z) {
        double dx = spot[0] - x;
        double dz = spot[1] - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(0.6F, StrikeTargeting.NEAR_PLAYER_CHANCE);
        assertEquals(16.0, StrikeTargeting.NEAR_MIN_BLOCKS);
        assertEquals(64.0, StrikeTargeting.NEAR_MAX_BLOCKS);
        assertEquals(0.8F, StrikeTargeting.DISC_FRACTION);
    }

    @Test
    void everyPickLiesInsideTheRadius() {
        RandomSource random = RandomSource.create(99L);
        double[] out = new double[2];
        float radius = 96.0F;
        // One player 90 blocks from the centre, so many near strikes must be pulled back, and one 40 blocks out.
        List<Point> players = List.of(new Point(100.0, 64.0, -20.0), new Point(10.0, 70.0, 20.0));
        int onTheRim = 0;
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(10.0, -20.0, radius, players, random, out);
            double fromCentre = distance(out, 10.0, -20.0);
            assertTrue(fromCentre <= radius + SLACK, "a strike inside the storm, got " + fromCentre + " blocks out");
            if (fromCentre > radius - SLACK) {
                onTheRim++;
            }
        }
        // Strikes that fell outside were pulled back onto the radius, not dropped.
        assertTrue(onTheRim > 100, "strikes pulled back onto the radius: " + onTheRim);
    }

    @Test
    void aSmallStormKeepsEveryPickInside() {
        RandomSource random = RandomSource.create(17L);
        double[] out = new double[2];
        float radius = 12.0F;
        List<Point> players = List.of(new Point(3.0, 64.0, 4.0));
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(0.0, 0.0, radius, players, random, out);
            assertTrue(distance(out, 0.0, 0.0) <= radius + SLACK);
        }
    }

    @Test
    void aboutSixtyPercentOfStrikesLandWithinSixtyFourBlocksOfAPlayer() {
        RandomSource random = RandomSource.create(2024L);
        double[] out = new double[2];
        float radius = 1000.0F;
        List<Point> players = List.of(new Point(300.0, 64.0, -200.0));
        int near = 0;
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(0.0, 0.0, radius, players, random, out);
            assertTrue(distance(out, 0.0, 0.0) <= radius + SLACK);
            if (distance(out, 300.0, -200.0) <= StrikeTargeting.NEAR_MAX_BLOCKS + SLACK) {
                near++;
            }
        }
        // Six in ten by the near-player chance; the few disc strikes that happen to land near add 0.3 points.
        assertEquals(0.6, near / (double) PICKS, SHARE_TOLERANCE);
    }

    @Test
    void nearStrikesKeepTheirDistanceAndTheOthersFallInTheDisc() {
        RandomSource random = RandomSource.create(31L);
        double[] out = new double[2];
        float radius = 1000.0F;
        // The player stands outside the inner disc, so a strike within 64 blocks is always a near strike.
        List<Point> players = List.of(new Point(950.0, 64.0, 0.0));
        double disc = StrikeTargeting.DISC_FRACTION * (double) radius;
        int near = 0;
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(0.0, 0.0, radius, players, random, out);
            double fromCentre = distance(out, 0.0, 0.0);
            double fromPlayer = distance(out, 950.0, 0.0);
            assertTrue(fromCentre <= radius + SLACK);
            if (fromPlayer <= StrikeTargeting.NEAR_MAX_BLOCKS + SLACK) {
                near++;
                assertTrue(fromPlayer >= StrikeTargeting.NEAR_MIN_BLOCKS - SLACK,
                    "a near strike at least 16 blocks from the player, got " + fromPlayer);
            } else {
                assertTrue(fromCentre <= disc + SLACK, "a strike in the inner disc, got " + fromCentre);
            }
        }
        assertEquals(StrikeTargeting.NEAR_PLAYER_CHANCE, near / (double) PICKS, SHARE_TOLERANCE);
    }

    @Test
    void severalPlayersShareTheNearStrikes() {
        RandomSource random = RandomSource.create(8L);
        double[] out = new double[2];
        float radius = 1000.0F;
        List<Point> players = List.of(new Point(900.0, 64.0, 0.0), new Point(-900.0, 64.0, 0.0));
        int first = 0;
        int second = 0;
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(0.0, 0.0, radius, players, random, out);
            if (distance(out, 900.0, 0.0) <= StrikeTargeting.NEAR_MAX_BLOCKS + SLACK) {
                first++;
            } else if (distance(out, -900.0, 0.0) <= StrikeTargeting.NEAR_MAX_BLOCKS + SLACK) {
                second++;
            }
        }
        assertEquals(0.3, first / (double) PICKS, SHARE_TOLERANCE);
        assertEquals(0.3, second / (double) PICKS, SHARE_TOLERANCE);
    }

    @Test
    void withoutPlayersEveryStrikeFallsInTheDisc() {
        RandomSource random = RandomSource.create(5L);
        double[] out = new double[2];
        float radius = 200.0F;
        double disc = StrikeTargeting.DISC_FRACTION * (double) radius;
        List<Point> nobody = List.of();
        int inner = 0;
        int east = 0;
        int south = 0;
        double farthest = 0.0;
        for (int i = 0; i < PICKS; i++) {
            StrikeTargeting.pick(50.0, 60.0, radius, nobody, random, out);
            double fromCentre = distance(out, 50.0, 60.0);
            assertTrue(fromCentre <= disc + SLACK, "a strike within 0.8 radii, got " + fromCentre);
            farthest = Math.max(farthest, fromCentre);
            if (fromCentre <= disc * 0.5) {
                inner++;
            }
            if (out[0] > 50.0) {
                east++;
            }
            if (out[1] > 60.0) {
                south++;
            }
        }
        // Uniform over the area: a quarter of the strikes within half the disc's radius, half on either side.
        assertEquals(0.25, inner / (double) PICKS, SHARE_TOLERANCE);
        assertEquals(0.5, east / (double) PICKS, SHARE_TOLERANCE);
        assertEquals(0.5, south / (double) PICKS, SHARE_TOLERANCE);
        assertTrue(farthest > disc * 0.95, "the disc is used to its rim, farthest strike " + farthest);
    }

    @Test
    void aStormWithoutARadiusStrikesItsCentre() {
        double[] out = new double[2];
        List<Point> players = List.of(new Point(1.0, 64.0, 1.0));
        StrikeTargeting.pick(5.0, -7.0, 0.0F, players, RandomSource.create(1L), out);
        assertEquals(5.0, out[0]);
        assertEquals(-7.0, out[1]);
    }
}
