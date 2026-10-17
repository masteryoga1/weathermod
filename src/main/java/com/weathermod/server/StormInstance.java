package com.weathermod.server;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import com.weathermod.network.StormSnapshot;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * One storm in a dimension, simulated on the server. It moves with the wind unless a command steers it, ages
 * toward its maximum age and ramps its intensity in over the first tenth of its life and out over the last fifth.
 */
public final class StormInstance {
    /** Cloud base height every new storm starts at. */
    public static final double DEFAULT_CLOUD_BASE = 160.0D;
    /** Fraction of the storm's life spent ramping intensity up from zero. */
    public static final float RAMP_UP_FRACTION = 0.1F;
    /** Fraction of the storm's life spent ramping intensity back down to zero. */
    public static final float RAMP_DOWN_FRACTION = 0.2F;
    /** Fastest speed a steered storm may be given, in blocks per second. */
    public static final float MAX_STEER_SPEED = 64.0F;
    /** Game ticks in one real second. */
    public static final int TICKS_PER_SECOND = 20;

    private static final float DEFAULT_RADIUS = 96.0F;
    private static final float DEFAULT_INTEREST_RADIUS = 512.0F;

    private static final String KEY_ID = "Id";
    private static final String KEY_TYPE = "Type";
    private static final String KEY_X = "X";
    private static final String KEY_Y = "Y";
    private static final String KEY_Z = "Z";
    private static final String KEY_HEADING = "Heading";
    private static final String KEY_SPEED = "Speed";
    private static final String KEY_INTENSITY = "Intensity";
    private static final String KEY_PEAK_INTENSITY = "PeakIntensity";
    private static final String KEY_AGE = "Age";
    private static final String KEY_MAX_AGE = "MaxAge";
    private static final String KEY_RADIUS = "Radius";
    private static final String KEY_INTEREST_RADIUS = "InterestRadius";
    private static final String KEY_STEERED = "Steered";

    private final int id;
    private final ResourceKey<WeatherType> type;
    private double x;
    private double y;
    private double z;
    private float headingDegrees;
    private float speed;
    private float peakIntensity;
    private float intensity;
    private int ageTicks;
    private int maxAgeTicks;
    private float radius;
    private float interestRadius;
    private boolean steered;
    private boolean dirty;

    /**
     * Creates a storm from raw values. {@code peakIntensity} is the intensity at the plateau of its life; the
     * current intensity is derived from it and the age. New storms start dirty so they are synced at once.
     */
    public StormInstance(int id, ResourceKey<WeatherType> type, double x, double y, double z, float headingDegrees,
                         float speed, float peakIntensity, int ageTicks, int maxAgeTicks, float radius,
                         float interestRadius) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.headingDegrees = WindState.wrapDegrees(headingDegrees);
        this.speed = clamp(speed, 0.0F, MAX_STEER_SPEED);
        this.peakIntensity = clamp(peakIntensity, 0.0F, 1.0F);
        this.ageTicks = Math.max(0, ageTicks);
        this.maxAgeTicks = Math.max(1, maxAgeTicks);
        this.radius = Math.max(0.0F, radius);
        this.interestRadius = Math.max(0.0F, interestRadius);
        this.intensity = this.peakIntensity * lifeEnvelope(this.ageTicks, this.maxAgeTicks);
        this.dirty = true;
    }

    /**
     * Creates a storm of the given type at a position, rolling its peak intensity and lifetime from the type's
     * ranges. The holder must be a registered entry so the type can be saved by id.
     *
     * @throws IllegalArgumentException when the holder has no registry key
     */
    public static StormInstance create(int id, Holder<WeatherType> type, double x, double z, RandomSource random) {
        ResourceKey<WeatherType> key = type.unwrapKey()
            .orElseThrow(() -> new IllegalArgumentException("Weather type holder is not registered: " + type));
        WeatherType weatherType = type.value();
        float peak = weatherType.pickIntensity(random);
        int maxAge = weatherType.pickDurationTicks(random);
        return new StormInstance(id, key, x, DEFAULT_CLOUD_BASE, z, 0.0F, 0.0F, peak, 0, maxAge,
            weatherType.radius(), weatherType.interestRadius());
    }

    /**
     * Advances the storm by one tick: follows the wind unless steered, moves along its heading, ages, and
     * recomputes the intensity from the life envelope. Always marks the storm dirty.
     */
    public void tick(WindState wind) {
        if (!steered) {
            headingDegrees = wind.directionDegrees();
            speed = wind.speed();
        }
        double blocksThisTick = speed / TICKS_PER_SECOND;
        x += WindState.directionX(headingDegrees) * blocksThisTick;
        z += WindState.directionZ(headingDegrees) * blocksThisTick;
        ageTicks++;
        intensity = peakIntensity * lifeEnvelope(ageTicks, maxAgeTicks);
        dirty = true;
    }

    /** Whether the storm has lived out its maximum age and should be removed. */
    public boolean isExpired() {
        return ageTicks >= maxAgeTicks;
    }

    /**
     * Intensity multiplier over a storm's life: linear ramp from 0 to 1 over the first {@link #RAMP_UP_FRACTION},
     * 1 in the middle, linear ramp back to 0 over the last {@link #RAMP_DOWN_FRACTION}.
     */
    public static float lifeEnvelope(int ageTicks, int maxAgeTicks) {
        if (maxAgeTicks <= 0) {
            return 1.0F;
        }
        float life = clamp(ageTicks / (float) maxAgeTicks, 0.0F, 1.0F);
        if (life < RAMP_UP_FRACTION) {
            return life / RAMP_UP_FRACTION;
        }
        if (life > 1.0F - RAMP_DOWN_FRACTION) {
            return (1.0F - life) / RAMP_DOWN_FRACTION;
        }
        return 1.0F;
    }

    /** Writes the storm to a new compound tag. The dirty flag is transient and not written. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_ID, id);
        tag.putString(KEY_TYPE, type.location().toString());
        tag.putDouble(KEY_X, x);
        tag.putDouble(KEY_Y, y);
        tag.putDouble(KEY_Z, z);
        tag.putFloat(KEY_HEADING, headingDegrees);
        tag.putFloat(KEY_SPEED, speed);
        tag.putFloat(KEY_INTENSITY, intensity);
        tag.putFloat(KEY_PEAK_INTENSITY, peakIntensity);
        tag.putInt(KEY_AGE, ageTicks);
        tag.putInt(KEY_MAX_AGE, maxAgeTicks);
        tag.putFloat(KEY_RADIUS, radius);
        tag.putFloat(KEY_INTEREST_RADIUS, interestRadius);
        tag.putBoolean(KEY_STEERED, steered);
        return tag;
    }

    /**
     * Reads a storm written by {@link #save()}. An unparseable type id falls back to the placeholder type;
     * missing radii fall back to the defaults. The loaded storm is not dirty.
     */
    public static StormInstance load(CompoundTag tag) {
        ResourceLocation typeId = ResourceLocation.tryParse(tag.getString(KEY_TYPE));
        ResourceKey<WeatherType> type = typeId != null ? WeatherRegistries.key(typeId) : WeatherTypes.PLACEHOLDER;
        double y = tag.contains(KEY_Y) ? tag.getDouble(KEY_Y) : DEFAULT_CLOUD_BASE;
        float radius = tag.contains(KEY_RADIUS) ? tag.getFloat(KEY_RADIUS) : DEFAULT_RADIUS;
        float interestRadius = tag.contains(KEY_INTEREST_RADIUS) ? tag.getFloat(KEY_INTEREST_RADIUS) : DEFAULT_INTEREST_RADIUS;
        float peak = tag.contains(KEY_PEAK_INTENSITY) ? tag.getFloat(KEY_PEAK_INTENSITY) : tag.getFloat(KEY_INTENSITY);

        StormInstance storm = new StormInstance(tag.getInt(KEY_ID), type, tag.getDouble(KEY_X), y,
            tag.getDouble(KEY_Z), tag.getFloat(KEY_HEADING), tag.getFloat(KEY_SPEED), peak, tag.getInt(KEY_AGE),
            tag.getInt(KEY_MAX_AGE), radius, interestRadius);
        if (tag.contains(KEY_INTENSITY)) {
            storm.intensity = clamp(tag.getFloat(KEY_INTENSITY), 0.0F, 1.0F);
        }
        storm.steered = tag.getBoolean(KEY_STEERED);
        storm.dirty = false;
        return storm;
    }

    /** Builds the immutable snapshot that is sent to clients. */
    public StormSnapshot toSnapshot() {
        return new StormSnapshot(id, type.location(), x, y, z, headingDegrees, speed, intensity, radius,
            interestRadius, ageTicks, maxAgeTicks);
    }

    /** Sets both peak and current intensity, clamped to {@code [0, 1]}. The life envelope still applies on later ticks. */
    public void setIntensity(float value) {
        this.peakIntensity = clamp(value, 0.0F, 1.0F);
        this.intensity = this.peakIntensity;
        this.dirty = true;
    }

    /** Overrides the wind with a fixed heading and speed until {@link #releaseSteering()} is called. */
    public void steer(float headingDegrees, float speed) {
        this.steered = true;
        this.headingDegrees = WindState.wrapDegrees(headingDegrees);
        this.speed = clamp(speed, 0.0F, MAX_STEER_SPEED);
        this.dirty = true;
    }

    /** Lets the storm follow the wind again from the next tick. */
    public void releaseSteering() {
        this.steered = false;
        this.dirty = true;
    }

    /** Moves the storm to an absolute position. */
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dirty = true;
    }

    /** Squared horizontal distance from the storm centre to a point. */
    public double distanceToSqr(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }

    /** Horizontal distance from the storm centre to a point. */
    public double distanceTo(double px, double pz) {
        return Math.sqrt(distanceToSqr(px, pz));
    }

    /** Whether a player at the given horizontal position should receive this storm. */
    public boolean isWithinInterest(double px, double pz) {
        return distanceToSqr(px, pz) <= (double) interestRadius * interestRadius;
    }

    public int id() {
        return id;
    }

    public ResourceKey<WeatherType> type() {
        return type;
    }

    /** The weather type id, as written to NBT and sent to clients. */
    public ResourceLocation typeId() {
        return type.location();
    }

    public double x() {
        return x;
    }

    /** Cloud base height. */
    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** Compass heading the storm moves toward, see {@link WindState#directionDegrees()}. */
    public float headingDegrees() {
        return headingDegrees;
    }

    /** Movement speed in blocks per second. */
    public float speed() {
        return speed;
    }

    /** Current intensity in {@code [0, 1]}, already shaped by the life envelope. */
    public float intensity() {
        return intensity;
    }

    /** Intensity at the plateau of the storm's life, in {@code [0, 1]}. */
    public float peakIntensity() {
        return peakIntensity;
    }

    public int ageTicks() {
        return ageTicks;
    }

    public int maxAgeTicks() {
        return maxAgeTicks;
    }

    /** Ticks left before the storm expires, never negative. */
    public int remainingTicks() {
        return Math.max(0, maxAgeTicks - ageTicks);
    }

    /** Radius of the storm's effects in blocks. */
    public float radius() {
        return radius;
    }

    /** Players farther than this from the centre never receive the storm. */
    public float interestRadius() {
        return interestRadius;
    }

    /** Whether a command has pinned heading and speed. */
    public boolean isSteered() {
        return steered;
    }

    /** Whether the storm changed since it was last sent to clients. */
    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** Called after the storm has been sent to interested players. */
    public void clearDirty() {
        this.dirty = false;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public String toString() {
        return "StormInstance[id=" + id + ", type=" + type.location() + ", x=" + x + ", z=" + z
            + ", intensity=" + intensity + ", age=" + ageTicks + "/" + maxAgeTicks + "]";
    }
}
