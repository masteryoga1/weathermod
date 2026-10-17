package com.weathermod.server;

import com.weathermod.core.Headings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The prevailing wind of one dimension: a compass direction and a sustained speed that random-walk over time,
 * pulled toward the direction of a regime that changes every ten minutes, with short gusts on top. Storms that
 * are not steered by a command move with it.
 */
public final class WindState {
    /** Slowest sustained wind the random walk allows, in blocks per second. */
    public static final float MIN_SPEED = 0.0F;
    /** Fastest sustained wind the random walk allows, in blocks per second; gusts may exceed it. */
    public static final float MAX_SPEED = 16.0F;
    /** Largest random change of direction in one tick, in degrees, before the pull toward the regime direction. */
    public static final float MAX_DIRECTION_DRIFT = 0.5F;
    /** Largest change of sustained speed in one tick, in blocks per second. */
    public static final float MAX_SPEED_STEP = 0.01F;
    /** Ticks between picks of a new target speed. */
    public static final int TARGET_INTERVAL_TICKS = 1200;
    /** Ticks between picks of a new regime and a new prevailing direction. */
    public static final int REGIME_INTERVAL_TICKS = 12000;
    /** Largest pull of the direction toward the regime's prevailing direction in one tick, in degrees. */
    public static final float REGIME_PULL_DEGREES = 0.15F;
    /** Largest change of the prevailing direction when a new regime starts, in degrees either way. */
    public static final float MAX_PREVAILING_SHIFT = 90.0F;
    /** Largest gust multiplier any regime allows, see {@link WindRegime#maxGustFactor()}. */
    public static final float MAX_GUST_FACTOR = 1.9F;
    /** Fastest gust, in blocks per second: {@link #MAX_SPEED} times {@link #MAX_GUST_FACTOR}. */
    public static final float MAX_GUST_SPEED = MAX_SPEED * MAX_GUST_FACTOR;
    /** Smallest peak multiplier of a gust. */
    public static final float MIN_GUST_PEAK = 1.15F;
    /** Shortest gust, in ticks. */
    public static final int MIN_GUST_TICKS = 20;
    /** Longest gust, in ticks. */
    public static final int MAX_GUST_TICKS = 80;
    /** One gust starts per this many ticks on average while none is active, outside a gale. */
    public static final int GUST_CHANCE = 600;
    /** One gust starts per this many ticks on average while none is active in a gale. */
    public static final int GUST_CHANCE_GALE = 300;

    private static final float SPEED_JITTER = 0.005F;

    private static final String KEY_DIRECTION = "Direction";
    private static final String KEY_SPEED = "Speed";
    private static final String KEY_TARGET_SPEED = "TargetSpeed";
    private static final String KEY_TARGET_TICKS = "TargetTicks";
    private static final String KEY_REGIME = "Regime";
    private static final String KEY_PREVAILING = "Prevailing";
    private static final String KEY_REGIME_TICKS = "RegimeTicks";
    private static final String KEY_GUST_FACTOR = "GustFactor";
    private static final String KEY_GUST_TICKS = "GustTicks";
    private static final String KEY_GUST_LENGTH = "GustLength";
    private static final String KEY_GUST_PEAK = "GustPeak";

    private float directionDegrees;
    private float speed;
    private float targetSpeed;
    private int ticksUntilNewTarget;

    private WindRegime regime;
    private float prevailingDirectionDegrees;
    private int ticksUntilNewRegime;

    private float gustFactor;
    private int gustTicks;
    private int gustLength;
    private float gustPeak;

    /**
     * Creates a wind with the given direction and speed, no gust, the regime closest to that speed and the
     * direction itself as prevailing direction. The direction is wrapped into {@code [0, 360)} and the speed
     * clamped into {@code [MIN_SPEED, MAX_SPEED]}.
     */
    public WindState(float directionDegrees, float speed) {
        this.directionDegrees = Headings.wrapDegrees(directionDegrees);
        this.speed = clampSpeed(speed);
        this.targetSpeed = this.speed;
        this.ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
        this.regime = WindRegime.forSpeed(this.speed);
        this.prevailingDirectionDegrees = this.directionDegrees;
        this.ticksUntilNewRegime = REGIME_INTERVAL_TICKS;
        clearGust();
    }

    /** Creates a wind with a random direction, a random regime and a random speed inside that regime's band. */
    public static WindState random(RandomSource random) {
        WindRegime regime = WindRegime.pick(random);
        float direction = random.nextFloat() * 360.0F;
        float speed = rollSpeed(regime, random);
        WindState wind = new WindState(direction, speed);
        wind.regime = regime;
        return wind;
    }

    /**
     * Compass direction the wind blows toward, in degrees: 0 is north ({@code -Z}), 90 is east ({@code +X}),
     * 180 is south ({@code +Z}) and 270 is west ({@code -X}), see {@link Headings}. Always in {@code [0, 360)}.
     */
    public float directionDegrees() {
        return directionDegrees;
    }

    /** Sustained wind speed in blocks per second, in {@code [MIN_SPEED, MAX_SPEED]}. */
    public float speed() {
        return speed;
    }

    /** The speed the random walk is currently drifting toward. */
    public float targetSpeed() {
        return targetSpeed;
    }

    /** The regime whose speed band the target speed is rolled in; it changes every {@link #REGIME_INTERVAL_TICKS}. */
    public WindRegime regime() {
        return regime;
    }

    /** Compass direction the random walk is pulled toward, in {@code [0, 360)}; a new one comes with each regime. */
    public float prevailingDirectionDegrees() {
        return prevailingDirectionDegrees;
    }

    /** Multiplier a gust currently applies to the sustained speed: 1 without a gust, up to the regime's maximum. */
    public float gustFactor() {
        return gustFactor;
    }

    /** Momentary wind speed including the gust, in blocks per second, never above {@link #MAX_GUST_SPEED}. */
    public float gustSpeed() {
        return Math.min(speed * gustFactor, MAX_GUST_SPEED);
    }

    /** Whether a gust is in progress. */
    public boolean isGusting() {
        return gustLength > 0;
    }

    /** Horizontal velocity along {@code X} in blocks per second. */
    public double velocityX() {
        return Headings.directionX(directionDegrees) * speed;
    }

    /** Horizontal velocity along {@code Z} in blocks per second. */
    public double velocityZ() {
        return Headings.directionZ(directionDegrees) * speed;
    }

    /**
     * Sets direction and speed directly, as the wind command does. The new direction also becomes the prevailing
     * direction and the new speed the drift target; the target timer restarts, so the value holds for a while
     * before the random walk resumes. The regime and any running gust are kept.
     */
    public void set(float directionDegrees, float speed) {
        this.directionDegrees = Headings.wrapDegrees(directionDegrees);
        this.prevailingDirectionDegrees = this.directionDegrees;
        this.speed = clampSpeed(speed);
        this.targetSpeed = this.speed;
        this.ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
    }

    /**
     * Advances the wind by one tick. Every {@link #REGIME_INTERVAL_TICKS} a new regime and a new prevailing
     * direction (within {@link #MAX_PREVAILING_SHIFT} of the old one) are picked and the target speed re-rolls in
     * the regime's band. The direction drifts by at most {@link #MAX_DIRECTION_DRIFT} degrees plus a pull of at
     * most {@link #REGIME_PULL_DEGREES} toward the prevailing direction; the speed moves toward a target that is
     * re-rolled every {@link #TARGET_INTERVAL_TICKS}. Gusts start at random and follow a sine envelope.
     */
    public void tick(RandomSource random) {
        if (--ticksUntilNewRegime <= 0) {
            startRegime(WindRegime.pick(random), random);
        }

        float drift = (random.nextFloat() * 2.0F - 1.0F) * MAX_DIRECTION_DRIFT;
        float toward = Mth.wrapDegrees(prevailingDirectionDegrees - directionDegrees);
        float pull = Mth.clamp(toward, -REGIME_PULL_DEGREES, REGIME_PULL_DEGREES);
        directionDegrees = Headings.wrapDegrees(directionDegrees + drift + pull);

        if (--ticksUntilNewTarget <= 0) {
            targetSpeed = rollSpeed(regime, random);
            ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
        }

        float step = Mth.clamp(targetSpeed - speed, -MAX_SPEED_STEP, MAX_SPEED_STEP);
        float jitter = (random.nextFloat() * 2.0F - 1.0F) * SPEED_JITTER;
        speed = clampSpeed(speed + step + jitter);

        tickGust(random);
    }

    private void startRegime(WindRegime next, RandomSource random) {
        regime = next;
        ticksUntilNewRegime = REGIME_INTERVAL_TICKS;
        float shift = (random.nextFloat() * 2.0F - 1.0F) * MAX_PREVAILING_SHIFT;
        prevailingDirectionDegrees = Headings.wrapDegrees(prevailingDirectionDegrees + shift);
        targetSpeed = rollSpeed(regime, random);
        ticksUntilNewTarget = TARGET_INTERVAL_TICKS;
        if (isGusting()) {
            gustPeak = Math.min(gustPeak, regime.maxGustFactor());
            gustFactor = Math.min(gustFactor, gustPeak);
        }
    }

    private void tickGust(RandomSource random) {
        if (isGusting()) {
            gustTicks++;
            if (gustTicks >= gustLength) {
                clearGust();
            } else {
                float envelope = (float) Math.sin(Math.PI * gustTicks / gustLength);
                gustFactor = Mth.clamp(1.0F + (gustPeak - 1.0F) * envelope, 1.0F, gustPeak);
            }
            return;
        }
        int chance = regime == WindRegime.GALE ? GUST_CHANCE_GALE : GUST_CHANCE;
        if (random.nextInt(chance) == 0) {
            gustLength = MIN_GUST_TICKS + random.nextInt(MAX_GUST_TICKS - MIN_GUST_TICKS + 1);
            gustTicks = 0;
            gustPeak = MIN_GUST_PEAK + random.nextFloat() * (regime.maxGustFactor() - MIN_GUST_PEAK);
            gustFactor = 1.0F;
        }
    }

    private void clearGust() {
        gustLength = 0;
        gustTicks = 0;
        gustPeak = 1.0F;
        gustFactor = 1.0F;
    }

    /** Writes the wind to a new compound tag. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(KEY_DIRECTION, directionDegrees);
        tag.putFloat(KEY_SPEED, speed);
        tag.putFloat(KEY_TARGET_SPEED, targetSpeed);
        tag.putInt(KEY_TARGET_TICKS, ticksUntilNewTarget);
        tag.putString(KEY_REGIME, regime.getSerializedName());
        tag.putFloat(KEY_PREVAILING, prevailingDirectionDegrees);
        tag.putInt(KEY_REGIME_TICKS, ticksUntilNewRegime);
        tag.putFloat(KEY_GUST_FACTOR, gustFactor);
        tag.putInt(KEY_GUST_TICKS, gustTicks);
        tag.putInt(KEY_GUST_LENGTH, gustLength);
        tag.putFloat(KEY_GUST_PEAK, gustPeak);
        return tag;
    }

    /**
     * Reads a wind written by {@link #save()}. Every key is optional: a tag from before regimes and gusts
     * existed loads with the regime closest to its speed, its direction as prevailing direction and no gust;
     * missing direction and speed fall back to a calm wind that re-rolls its target at once.
     */
    public static WindState load(CompoundTag tag) {
        WindState wind = new WindState(tag.getFloat(KEY_DIRECTION), tag.getFloat(KEY_SPEED));
        wind.targetSpeed = tag.contains(KEY_TARGET_SPEED) ? clampSpeed(tag.getFloat(KEY_TARGET_SPEED)) : wind.speed;
        int ticks = tag.getInt(KEY_TARGET_TICKS);
        wind.ticksUntilNewTarget = ticks > 0 ? Math.min(ticks, TARGET_INTERVAL_TICKS) : 1;

        if (tag.contains(KEY_REGIME, Tag.TAG_STRING)) {
            wind.regime = WindRegime.byName(tag.getString(KEY_REGIME)).orElse(wind.regime);
        }
        if (tag.contains(KEY_PREVAILING, Tag.TAG_ANY_NUMERIC)) {
            wind.prevailingDirectionDegrees = Headings.wrapDegrees(tag.getFloat(KEY_PREVAILING));
        }
        if (tag.contains(KEY_REGIME_TICKS, Tag.TAG_ANY_NUMERIC)) {
            int regimeTicks = tag.getInt(KEY_REGIME_TICKS);
            wind.ticksUntilNewRegime = regimeTicks > 0 ? Math.min(regimeTicks, REGIME_INTERVAL_TICKS) : 1;
        }

        int gustLength = Mth.clamp(tag.getInt(KEY_GUST_LENGTH), 0, MAX_GUST_TICKS);
        if (gustLength > 0) {
            wind.gustLength = gustLength;
            wind.gustTicks = Mth.clamp(tag.getInt(KEY_GUST_TICKS), 0, gustLength - 1);
            wind.gustPeak = Mth.clamp(tag.getFloat(KEY_GUST_PEAK), 1.0F, wind.regime.maxGustFactor());
            wind.gustFactor = Mth.clamp(tag.getFloat(KEY_GUST_FACTOR), 1.0F, wind.gustPeak);
        }
        return wind;
    }

    private static float rollSpeed(WindRegime regime, RandomSource random) {
        return regime.minSpeed() + random.nextFloat() * (regime.maxSpeed() - regime.minSpeed());
    }

    private static float clampSpeed(float value) {
        return Mth.clamp(value, MIN_SPEED, MAX_SPEED);
    }

    @Override
    public String toString() {
        return "WindState[" + directionDegrees + " deg, " + speed + " b/s, " + regime.getSerializedName()
            + ", prevailing " + prevailingDirectionDegrees + " deg, gust x" + gustFactor + "]";
    }
}
