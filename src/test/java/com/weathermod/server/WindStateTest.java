package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Checks the wind random walk stays inside its bounds over a long run and that it survives NBT. Needs no
 * Minecraft bootstrap.
 */
class WindStateTest {
    private static final float EPSILON = 1.0E-4F;

    @Test
    void randomWalkStaysWithinBoundsOverTenThousandTicks() {
        RandomSource random = RandomSource.create(20240517L);
        WindState wind = WindState.random(random);

        for (int tick = 0; tick < 10_000; tick++) {
            float directionBefore = wind.directionDegrees();
            float speedBefore = wind.speed();

            wind.tick(random);

            float direction = wind.directionDegrees();
            assertTrue(direction >= 0.0F && direction < 360.0F, "direction out of range at tick " + tick + ": " + direction);

            float speed = wind.speed();
            assertTrue(speed >= WindState.MIN_SPEED && speed <= WindState.MAX_SPEED,
                "speed out of range at tick " + tick + ": " + speed);

            float turned = Headings.wrapDegrees(direction - directionBefore);
            float smallestTurn = Math.min(turned, 360.0F - turned);
            assertTrue(smallestTurn <= WindState.MAX_DIRECTION_DRIFT + EPSILON,
                "direction jumped " + smallestTurn + " degrees at tick " + tick);

            float speedChange = Math.abs(speed - speedBefore);
            assertTrue(speedChange <= WindState.MAX_SPEED_STEP + 0.005F + EPSILON,
                "speed jumped " + speedChange + " at tick " + tick);

            float target = wind.targetSpeed();
            assertTrue(target >= WindState.MIN_SPEED && target <= WindState.MAX_SPEED,
                "target speed out of range at tick " + tick + ": " + target);
        }
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
        assertEquals(tag, loaded.save());
    }

    @Test
    void loadOfAnEmptyTagGivesACalmValidWind() {
        WindState wind = WindState.load(new CompoundTag());
        assertEquals(0.0F, wind.directionDegrees(), EPSILON);
        assertEquals(WindState.MIN_SPEED, wind.speed(), EPSILON);

        wind.tick(RandomSource.create(5L));
        assertTrue(wind.targetSpeed() >= WindState.MIN_SPEED && wind.targetSpeed() <= WindState.MAX_SPEED);
    }

    @Test
    void setWrapsDirectionAndClampsSpeed() {
        WindState wind = new WindState(0.0F, 0.0F);

        wind.set(-90.0F, 1000.0F);
        assertEquals(270.0F, wind.directionDegrees(), EPSILON);
        assertEquals(WindState.MAX_SPEED, wind.speed(), EPSILON);
        assertEquals(WindState.MAX_SPEED, wind.targetSpeed(), EPSILON);

        wind.set(725.0F, -3.0F);
        assertEquals(5.0F, wind.directionDegrees(), EPSILON);
        assertEquals(WindState.MIN_SPEED, wind.speed(), EPSILON);

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
}
