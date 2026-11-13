package com.weathermod.core;

import org.joml.Vector3d;

/**
 * The push of straight wind: the ambient wind of a dimension, the outflow of convective storms and the burst of
 * air a downburst throws outward. Wind speeds are in blocks per second, accelerations in blocks per tick squared.
 * The server calls these functions for the entities it simulates and each client for its own player, with the
 * state the server synced, so both sides push the same way. Pure arithmetic; safe to call from the server and the
 * client thread at once.
 */
public final class WindForce {
    /** Wind below this speed pushes nothing: a gale starts to matter at 10 blocks per second. */
    public static final float PUSH_THRESHOLD_BLOCKS_PER_SECOND = 10.0F;
    /** Acceleration per block per second above the threshold, blocks per tick squared. */
    public static final float PUSH_ACCEL_PER_BLOCK_PER_SECOND = 0.004F;
    /** Cap: with ground friction this drifts a standing player at about 1.4 blocks per second. */
    public static final float MAX_PUSH_ACCEL = 0.06F;
    /** A downburst pushes down inside this fraction of its radius. */
    public static final float DOWNBURST_CORE_FRACTION = 0.3F;
    /** Downward acceleration in the core of a downburst at full strength, blocks per tick squared. */
    public static final float DOWNBURST_DOWN_ACCEL = 0.04F;
    /** Its outflow reaches this many radii. */
    public static final float DOWNBURST_REACH_FACTOR = 1.5F;
    /** A downburst builds up over this many ticks. */
    public static final int DOWNBURST_RAMP_TICKS = 20;
    /** A downburst fades over its last ticks, this many. */
    public static final int DOWNBURST_DECAY_TICKS = 40;

    /** The outflow of a downburst reaches its full speed at this fraction of the radius. */
    private static final float DOWNBURST_FULL_FRACTION = 0.5F;
    /** Closer to a centre than this a body has no direction to be pushed in. */
    private static final double CENTRE_EPSILON_BLOCKS = 1.0E-4;

    private WindForce() {
    }

    /**
     * The acceleration a wind of the given speed gives a body: 0 up to
     * {@link #PUSH_THRESHOLD_BLOCKS_PER_SECOND}, then {@link #PUSH_ACCEL_PER_BLOCK_PER_SECOND} for every block per
     * second above it, capped at {@link #MAX_PUSH_ACCEL}. A speed that is not a number pushes nothing.
     */
    public static float pushAcceleration(float windBlocksPerSecond) {
        if (!(windBlocksPerSecond > PUSH_THRESHOLD_BLOCKS_PER_SECOND)) {
            return 0.0F;
        }
        float acceleration = (windBlocksPerSecond - PUSH_THRESHOLD_BLOCKS_PER_SECOND) * PUSH_ACCEL_PER_BLOCK_PER_SECOND;
        return Math.min(MAX_PUSH_ACCEL, acceleration);
    }

    /**
     * How strongly a downburst blows at an age, 0..1: a linear rise over {@link #DOWNBURST_RAMP_TICKS}, then 1,
     * then a linear fall over the last {@link #DOWNBURST_DECAY_TICKS}; 0 before it starts, from its last tick on
     * and for a duration that is not positive. A downburst too short for both ramps never reaches 1.
     */
    public static float downburstEnvelope(int ageTicks, int durationTicks) {
        if (durationTicks <= 0 || ageTicks < 0 || ageTicks >= durationTicks) {
            return 0.0F;
        }
        float rise = ageTicks / (float) DOWNBURST_RAMP_TICKS;
        float fall = (durationTicks - ageTicks) / (float) DOWNBURST_DECAY_TICKS;
        return Math.min(1.0F, Math.min(rise, fall));
    }

    /**
     * Outflow speed of a downburst at a distance from its centre, in blocks per second:
     * {@code speed * envelope * profile}, the profile rising linearly from 0 at the centre to 1 at half the
     * radius, staying 1 to the radius and falling linearly to 0 at {@link #DOWNBURST_REACH_FACTOR} radii. A radius
     * that is not positive, or a distance that is not a number, gives 0.
     */
    public static float downburstSpeedAt(double distance, float radius, float speed, float envelope) {
        if (!(radius > 0.0F) || !(distance > 0.0)) {
            return 0.0F;
        }
        double full = (double) radius * DOWNBURST_FULL_FRACTION;
        double reach = (double) radius * DOWNBURST_REACH_FACTOR;
        double profile;
        if (distance <= full) {
            profile = distance / full;
        } else if (distance <= radius) {
            profile = 1.0;
        } else if (distance < reach) {
            profile = (reach - distance) / (reach - radius);
        } else {
            return 0.0F;
        }
        return (float) (speed * envelope * profile);
    }

    /**
     * Adds a storm's outflow to a wind vector in blocks per second: the unit vector of the storm's heading times
     * {@code outflowSpeed * coverage}. Only x and z of {@code wind} change.
     *
     * @param headingDegrees compass heading the storm moves toward, see {@link Headings}
     * @param outflowSpeed   the type's outflow at full coverage, in blocks per second
     * @param coverage       how strongly the storm covers the point, 0..1, see {@link Coverage}
     */
    public static void addOutflow(float headingDegrees, float outflowSpeed, float coverage, Vector3d wind) {
        double speed = (double) outflowSpeed * coverage;
        wind.x += Headings.directionX(headingDegrees) * speed;
        wind.z += Headings.directionZ(headingDegrees) * speed;
    }

    /**
     * Adds the ambient wind to a wind vector in blocks per second. Only x and z of {@code wind} change.
     *
     * @param directionDegrees compass direction the wind blows toward, see {@link Headings}
     * @param speed            wind speed in blocks per second
     */
    public static void addWind(float directionDegrees, float speed, Vector3d wind) {
        wind.x += Headings.directionX(directionDegrees) * speed;
        wind.z += Headings.directionZ(directionDegrees) * speed;
    }

    /**
     * Turns a horizontal wind vector in blocks per second into a push in blocks per tick squared: its direction
     * times {@link #pushAcceleration(float)} of its length. The y of {@code wind} is ignored and the y of
     * {@code out} is 0. {@code out} is always written and may be the same vector as {@code wind}.
     */
    public static void toAcceleration(Vector3d wind, Vector3d out) {
        double x = wind.x;
        double z = wind.z;
        double speed = Math.sqrt(x * x + z * z);
        float acceleration = pushAcceleration((float) speed);
        if (!(acceleration > 0.0F)) {
            out.set(0.0, 0.0, 0.0);
            return;
        }
        out.set(x / speed * acceleration, 0.0, z / speed * acceleration);
    }

    /**
     * The acceleration a downburst gives a body at {@code (x, z)}: radially outward by
     * {@link #pushAcceleration(float)} of {@link #downburstSpeedAt}, and downward by
     * {@link #DOWNBURST_DOWN_ACCEL} times the envelope inside {@link #DOWNBURST_CORE_FRACTION} of the radius.
     * Adds to {@code out}, so several downbursts and other forces can be summed in one vector.
     *
     * @param radius   radius of the downburst in blocks
     * @param speed    its peak outflow in blocks per second
     * @param envelope how strongly it blows now, 0..1, see {@link #downburstEnvelope(int, int)}
     */
    public static void addDownburst(double centreX, double centreZ, float radius, float speed, float envelope,
                                    double x, double z, Vector3d out) {
        if (!(radius > 0.0F) || !(envelope > 0.0F)) {
            return;
        }
        double dx = x - centreX;
        double dz = z - centreZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        float outward = pushAcceleration(downburstSpeedAt(distance, radius, speed, envelope));
        if (outward > 0.0F && distance >= CENTRE_EPSILON_BLOCKS) {
            out.x += dx / distance * outward;
            out.z += dz / distance * outward;
        }
        if (distance <= (double) radius * DOWNBURST_CORE_FRACTION) {
            out.y -= (double) DOWNBURST_DOWN_ACCEL * envelope;
        }
    }
}
