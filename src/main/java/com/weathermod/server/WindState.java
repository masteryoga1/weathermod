package com.weathermod.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * The prevailing wind of one dimension: a compass direction and a speed that random-walk over time.
 * Storms that are not steered by a command move with it.
 */
public final class WindState {
    /** Slowest wind the random walk allows, in blocks per second. */
    public static final float MIN_SPEED = 0.0F;
    /** Fastest wind the random walk allows, in blocks per second. */
    public static final float MAX_SPEED = 12.0F;
    /** Largest change of direction in one tick, in degrees. */
    public static final float MAX_DIRECTION_DRIFT = 0.5F;
    /** Largest change of speed in one tick, in blocks per second. */
    public static final float MAX_SPEED_STEP = 0.01F;
    /** Ticks between picks of a new target speed. */
    public static final int TARGET_INTERVAL_TICKS = 1200;

    private static final float TARGET_SPEED_MIN = 1.0F;
    private static final float TARGET_SPEED_MAX = 8.0F;
    private static final float SPEED_JITTER = 0.005F;

    private static final String KEY_DIRECTION = "Direction";
    private static final String KEY_SPEED = "Speed";
    private static final String KEY_TARGET_SPEED = "TargetSpeed";
    private static final String KEY_TARGET_TICKS = "TargetTicks";

    private float directionDegrees;
    private float speed;
    private float targetSpeed;
    private int ticksUntilNewTarget;

    /**
     * Creates a wind with the given direction and speed. The direction is wrapped into {@code [0, 360)} and the
     * speed clamped into {@code [MIN_SPEED, MAX_SPEED]}.
     */
    public WindState(float directionDegrees, float speed) {
        this.directionDegrees = wrapDegrees(directionDegrees);
        this.speed = clampSpeed(speed);
        this.targetSpeed = this.speed;
        this.ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
    }

    /** Creates a wind with a random direction and a random speed inside the natural target range. */
    public static WindState random(RandomSource random) {
        float direction = random.nextFloat() * 360.0F;
        float speed = TARGET_SPEED_MIN + random.nextFloat() * (TARGET_SPEED_MAX - TARGET_SPEED_MIN);
        return new WindState(direction, speed);
    }

    /**
     * Compass direction the wind blows toward, in degrees: 0 is north ({@code -Z}), 90 is east ({@code +X}),
     * 180 is south ({@code +Z}) and 270 is west ({@code -X}). Always in {@code [0, 360)}.
     */
    public float directionDegrees() {
        return directionDegrees;
    }

    /** Wind speed in blocks per second, in {@code [MIN_SPEED, MAX_SPEED]}. */
    public float speed() {
        return speed;
    }

    /** The speed the random walk is currently drifting toward. */
    public float targetSpeed() {
        return targetSpeed;
    }

    /** Horizontal velocity along {@code X} in blocks per second. */
    public double velocityX() {
        return directionX(directionDegrees) * speed;
    }

    /** Horizontal velocity along {@code Z} in blocks per second. */
    public double velocityZ() {
        return directionZ(directionDegrees) * speed;
    }

    /**
     * Sets direction and speed directly, as the wind command does. The new speed also becomes the drift target
     * and the target timer restarts, so the value holds for a while before the random walk resumes.
     */
    public void set(float directionDegrees, float speed) {
        this.directionDegrees = wrapDegrees(directionDegrees);
        this.speed = clampSpeed(speed);
        this.targetSpeed = this.speed;
        this.ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
    }

    /**
     * Advances the random walk by one tick: the direction drifts by at most {@link #MAX_DIRECTION_DRIFT} degrees
     * and the speed moves toward a target that is re-rolled every {@link #TARGET_INTERVAL_TICKS} ticks.
     */
    public void tick(RandomSource random) {
        directionDegrees = wrapDegrees(directionDegrees + (random.nextFloat() * 2.0F - 1.0F) * MAX_DIRECTION_DRIFT);

        if (--ticksUntilNewTarget <= 0) {
            targetSpeed = TARGET_SPEED_MIN + random.nextFloat() * (TARGET_SPEED_MAX - TARGET_SPEED_MIN);
            ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
        }

        float step = clamp(targetSpeed - speed, -MAX_SPEED_STEP, MAX_SPEED_STEP);
        float jitter = (random.nextFloat() * 2.0F - 1.0F) * SPEED_JITTER;
        speed = clampSpeed(speed + step + jitter);
    }

    /** Writes the wind to a new compound tag. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(KEY_DIRECTION, directionDegrees);
        tag.putFloat(KEY_SPEED, speed);
        tag.putFloat(KEY_TARGET_SPEED, targetSpeed);
        tag.putInt(KEY_TARGET_TICKS, ticksUntilNewTarget);
        return tag;
    }

    /** Reads a wind written by {@link #save()}. Missing values fall back to a calm wind that re-rolls at once. */
    public static WindState load(CompoundTag tag) {
        WindState wind = new WindState(tag.getFloat(KEY_DIRECTION), tag.getFloat(KEY_SPEED));
        wind.targetSpeed = tag.contains(KEY_TARGET_SPEED) ? clampSpeed(tag.getFloat(KEY_TARGET_SPEED)) : wind.speed;
        int ticks = tag.getInt(KEY_TARGET_TICKS);
        wind.ticksUntilNewTarget = ticks > 0 ? Math.min(ticks, TARGET_INTERVAL_TICKS) : 1;
        return wind;
    }

    /** Wraps an angle into {@code [0, 360)}. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped < 0.0F) {
            wrapped += 360.0F;
        }
        return wrapped >= 360.0F ? 0.0F : wrapped;
    }

    /** Unit {@code X} component of a compass heading, see {@link #directionDegrees()}. */
    public static double directionX(float degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    /** Unit {@code Z} component of a compass heading, see {@link #directionDegrees()}. */
    public static double directionZ(float degrees) {
        return -Math.cos(Math.toRadians(degrees));
    }

    private static float clampSpeed(float value) {
        return clamp(value, MIN_SPEED, MAX_SPEED);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public String toString() {
        return "WindState[" + directionDegrees + " deg, " + speed + " b/s]";
    }
}
