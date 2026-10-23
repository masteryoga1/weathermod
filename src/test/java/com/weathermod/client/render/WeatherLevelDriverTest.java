package com.weathermod.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.client.LocalConditions;
import com.weathermod.core.PrecipitationKind;
import org.junit.jupiter.api.Test;

/**
 * Ticks drivers against hand-built local conditions and checks the snap to the conditions on the first active tick,
 * the ramps toward the conditions after it, the decay toward zero while inactive, the driving flag against the
 * active and config inputs, partial-tick interpolation, clamping and reset. Needs no Minecraft bootstrap:
 * {@code applyTo} is the only method that needs a level and it only copies four fields.
 */
class WeatherLevelDriverTest {
    private static final float EPSILON = 1.0E-5F;
    /** Enough ticks for every level to reach any target in {@code [0, 1]} at the slowest step. */
    private static final int SETTLE_TICKS = 120;

    private static final LocalConditions THUNDERSTORM =
        new LocalConditions(1.0F, 0.5F, 0.8F, PrecipitationKind.RAIN, 1.0F, 7);
    private static final LocalConditions DRIZZLE =
        new LocalConditions(0.35F, 0.0F, 0.2F, PrecipitationKind.RAIN, 1.0F, 3);

    private static void tick(WeatherLevelDriver driver, LocalConditions conditions, boolean active, boolean enabled,
                             int ticks) {
        for (int i = 0; i < ticks; i++) {
            driver.tick(conditions, active, enabled);
        }
    }

    /** A driver past its first active tick, with every level at zero, so the ramps below start from zero. */
    private static WeatherLevelDriver settledDriver() {
        WeatherLevelDriver driver = new WeatherLevelDriver();
        driver.tick(LocalConditions.NONE, true, true);
        return driver;
    }

    @Test
    void stepsMatchTheAgreedRamps() {
        assertEquals(0.01F, WeatherLevelDriver.RAIN_STEP, 0.0F);
        assertEquals(0.01F, WeatherLevelDriver.THUNDER_STEP, 0.0F);
        assertEquals(0.02F, WeatherLevelDriver.HAZE_STEP, 0.0F);
        assertTrue(WeatherLevelDriver.DEFAULT_RAIN_LEVEL_DRIVER);
    }

    @Test
    void startsAtZeroAndNotDriving() {
        WeatherLevelDriver driver = new WeatherLevelDriver();

        assertEquals(0.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(0.0F, driver.haze(), 0.0F);
        assertEquals(0.0F, driver.rain(0.5F), 0.0F);
        assertFalse(driver.isDriving());
    }

    @Test
    void oneTickMovesEachLevelByItsOwnStep() {
        WeatherLevelDriver driver = settledDriver();

        driver.tick(THUNDERSTORM, true, true);

        assertEquals(WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(WeatherLevelDriver.THUNDER_STEP, driver.thunder(), EPSILON);
        assertEquals(WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);
        assertEquals(0.0F, driver.rain(0.0F), 0.0F);
        assertEquals(0.0F, driver.thunder(0.0F), 0.0F);
        assertEquals(0.0F, driver.haze(0.0F), 0.0F);
    }

    @Test
    void levelsRampToTheConditionsAndStopThere() {
        WeatherLevelDriver driver = settledDriver();

        tick(driver, THUNDERSTORM, true, true, SETTLE_TICKS);

        assertEquals(1.0F, driver.rain(), EPSILON);
        assertEquals(0.5F, driver.thunder(), EPSILON);
        assertEquals(0.8F, driver.haze(), EPSILON);

        driver.tick(THUNDERSTORM, true, true);
        assertEquals(1.0F, driver.rain(), EPSILON);
        assertEquals(0.5F, driver.thunder(), EPSILON);
        assertEquals(0.8F, driver.haze(), EPSILON);
        assertEquals(1.0F, driver.rain(0.5F), EPSILON);
    }

    @Test
    void eachLevelRampsAtItsOwnPace() {
        WeatherLevelDriver driver = settledDriver();

        tick(driver, THUNDERSTORM, true, true, 30);

        assertEquals(0.3F, driver.rain(), EPSILON);
        assertEquals(0.3F, driver.thunder(), EPSILON);
        assertEquals(0.6F, driver.haze(), EPSILON);

        tick(driver, THUNDERSTORM, true, true, 30);

        assertEquals(0.6F, driver.rain(), EPSILON);
        assertEquals(0.5F, driver.thunder(), EPSILON);
        assertEquals(0.8F, driver.haze(), EPSILON);
    }

    @Test
    void levelsNeverOvershootTheConditions() {
        WeatherLevelDriver driver = settledDriver();

        for (int i = 0; i < SETTLE_TICKS; i++) {
            driver.tick(DRIZZLE, true, true);
            assertTrue(driver.rain() <= DRIZZLE.rain() + EPSILON, "rain overshoot at tick " + i);
            assertTrue(driver.haze() <= DRIZZLE.haze() + EPSILON, "haze overshoot at tick " + i);
            assertEquals(0.0F, driver.thunder(), 0.0F);
        }

        assertEquals(DRIZZLE.rain(), driver.rain(), EPSILON);
        assertEquals(DRIZZLE.haze(), driver.haze(), EPSILON);
    }

    @Test
    void changingConditionsRampDownAtTheSameSteps() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, SETTLE_TICKS);

        driver.tick(DRIZZLE, true, true);

        assertEquals(1.0F - WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(0.5F - WeatherLevelDriver.THUNDER_STEP, driver.thunder(), EPSILON);
        assertEquals(0.8F - WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);

        tick(driver, DRIZZLE, true, true, SETTLE_TICKS);

        assertEquals(DRIZZLE.rain(), driver.rain(), EPSILON);
        assertEquals(0.0F, driver.thunder(), EPSILON);
        assertEquals(DRIZZLE.haze(), driver.haze(), EPSILON);
    }

    @Test
    void inactiveTicksMoveTowardZeroWhateverTheConditionsSay() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, SETTLE_TICKS);

        driver.tick(THUNDERSTORM, false, true);

        assertEquals(1.0F - WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(0.5F - WeatherLevelDriver.THUNDER_STEP, driver.thunder(), EPSILON);
        assertEquals(0.8F - WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);
        assertEquals(1.0F, driver.rain(0.0F), EPSILON);

        tick(driver, THUNDERSTORM, false, true, SETTLE_TICKS);

        assertEquals(0.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(0.0F, driver.haze(), 0.0F);
    }

    @Test
    void noneConditionsWhileActiveDecayToZero() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, 50);

        driver.tick(LocalConditions.NONE, true, true);

        assertEquals(0.5F - WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(0.5F - WeatherLevelDriver.THUNDER_STEP, driver.thunder(), EPSILON);
        assertEquals(0.8F - WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);
        assertTrue(driver.isDriving());
    }

    @Test
    void isDrivingRequiresActiveAndEnabled() {
        WeatherLevelDriver driver = settledDriver();

        driver.tick(THUNDERSTORM, true, true);
        assertTrue(driver.isDriving());

        driver.tick(THUNDERSTORM, false, true);
        assertFalse(driver.isDriving(), "inactive must not drive");

        driver.tick(THUNDERSTORM, true, false);
        assertFalse(driver.isDriving(), "config off must not drive");

        driver.tick(THUNDERSTORM, false, false);
        assertFalse(driver.isDriving());

        driver.tick(LocalConditions.NONE, true, true);
        assertTrue(driver.isDriving(), "driving does not depend on the conditions");
    }

    @Test
    void isDrivingFollowsTheLatestTickOnly() {
        WeatherLevelDriver driver = settledDriver();

        tick(driver, THUNDERSTORM, true, true, 10);
        assertTrue(driver.isDriving());

        driver.tick(THUNDERSTORM, true, false);
        assertFalse(driver.isDriving());
        assertEquals(0.11F, driver.rain(), EPSILON, "levels keep ramping while the driver is off");

        driver.tick(THUNDERSTORM, true, true);
        assertTrue(driver.isDriving());
    }

    @Test
    void configOffStillRampsTheLevelsForTheOtherConsumers() {
        WeatherLevelDriver driver = settledDriver();

        tick(driver, THUNDERSTORM, true, false, SETTLE_TICKS);

        assertFalse(driver.isDriving());
        assertEquals(1.0F, driver.rain(), EPSILON);
        assertEquals(0.8F, driver.haze(), EPSILON);
    }

    @Test
    void partialTickInterpolatesBetweenPreviousAndCurrentTick() {
        WeatherLevelDriver driver = settledDriver();

        driver.tick(THUNDERSTORM, true, true);

        assertEquals(0.0F, driver.rain(0.0F), EPSILON);
        assertEquals(0.01F, driver.rain(1.0F), EPSILON);
        assertEquals(0.005F, driver.rain(0.5F), EPSILON);
        assertEquals(0.005F, driver.thunder(0.5F), EPSILON);
        assertEquals(0.01F, driver.haze(0.5F), EPSILON);

        driver.tick(THUNDERSTORM, true, true);

        assertEquals(0.01F, driver.rain(0.0F), EPSILON);
        assertEquals(0.02F, driver.rain(1.0F), EPSILON);
        assertEquals(0.0125F, driver.rain(0.25F), EPSILON);
        assertEquals(0.0125F, driver.thunder(0.25F), EPSILON);
        assertEquals(0.025F, driver.haze(0.25F), EPSILON);
    }

    @Test
    void partialTickInterpolatesDownwardMovementToo() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, SETTLE_TICKS);

        driver.tick(THUNDERSTORM, false, true);

        assertEquals(1.0F, driver.rain(0.0F), EPSILON);
        assertEquals(0.99F, driver.rain(1.0F), EPSILON);
        assertEquals(0.995F, driver.rain(0.5F), EPSILON);
        assertEquals(0.79F, driver.haze(0.5F), EPSILON);
    }

    @Test
    void conditionsOutsideTheUnitRangeAreClampedBeforeApproaching() {
        WeatherLevelDriver driver = settledDriver();
        LocalConditions wild = new LocalConditions(3.0F, -1.0F, 1.5F, PrecipitationKind.RAIN, 1.0F, 1);

        tick(driver, wild, true, true, 2 * SETTLE_TICKS);

        assertEquals(1.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(1.0F, driver.haze(), 0.0F);
    }

    @Test
    void nanConditionsCountAsZero() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, 10);
        LocalConditions broken = new LocalConditions(Float.NaN, Float.NaN, Float.NaN, PrecipitationKind.RAIN, 1.0F, 1);

        driver.tick(broken, true, true);

        assertEquals(0.09F, driver.rain(), EPSILON);
        assertEquals(0.09F, driver.thunder(), EPSILON);
        assertEquals(0.18F, driver.haze(), EPSILON);
    }

    @Test
    void resetClearsLevelsAndStopsDriving() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, 40);

        driver.reset();

        assertEquals(0.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(0.0F, driver.haze(), 0.0F);
        assertEquals(0.0F, driver.rain(0.5F), 0.0F);
        assertEquals(0.0F, driver.thunder(0.5F), 0.0F);
        assertEquals(0.0F, driver.haze(0.5F), 0.0F);
        assertFalse(driver.isDriving());
    }

    @Test
    void resetClearsEverythingAndTheNextActiveTickSnapsToTheConditions() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, 40);
        driver.reset();

        assertEquals(0.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.rain(0.5F), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(0.0F, driver.haze(), 0.0F);
        assertFalse(driver.isDriving());

        driver.tick(DRIZZLE, true, true);

        assertEquals(DRIZZLE.rain(), driver.rain(), 0.0F);
        assertEquals(DRIZZLE.rain(), driver.rain(0.0F), 0.0F);
        assertTrue(driver.isDriving());
    }

    @Test
    void nullConditionsAreRejected() {
        WeatherLevelDriver driver = settledDriver();

        assertThrows(NullPointerException.class, () -> driver.tick(null, true, true));
    }

    @Test
    void firstActiveTickStartsAtTheConditionsInsteadOfRampingFromZero() {
        WeatherLevelDriver driver = new WeatherLevelDriver();

        driver.tick(DRIZZLE, true, true);

        assertEquals(DRIZZLE.rain(), driver.rain(), 0.0F);
        assertEquals(DRIZZLE.rain(), driver.rain(0.0F), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(DRIZZLE.haze(), driver.haze(), 0.0F);
        assertEquals(DRIZZLE.haze(), driver.haze(0.0F), 0.0F);
        assertTrue(driver.isDriving());

        driver.tick(THUNDERSTORM, true, true);

        assertEquals(DRIZZLE.rain() + WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(DRIZZLE.rain(), driver.rain(0.0F), 0.0F);
        assertEquals(WeatherLevelDriver.THUNDER_STEP, driver.thunder(), EPSILON);
        assertEquals(DRIZZLE.haze() + WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);
    }

    @Test
    void firstActiveTickClampsTheConditions() {
        WeatherLevelDriver driver = new WeatherLevelDriver();
        LocalConditions wild = new LocalConditions(1.5F, -0.5F, Float.NaN, PrecipitationKind.RAIN, 1.0F, 1);

        driver.tick(wild, true, true);

        assertEquals(1.0F, driver.rain(), 0.0F);
        assertEquals(0.0F, driver.thunder(), 0.0F);
        assertEquals(0.0F, driver.haze(), 0.0F);
    }

    @Test
    void inactiveTicksKeepTheDriverFreshAndResetMakesItFreshAgain() {
        WeatherLevelDriver driver = new WeatherLevelDriver();
        tick(driver, THUNDERSTORM, false, true, 5);
        assertEquals(0.0F, driver.rain(), 0.0F);
        assertFalse(driver.isDriving());

        driver.tick(DRIZZLE, true, true);
        assertEquals(DRIZZLE.rain(), driver.rain(), 0.0F);

        tick(driver, THUNDERSTORM, true, true, SETTLE_TICKS);
        assertEquals(1.0F, driver.rain(), EPSILON);

        driver.reset();
        assertEquals(0.0F, driver.rain(), 0.0F);
        assertFalse(driver.isDriving());

        driver.tick(DRIZZLE, true, true);
        assertEquals(DRIZZLE.rain(), driver.rain(), 0.0F);
        assertEquals(DRIZZLE.haze(), driver.haze(), 0.0F);
    }

    @Test
    void worldShownMakesTheFirstActiveTickRampInstead() {
        WeatherLevelDriver driver = new WeatherLevelDriver();
        driver.worldShown();

        driver.tick(DRIZZLE, true, true);

        assertEquals(WeatherLevelDriver.RAIN_STEP, driver.rain(), EPSILON);
        assertEquals(0.0F, driver.rain(0.0F), 0.0F);
        assertEquals(WeatherLevelDriver.HAZE_STEP, driver.haze(), EPSILON);

        driver.reset();
        driver.tick(DRIZZLE, true, true);

        assertEquals(DRIZZLE.rain(), driver.rain(), 0.0F);
    }

    @Test
    void configToggleDoesNotRestartTheRamps() {
        WeatherLevelDriver driver = settledDriver();
        tick(driver, THUNDERSTORM, true, true, 30);
        assertEquals(0.3F, driver.rain(), EPSILON);

        driver.tick(THUNDERSTORM, true, false);
        assertFalse(driver.isDriving());
        driver.tick(THUNDERSTORM, true, true);

        assertTrue(driver.isDriving());
        assertEquals(0.32F, driver.rain(), EPSILON);
    }
}
