package com.weathermod.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import org.junit.jupiter.api.Test;

/**
 * Checks the renderer's arithmetic against the agreed numbers and the vanilla formulas: tilt caps, the distance
 * fades, the column window, the texture scroll ranges, the column seeds and the upwind lean of a streak's top. Needs
 * no Minecraft bootstrap: only plain arithmetic and {@link Headings} are used.
 */
class PrecipitationGeometryTest {
    private static final float EPSILON = 1.0E-5F;
    private static final double DOUBLE_EPSILON = 1.0E-9D;

    @Test
    void constantsMatchTheAgreedNumbers() {
        assertEquals(5, PrecipitationGeometry.FAST_RADIUS);
        assertEquals(10, PrecipitationGeometry.FANCY_RADIUS);
        assertEquals(0.035F, PrecipitationGeometry.RAIN_TILT_PER_SPEED, 0.0F);
        assertEquals(0.6F, PrecipitationGeometry.RAIN_MAX_TILT, 0.0F);
        assertEquals(1.6F, PrecipitationGeometry.SNOW_TILT_FACTOR, 0.0F);
        assertEquals(0.9F, PrecipitationGeometry.SNOW_MAX_TILT, 0.0F);
        assertEquals(0.75F, PrecipitationGeometry.SLEET_RAIN_ALPHA, 0.0F);
        assertEquals(0.6F, PrecipitationGeometry.SLEET_SNOW_ALPHA, 0.0F);
        assertEquals(0.25F, PrecipitationGeometry.RAIN_UV_PER_BLOCK, 0.0F);
    }

    @Test
    void rainTiltGrowsWithTheWindAndIsCapped() {
        assertEquals(0.0F, PrecipitationGeometry.rainTilt(0.0F), 0.0F, "no wind, no lean");
        assertEquals(0.0F, PrecipitationGeometry.rainTilt(-4.0F), 0.0F, "a negative speed never leans");
        assertEquals(0.035F, PrecipitationGeometry.rainTilt(1.0F), EPSILON);
        assertEquals(0.35F, PrecipitationGeometry.rainTilt(10.0F), EPSILON);
        assertEquals(0.6F, PrecipitationGeometry.rainTilt(17.142857F), EPSILON,
            "the cap is reached at about 17 blocks per second");
        assertEquals(PrecipitationGeometry.RAIN_MAX_TILT, PrecipitationGeometry.rainTilt(100.0F), 0.0F);
        assertEquals(PrecipitationGeometry.RAIN_MAX_TILT, PrecipitationGeometry.rainTilt(Float.MAX_VALUE), 0.0F);
    }

    @Test
    void snowTiltIsALargerCappedMultipleOfTheRainTilt() {
        assertEquals(0.0F, PrecipitationGeometry.snowTilt(0.0F), 0.0F);
        assertEquals(0.0F, PrecipitationGeometry.snowTilt(-1.0F), 0.0F);
        assertEquals(0.056F, PrecipitationGeometry.snowTilt(1.0F), EPSILON);
        assertEquals(0.56F, PrecipitationGeometry.snowTilt(10.0F), EPSILON);
        assertEquals(PrecipitationGeometry.SNOW_MAX_TILT, PrecipitationGeometry.snowTilt(20.0F), EPSILON,
            "at 20 blocks per second the capped rain tilt of 0.6 times the factor would be 0.96, so the snow cap applies");
        assertEquals(PrecipitationGeometry.SNOW_MAX_TILT, PrecipitationGeometry.snowTilt(100.0F), 0.0F);
        for (float speed = 0.0F; speed <= 30.0F; speed += 0.5F) {
            assertTrue(PrecipitationGeometry.snowTilt(speed) >= PrecipitationGeometry.rainTilt(speed),
                "snow leans at least as much as rain at " + speed);
        }
    }

    @Test
    void rainAlphaIsFullAtTheCameraAndHalfAtTheEdge() {
        assertEquals(1.0F, PrecipitationGeometry.rainAlpha(0.0F, 1.0F), EPSILON);
        assertEquals(0.5F, PrecipitationGeometry.rainAlpha(1.0F, 1.0F), EPSILON);
        assertEquals(0.35F, PrecipitationGeometry.rainAlpha(0.0F, 0.35F), EPSILON, "scales with the rain level");
        assertEquals(0.175F, PrecipitationGeometry.rainAlpha(1.0F, 0.35F), EPSILON);
        assertEquals(0.0F, PrecipitationGeometry.rainAlpha(0.5F, 0.0F), 0.0F, "no rain, no alpha");
        assertEquals(0.875F, PrecipitationGeometry.rainAlpha(0.5F, 1.0F), EPSILON);
    }

    @Test
    void snowAlphaFadesLessTowardTheEdge() {
        assertEquals(0.8F, PrecipitationGeometry.snowAlpha(0.0F, 1.0F), EPSILON);
        assertEquals(0.5F, PrecipitationGeometry.snowAlpha(1.0F, 1.0F), EPSILON);
        assertEquals(0.4F, PrecipitationGeometry.snowAlpha(0.0F, 0.5F), EPSILON);
        assertEquals(0.25F, PrecipitationGeometry.snowAlpha(1.0F, 0.5F), EPSILON);
        assertEquals(0.0F, PrecipitationGeometry.snowAlpha(0.0F, 0.0F), 0.0F);
    }

    @Test
    void sleetPassesStayAboveTheAlphaTestAtFullRain() {
        float rainEdge = PrecipitationGeometry.rainAlpha(1.0F, 1.0F) * PrecipitationGeometry.SLEET_RAIN_ALPHA;
        float snowEdge = PrecipitationGeometry.snowAlpha(1.0F, 1.0F) * PrecipitationGeometry.SLEET_SNOW_ALPHA;
        assertTrue(rainEdge > 0.1F, "the sleet rain pass keeps the edge columns above the 0.1 discard");
        assertTrue(snowEdge > 0.1F, "the sleet snow pass keeps the edge columns above the 0.1 discard");
    }

    @Test
    void columnBoundsAboveTheTerrainSpanTheWholeWindow() {
        int[] bounds = PrecipitationGeometry.columnBounds(100, 60, 10);

        assertNotNull(bounds);
        assertArrayEquals(new int[] {90, 110, 100}, bounds, "bottom and top are the window, light at the camera");
    }

    @Test
    void columnBoundsInsideTheTerrainBandStartAtTheGround() {
        assertArrayEquals(new int[] {95, 110, 100}, PrecipitationGeometry.columnBounds(100, 95, 10),
            "ground below the camera: the streak starts at the ground and the light is read at the camera");
        assertArrayEquals(new int[] {105, 110, 105}, PrecipitationGeometry.columnBounds(100, 105, 10),
            "ground above the camera: the streak starts at the ground and the light is read at the ground");
        assertArrayEquals(new int[] {100, 110, 100}, PrecipitationGeometry.columnBounds(100, 100, 10),
            "ground at the camera height");
        assertArrayEquals(new int[] {109, 110, 109}, PrecipitationGeometry.columnBounds(100, 109, 10),
            "one block of streak left just under the top of the window");
    }

    @Test
    void columnBoundsBelowTheTerrainAreNull() {
        assertNull(PrecipitationGeometry.columnBounds(100, 110, 10),
            "ground exactly at the top of the window: nothing in view");
        assertNull(PrecipitationGeometry.columnBounds(100, 111, 10), "ground above the window: nothing in view");
        assertNull(PrecipitationGeometry.columnBounds(50, 80, 10), "a camera under a roof draws nothing");
        assertNull(PrecipitationGeometry.columnBounds(-40, 64, 5), "deep underground with the fast radius");
    }

    @Test
    void columnBoundsUseTheRadius() {
        assertArrayEquals(new int[] {95, 105, 100}, PrecipitationGeometry.columnBounds(100, 60, 5));
        assertArrayEquals(new int[] {95, 105, 100},
            PrecipitationGeometry.columnBounds(100, 64, PrecipitationGeometry.FAST_RADIUS));
        assertArrayEquals(new int[] {90, 110, 100},
            PrecipitationGeometry.columnBounds(100, 64, PrecipitationGeometry.FANCY_RADIUS));
        assertArrayEquals(new int[] {97, 105, 100},
            PrecipitationGeometry.columnBounds(100, 97, PrecipitationGeometry.FAST_RADIUS),
            "the ground wins over the bottom of the window whichever radius is in use");
        assertArrayEquals(new int[] {97, 110, 100},
            PrecipitationGeometry.columnBounds(100, 97, PrecipitationGeometry.FANCY_RADIUS));
    }

    @Test
    void scalarColumnHelpersAgreeWithTheBounds() {
        for (int ground = 40; ground <= 130; ground++) {
            int[] bounds = PrecipitationGeometry.columnBounds(100, ground, 10);
            int bottom = PrecipitationGeometry.columnBottom(100, ground, 10);
            int top = PrecipitationGeometry.columnTop(100, ground, 10);
            if (bounds == null) {
                assertEquals(bottom, top, "null bounds mean the bottom meets the top at ground " + ground);
            } else {
                assertEquals(bottom, bounds[0]);
                assertEquals(top, bounds[1]);
                assertEquals(PrecipitationGeometry.columnLightY(100, ground), bounds[2]);
                assertTrue(bottom < top);
            }
        }
    }

    @Test
    void rainScrollStaysWithinOneTexturePeriodAndRunsDownward() {
        assertEquals(0.0F, PrecipitationGeometry.rainScroll(0, 0, 0.0F, 3.0F), 0.0F, "no time, no scroll");
        assertEquals(-0.09375F, PrecipitationGeometry.rainScroll(1, 0, 0.0F, 3.0F), EPSILON,
            "one tick at speed 3 moves 3/32");
        assertEquals(-0.09375F, PrecipitationGeometry.rainScroll(0, 1, 0.0F, 3.0F), EPSILON,
            "the column hash is a phase in ticks");
        assertEquals(-0.09375F, PrecipitationGeometry.rainScroll(0, 0, 1.0F, 3.0F), EPSILON,
            "and so is the partial tick");
        assertEquals(-3.0F, PrecipitationGeometry.rainScroll(32, 0, 0.0F, 3.0F), EPSILON);
        assertEquals(-0.0F, PrecipitationGeometry.rainScroll(32, 0, 0.0F, 32.0F), EPSILON, "a whole period wraps to zero");
        int[] ticks = {0, 1, 31, 32, 33, 1000, 131071, 131072, 131073, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        float[] speeds = {3.0F, 3.25F, 3.5F, 3.999F};
        float[] partials = {0.0F, 0.25F, 0.5F, 0.999F};
        for (int tick : ticks) {
            for (int hash = 0; hash <= 255; hash += 51) {
                for (float speed : speeds) {
                    for (float partial : partials) {
                        float scroll = PrecipitationGeometry.rainScroll(tick, hash, partial, speed);
                        assertTrue(scroll <= 0.0F, "rain scroll is never positive: " + scroll);
                        assertTrue(scroll > -PrecipitationGeometry.RAIN_SCROLL_PERIOD,
                            "rain scroll stays above minus one period: " + scroll);
                    }
                }
            }
        }
    }

    @Test
    void snowScrollStaysWithinOneRepeatAndRunsDownward() {
        assertEquals(0.0F, PrecipitationGeometry.snowScroll(0, 0.0F), 0.0F);
        assertEquals(-0.5F, PrecipitationGeometry.snowScroll(256, 0.0F), EPSILON);
        assertEquals(-511.5F / 512.0F, PrecipitationGeometry.snowScroll(511, 0.5F), EPSILON);
        assertEquals(0.0F, PrecipitationGeometry.snowScroll(512, 0.0F), 0.0F, "the tick mask wraps at 512");
        int[] ticks = {0, 1, 255, 511, 512, 513, 100000, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        float[] partials = {0.0F, 0.3F, 0.999F};
        for (int tick : ticks) {
            for (float partial : partials) {
                float scroll = PrecipitationGeometry.snowScroll(tick, partial);
                assertTrue(scroll <= 0.0F, "snow scroll is never positive: " + scroll);
                assertTrue(scroll > -1.0F, "snow scroll stays within one repeat: " + scroll);
            }
        }
    }

    @Test
    void columnSeedAndHashFollowTheVanillaFormulas() {
        assertEquals(0L, PrecipitationGeometry.columnSeed(0, 0));
        assertEquals(0, PrecipitationGeometry.columnHash(0, 0));
        int x = 3;
        int z = -7;
        int xTerm = x * x * 3121 + x * 45238971;
        int zTerm = z * z * 418711 + z * 13761;
        assertEquals((long) (xTerm ^ zTerm), PrecipitationGeometry.columnSeed(x, z),
            "the two halves are xor-ed in int arithmetic");
        assertEquals((xTerm + zTerm) & 0xFF, PrecipitationGeometry.columnHash(x, z),
            "the hash adds them and keeps the low byte");
        for (int cx = -40; cx <= 40; cx += 7) {
            for (int cz = -40; cz <= 40; cz += 5) {
                int hash = PrecipitationGeometry.columnHash(cx, cz);
                assertTrue(hash >= 0 && hash <= 255, "hash in [0, 255]: " + hash);
            }
        }
        assertEquals(PrecipitationGeometry.columnSeed(12, 34), PrecipitationGeometry.columnSeed(12, 34), "deterministic");
    }

    @Test
    void topOffsetLeansUpwindAgainstTheCompassDirection() {
        double[] east = PrecipitationGeometry.topOffset(90.0F, 0.5F, 10.0F);
        assertEquals(-5.0D, east[0], DOUBLE_EPSILON, "wind toward east (+x) leans the top toward west");
        assertEquals(0.0D, east[1], DOUBLE_EPSILON);

        double[] north = PrecipitationGeometry.topOffset(0.0F, 0.5F, 10.0F);
        assertEquals(0.0D, north[0], DOUBLE_EPSILON);
        assertEquals(5.0D, north[1], DOUBLE_EPSILON, "wind toward north (-z) leans the top toward south (+z)");

        double[] south = PrecipitationGeometry.topOffset(180.0F, 0.5F, 10.0F);
        assertEquals(0.0D, south[0], DOUBLE_EPSILON);
        assertEquals(-5.0D, south[1], DOUBLE_EPSILON, "wind toward south (+z) leans the top toward north (-z)");

        double[] west = PrecipitationGeometry.topOffset(270.0F, 0.5F, 10.0F);
        assertEquals(5.0D, west[0], DOUBLE_EPSILON, "wind toward west (-x) leans the top toward east");
        assertEquals(0.0D, west[1], DOUBLE_EPSILON);
    }

    @Test
    void topOffsetUsesHeadingsForEveryDirection() {
        float tilt = 0.35F;
        float height = 20.0F;
        for (float degrees = 0.0F; degrees < 360.0F; degrees += 15.0F) {
            double[] offset = PrecipitationGeometry.topOffset(degrees, tilt, height);
            double expectedX = -Headings.directionX(degrees) * tilt * height;
            double expectedZ = -Headings.directionZ(degrees) * tilt * height;
            assertEquals(expectedX, offset[0], 1.0E-6D, "x at " + degrees);
            assertEquals(expectedZ, offset[1], 1.0E-6D, "z at " + degrees);
            double length = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            assertEquals(tilt * height, length, 1.0E-6D, "the lean is tilt times height long at " + degrees);
            double dot = offset[0] * Headings.directionX(degrees) + offset[1] * Headings.directionZ(degrees);
            assertTrue(dot < 0.0D, "the lean points against the wind at " + degrees);
        }
    }

    @Test
    void topOffsetIsZeroWithoutTiltOrHeightAndLinearInHeight() {
        assertArrayEquals(new double[] {0.0D, 0.0D}, PrecipitationGeometry.topOffset(45.0F, 0.0F, 10.0F));
        assertArrayEquals(new double[] {0.0D, 0.0D}, PrecipitationGeometry.topOffset(45.0F, 0.6F, 0.0F));
        assertArrayEquals(new double[] {0.0D, 0.0D},
            PrecipitationGeometry.topOffset(123.0F, PrecipitationGeometry.rainTilt(0.0F), 15.0F), "no wind, no lean");

        double[] perBlock = PrecipitationGeometry.topOffset(37.0F, 0.6F, 1.0F);
        double[] tall = PrecipitationGeometry.topOffset(37.0F, 0.6F, 20.0F);
        assertEquals(perBlock[0] * 20.0D, tall[0], 1.0E-6D, "the renderer scales the per-block lean by the column height");
        assertEquals(perBlock[1] * 20.0D, tall[1], 1.0E-6D);
    }
}
