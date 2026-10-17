package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.Test;

/**
 * Checks the wind random walk, its regimes and its gusts stay inside their bounds over a long run and that the
 * state survives NBT, including tags written before regimes and gusts existed. Needs no Minecraft bootstrap.
 */
class WindStateTest {
    private static final float EPSILON = 1.0E-4F;

    @Test
    void randomWalkStaysWithinBoundsOverHundredThousandTicks() {
        RandomSource random = RandomSource.create(20240517L);
        WindState wind = WindState.random(random);

        int gustsSeen = 0;
        int gustRun = 0;
        for (int tick = 0; tick < 100_000; tick++) {
            float directionBefore = wind.directionDegrees();
            float speedBefore = wind.speed();
            boolean gustingBefore = wind.isGusting();

            wind.tick(random);

            float direction = wind.directionDegrees();
            assertTrue(direction >= 0.0F && direction < 360.0F, "direction out of range at tick " + tick + ": " + direction);

            float prevailing = wind.prevailingDirectionDegrees();
            assertTrue(prevailing >= 0.0F && prevailing < 360.0F,
                "prevailing direction out of range at tick " + tick + ": " + prevailing);

            float speed = wind.speed();
            assertTrue(speed >= WindState.MIN_SPEED && speed <= WindState.MAX_SPEED,
                "speed out of range at tick " + tick + ": " + speed);

            float turned = Headings.wrapDegrees(direction - directionBefore);
            float smallestTurn = Math.min(turned, 360.0F - turned);
            assertTrue(smallestTurn <= WindState.MAX_DIRECTION_DRIFT + WindState.REGIME_PULL_DEGREES + EPSILON,
                "direction jumped " + smallestTurn + " degrees at tick " + tick);

            float speedChange = Math.abs(speed - speedBefore);
            assertTrue(speedChange <= WindState.MAX_SPEED_STEP + 0.005F + EPSILON,
                "speed jumped " + speedChange + " at tick " + tick);

            WindRegime regime = wind.regime();
            float target = wind.targetSpeed();
            assertTrue(target >= regime.minSpeed() - EPSILON && target <= regime.maxSpeed() + EPSILON,
                "target speed " + target + " outside regime " + regime + " at tick " + tick);

            float gustFactor = wind.gustFactor();
            assertTrue(gustFactor >= 1.0F && gustFactor <= regime.maxGustFactor() + EPSILON,
                "gust factor out of range at tick " + tick + ": " + gustFactor + " in " + regime);

            float gustSpeed = wind.gustSpeed();
            assertTrue(gustSpeed >= speed - EPSILON && gustSpeed <= WindState.MAX_GUST_SPEED + EPSILON,
                "gust speed out of range at tick " + tick + ": " + gustSpeed);
            assertEquals(Math.min(speed * gustFactor, WindState.MAX_GUST_SPEED), gustSpeed, EPSILON);

            if (wind.isGusting()) {
                gustRun++;
            } else {
                assertEquals(1.0F, gustFactor, "no gust means factor 1 at tick " + tick);
                if (gustingBefore) {
                    gustsSeen++;
                    assertTrue(gustRun >= WindState.MIN_GUST_TICKS && gustRun <= WindState.MAX_GUST_TICKS,
                        "gust lasted " + gustRun + " ticks, ending at tick " + tick);
                    gustRun = 0;
                }
            }
        }
        assertTrue(gustsSeen > 0, "a hundred thousand ticks produce at least one gust");
    }

    @Test
    void speedDriftsTowardItsTarget() {
        RandomSource random = RandomSource.create(1L);
        WindState wind = new WindState(0.0F, WindState.MAX_SPEED);

        float start = wind.speed();
        for (int tick = 0; tick < WindState.TARGET_INTERVAL_TICKS - 1; tick++) {
            wind.tick(random);
        }

        assertEquals(start, wind.targetSpeed(), EPSILON, "the target set by the constructor holds for a full interval");
        assertTrue(Math.abs(wind.speed() - start) <= 1.0F, "speed stays close to a target equal to its start value");

        wind.tick(random);
        float target = wind.targetSpeed();
        float before = Math.abs(wind.speed() - target);
        for (int tick = 0; tick < 200; tick++) {
            wind.tick(random);
        }
        float after = Math.abs(wind.speed() - target);
        assertTrue(after <= before + EPSILON, "speed moves toward the new target: was " + before + " away, now " + after);
    }

    @Test
    void everyRegimeIsReachedOverALongRun() {
        RandomSource random = RandomSource.create(7L);
        WindState wind = WindState.random(random);

        EnumSet<WindRegime> seen = EnumSet.of(wind.regime());
        float prevailingBefore = wind.prevailingDirectionDegrees();
        int tick = 0;
        while (tick < 3_000_000 && seen.size() < WindRegime.values().length) {
            wind.tick(random);
            seen.add(wind.regime());

            float prevailing = wind.prevailingDirectionDegrees();
            if (prevailing != prevailingBefore) {
                assertEquals(0, (tick + 1) % WindState.REGIME_INTERVAL_TICKS,
                    "the prevailing direction only moves when a regime starts, not at tick " + tick);
                float shifted = Headings.wrapDegrees(prevailing - prevailingBefore);
                float smallestShift = Math.min(shifted, 360.0F - shifted);
                assertTrue(smallestShift <= WindState.MAX_PREVAILING_SHIFT + EPSILON,
                    "prevailing direction jumped " + smallestShift + " degrees at tick " + tick);
                prevailingBefore = prevailing;
            }
            tick++;
        }

        assertEquals(EnumSet.allOf(WindRegime.class), seen, "every regime shows up given enough time");
    }

    @Test
    void regimePickFollowsTheWeights() {
        RandomSource random = RandomSource.create(123L);
        Map<WindRegime, Integer> counts = new EnumMap<>(WindRegime.class);
        int samples = 100_000;
        for (int i = 0; i < samples; i++) {
            counts.merge(WindRegime.pick(random), 1, Integer::sum);
        }

        int totalWeight = 0;
        for (WindRegime regime : WindRegime.values()) {
            totalWeight += regime.weight();
        }
        assertEquals(100, totalWeight, "the regime weights are percentages");

        for (WindRegime regime : WindRegime.values()) {
            double share = counts.getOrDefault(regime, 0) / (double) samples;
            double expected = regime.weight() / (double) totalWeight;
            assertTrue(Math.abs(share - expected) < 0.015,
                regime + " picked " + share + " of the time, expected about " + expected);
        }
    }

    @Test
    void regimesMatchTheirDesign() {
        assertEquals(16.0F, WindState.MAX_SPEED, EPSILON);
        assertEquals(WindState.MAX_SPEED * 1.9F, WindState.MAX_GUST_SPEED, EPSILON);

        assertEquals(0.0F, WindRegime.CALM.minSpeed(), EPSILON);
        assertEquals(3.0F, WindRegime.CALM.maxSpeed(), EPSILON);
        assertEquals(2.0F, WindRegime.BREEZE.minSpeed(), EPSILON);
        assertEquals(7.0F, WindRegime.BREEZE.maxSpeed(), EPSILON);
        assertEquals(5.0F, WindRegime.WINDY.minSpeed(), EPSILON);
        assertEquals(11.0F, WindRegime.WINDY.maxSpeed(), EPSILON);
        assertEquals(9.0F, WindRegime.GALE.minSpeed(), EPSILON);
        assertEquals(15.0F, WindRegime.GALE.maxSpeed(), EPSILON);

        assertEquals(1.3F, WindRegime.CALM.maxGustFactor(), EPSILON);
        assertEquals(1.5F, WindRegime.BREEZE.maxGustFactor(), EPSILON);
        assertEquals(1.7F, WindRegime.WINDY.maxGustFactor(), EPSILON);
        assertEquals(1.9F, WindRegime.GALE.maxGustFactor(), EPSILON);

        for (WindRegime regime : WindRegime.values()) {
            assertTrue(regime.maxSpeed() <= WindState.MAX_SPEED, regime + " fits under the speed cap");
            assertEquals(regime.name().toLowerCase(Locale.ROOT), regime.getSerializedName());
            assertEquals(regime, WindRegime.byName(regime.getSerializedName()).orElseThrow());
        }
        assertTrue(WindRegime.byName("hurricane").isEmpty());

        assertEquals(WindRegime.CALM, WindRegime.forSpeed(0.0F));
        assertEquals(WindRegime.BREEZE, WindRegime.forSpeed(4.5F));
        assertEquals(WindRegime.WINDY, WindRegime.forSpeed(8.0F));
        assertEquals(WindRegime.GALE, WindRegime.forSpeed(WindState.MAX_SPEED));
    }

    @Test
    void directionIsPulledTowardThePrevailingDirection() {
        WindState wind = WindState.load(windTag(0.0F, 4.0F, 90.0F));
        RandomSource steady = new FixedRandom(0.5F);

        for (int tick = 0; tick < 100; tick++) {
            wind.tick(steady);
        }
        assertEquals(100 * WindState.REGIME_PULL_DEGREES, wind.directionDegrees(), 1.0E-3F,
            "with no random drift the direction turns by the full pull every tick");
        assertEquals(90.0F, wind.prevailingDirectionDegrees(), EPSILON);

        for (int tick = 0; tick < 700; tick++) {
            wind.tick(steady);
        }
        assertEquals(90.0F, wind.directionDegrees(), 1.0E-3F, "the pull stops once the direction reaches prevailing");
        assertEquals(4.0F, wind.speed(), EPSILON, "a steady random leaves the speed on its target");
    }

    @Test
    void pullTakesTheShortestWayAround() {
        WindState wind = WindState.load(windTag(350.0F, 4.0F, 10.0F));
        RandomSource steady = new FixedRandom(0.5F);

        for (int tick = 0; tick < 100; tick++) {
            wind.tick(steady);
        }
        assertEquals(5.0F, wind.directionDegrees(), 1.0E-3F, "350 pulled toward 10 passes through north");

        WindState other = WindState.load(windTag(10.0F, 4.0F, 350.0F));
        for (int tick = 0; tick < 100; tick++) {
            other.tick(steady);
        }
        assertEquals(355.0F, other.directionDegrees(), 1.0E-3F, "10 pulled toward 350 passes through north");
    }

    @Test
    void saveThenLoadRoundTrips() {
        RandomSource random = RandomSource.create(99L);
        WindState wind = WindState.random(random);
        for (int tick = 0; tick < 37; tick++) {
            wind.tick(random);
        }

        CompoundTag tag = wind.save();
        WindState loaded = WindState.load(tag);

        assertEquals(wind.directionDegrees(), loaded.directionDegrees(), EPSILON);
        assertEquals(wind.speed(), loaded.speed(), EPSILON);
        assertEquals(wind.targetSpeed(), loaded.targetSpeed(), EPSILON);
        assertEquals(wind.regime(), loaded.regime());
        assertEquals(wind.prevailingDirectionDegrees(), loaded.prevailingDirectionDegrees(), EPSILON);
        assertEquals(wind.gustFactor(), loaded.gustFactor(), EPSILON);
        assertEquals(wind.isGusting(), loaded.isGusting());
        assertEquals(tag, loaded.save());
    }

    @Test
    void saveThenLoadRoundTripsWithAGustActive() {
        RandomSource random = RandomSource.create(4242L);
        WindState wind = WindState.random(random);
        int tick = 0;
        while (tick < 200_000 && !(wind.isGusting() && wind.gustFactor() > 1.0F)) {
            wind.tick(random);
            tick++;
        }
        assertTrue(wind.isGusting(), "a gust starts within two hundred thousand ticks");
        assertTrue(wind.gustFactor() > 1.0F);
        assertTrue(wind.gustSpeed() > wind.speed() || wind.speed() == 0.0F);

        CompoundTag tag = wind.save();
        WindState loaded = WindState.load(tag);

        assertTrue(loaded.isGusting());
        assertEquals(wind.gustFactor(), loaded.gustFactor(), EPSILON);
        assertEquals(wind.gustSpeed(), loaded.gustSpeed(), EPSILON);
        assertEquals(wind.regime(), loaded.regime());
        assertEquals(wind.prevailingDirectionDegrees(), loaded.prevailingDirectionDegrees(), EPSILON);
        assertEquals(wind.directionDegrees(), loaded.directionDegrees(), EPSILON);
        assertEquals(wind.speed(), loaded.speed(), EPSILON);
        assertEquals(wind.targetSpeed(), loaded.targetSpeed(), EPSILON);
        assertEquals(tag, loaded.save());

        RandomSource left = RandomSource.create(77L);
        RandomSource right = RandomSource.create(77L);
        boolean sawGustEnd = false;
        for (int step = 0; step < WindState.MAX_GUST_TICKS + 5; step++) {
            wind.tick(left);
            loaded.tick(right);
            assertEquals(wind.gustFactor(), loaded.gustFactor(), EPSILON,
                "the restored gust follows the same envelope at step " + step);
            assertEquals(wind.isGusting(), loaded.isGusting());
            sawGustEnd |= !wind.isGusting();
        }
        assertTrue(sawGustEnd, "the gust ends within its longest possible length");
    }

    @Test
    void legacyTagWithoutRegimeOrGustStillLoads() {
        CompoundTag legacy = new CompoundTag();
        legacy.putFloat("Direction", 123.5F);
        legacy.putFloat("Speed", 4.5F);
        legacy.putFloat("TargetSpeed", 6.0F);
        legacy.putInt("TargetTicks", 300);

        WindState wind = WindState.load(legacy);

        assertEquals(123.5F, wind.directionDegrees(), EPSILON);
        assertEquals(4.5F, wind.speed(), EPSILON);
        assertEquals(6.0F, wind.targetSpeed(), EPSILON);
        assertEquals(WindRegime.forSpeed(4.5F), wind.regime(), "the regime comes from the speed");
        assertEquals(123.5F, wind.prevailingDirectionDegrees(), EPSILON, "prevailing starts at the direction");
        assertEquals(1.0F, wind.gustFactor(), EPSILON);
        assertFalse(wind.isGusting());
        assertEquals(wind.speed(), wind.gustSpeed(), EPSILON);

        CompoundTag upgraded = wind.save();
        for (String key : new String[] {"Regime", "Prevailing", "RegimeTicks", "GustFactor", "GustTicks", "GustLength", "GustPeak"}) {
            assertTrue(upgraded.contains(key), "saving a legacy wind writes " + key);
        }
        assertEquals("breeze", upgraded.getString("Regime"));

        RandomSource random = RandomSource.create(11L);
        for (int tick = 0; tick < 300; tick++) {
            wind.tick(random);
            assertTrue(wind.speed() >= WindState.MIN_SPEED && wind.speed() <= WindState.MAX_SPEED);
            assertTrue(wind.gustFactor() >= 1.0F && wind.gustFactor() <= wind.regime().maxGustFactor() + EPSILON);
        }
        assertEquals(WindRegime.BREEZE, wind.regime(), "a legacy wind keeps its derived regime for a full interval");
    }

    @Test
    void loadOfAnEmptyTagGivesACalmValidWind() {
        WindState wind = WindState.load(new CompoundTag());
        assertEquals(0.0F, wind.directionDegrees(), EPSILON);
        assertEquals(WindState.MIN_SPEED, wind.speed(), EPSILON);
        assertEquals(WindRegime.CALM, wind.regime());
        assertFalse(wind.isGusting());

        wind.tick(RandomSource.create(5L));
        assertTrue(wind.targetSpeed() >= WindState.MIN_SPEED && wind.targetSpeed() <= WindState.MAX_SPEED);
    }

    @Test
    void setWrapsDirectionAndClampsSpeed() {
        WindState wind = WindState.load(windTag(0.0F, 10.0F, 45.0F));
        wind.tick(RandomSource.create(3L));
        WindRegime regime = wind.regime();

        wind.set(-90.0F, 1000.0F);
        assertEquals(270.0F, wind.directionDegrees(), EPSILON);
        assertEquals(270.0F, wind.prevailingDirectionDegrees(), EPSILON, "set moves the prevailing direction too");
        assertEquals(WindState.MAX_SPEED, wind.speed(), EPSILON);
        assertEquals(WindState.MAX_SPEED, wind.targetSpeed(), EPSILON);
        assertEquals(regime, wind.regime(), "set keeps the regime");

        wind.set(725.0F, -3.0F);
        assertEquals(5.0F, wind.directionDegrees(), EPSILON);
        assertEquals(WindState.MIN_SPEED, wind.speed(), EPSILON);
        assertEquals(0.0F, wind.gustSpeed(), EPSILON, "no sustained wind means no gust speed either");

        wind.set(360.0F, 1.0F);
        assertEquals(0.0F, wind.directionDegrees(), EPSILON);
    }

    @Test
    void velocityFollowsTheCompass() {
        WindState east = new WindState(90.0F, 4.0F);
        assertEquals(4.0D, east.velocityX(), 1.0E-6D);
        assertEquals(0.0D, east.velocityZ(), 1.0E-6D);

        WindState north = new WindState(0.0F, 2.5F);
        assertEquals(0.0D, north.velocityX(), 1.0E-6D);
        assertEquals(-2.5D, north.velocityZ(), 1.0E-6D, "heading 0 is north, toward negative z");
    }

    /** A saved wind in the breeze regime with full timers, so the direction and prevailing direction differ. */
    private static CompoundTag windTag(float direction, float speed, float prevailing) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Direction", direction);
        tag.putFloat("Speed", speed);
        tag.putFloat("TargetSpeed", speed);
        tag.putInt("TargetTicks", WindState.TARGET_INTERVAL_TICKS);
        tag.putString("Regime", WindRegime.BREEZE.getSerializedName());
        tag.putFloat("Prevailing", prevailing);
        tag.putInt("RegimeTicks", WindState.REGIME_INTERVAL_TICKS);
        return tag;
    }

    /**
     * A random source that always answers the same fraction and never rolls a zero, so the wind neither drifts,
     * jitters nor gusts and only the deterministic parts of the walk remain.
     */
    private static final class FixedRandom implements RandomSource {
        private final float value;

        FixedRandom(float value) {
            this.value = value;
        }

        @Override
        public RandomSource fork() {
            return new FixedRandom(value);
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            throw new UnsupportedOperationException("not needed by the wind");
        }

        @Override
        public void setSeed(long seed) {
        }

        @Override
        public int nextInt() {
            return 1;
        }

        @Override
        public int nextInt(int bound) {
            return bound - 1;
        }

        @Override
        public long nextLong() {
            return 1L;
        }

        @Override
        public boolean nextBoolean() {
            return false;
        }

        @Override
        public float nextFloat() {
            return value;
        }

        @Override
        public double nextDouble() {
            return value;
        }

        @Override
        public double nextGaussian() {
            return 0.0D;
        }
    }
}
