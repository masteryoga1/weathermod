package com.weathermod.server;

import com.weathermod.core.Headings;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import com.weathermod.network.StormSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * One storm in a dimension, simulated on the server. It moves with the wind, turned by a heading bias the director
 * sets, unless a command steers it; it ages toward its maximum age and ramps its intensity in over the first tenth
 * of its life and out over the last fifth. The director may cut that life short with {@link #endSoon()}.
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
    /** Shortest life {@link #endSoon()} leaves a storm, in ticks, so a very young storm still fades visibly. */
    public static final int MIN_FADE_TICKS = 100;

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
    private static final String KEY_NATURAL = "Natural";
    private static final String KEY_TICKS_UNSEEN = "TicksUnseen";
    private static final String KEY_HEADING_BIAS = "HeadingBias";

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
    private boolean natural;
    private int ticksUnseen;
    private float headingBiasDegrees;
    private boolean dirty;

    /**
     * Creates a storm from raw values. {@code peakIntensity} is the intensity at the plateau of its life; the
     * current intensity is derived from it and the age. New storms start dirty so they are synced at once, are not
     * natural, have no heading bias and have not been unseen.
     */
    public StormInstance(int id, ResourceKey<WeatherType> type, double x, double y, double z, float headingDegrees,
                         float speed, float peakIntensity, int ageTicks, int maxAgeTicks, float radius,
                         float interestRadius) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.headingDegrees = Headings.wrapDegrees(headingDegrees);
        this.speed = Mth.clamp(speed, 0.0F, MAX_STEER_SPEED);
        this.peakIntensity = Mth.clamp(peakIntensity, 0.0F, 1.0F);
        this.ageTicks = Math.max(0, ageTicks);
        this.maxAgeTicks = Math.max(1, maxAgeTicks);
        this.radius = Math.max(0.0F, radius);
        this.interestRadius = Math.max(0.0F, interestRadius);
        this.intensity = this.peakIntensity * lifeEnvelope(this.ageTicks, this.maxAgeTicks);
        this.natural = false;
        this.ticksUnseen = 0;
        this.headingBiasDegrees = 0.0F;
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
     * Creates a storm exactly like {@link #create} and marks it as spawned by the director, so it counts toward
     * the natural storm cap.
     */
    public static StormInstance createNatural(int id, Holder<WeatherType> type, double x, double z,
                                              RandomSource random) {
        StormInstance storm = create(id, type, x, z, random);
        storm.markNatural();
        return storm;
    }

    /**
     * Advances the storm by one tick: unless steered, takes the wind direction turned by the heading bias as its
     * heading and the wind speed times {@code driftFactor} (clamped to 0..1) as its speed; then moves along its
     * heading, ages, and recomputes the intensity from the life envelope. Always marks the storm dirty. Storms
     * travel slower than the air so a storm of a hundred blocks lingers over a player for minutes, not seconds.
     */
    public void tick(WindState wind, float driftFactor) {
        if (!steered) {
            headingDegrees = Headings.wrapDegrees(wind.directionDegrees() + headingBiasDegrees);
            speed = wind.speed() * Mth.clamp(driftFactor, 0.0F, 1.0F);
        }
        double blocksThisTick = speed / SharedConstants.TICKS_PER_SECOND;
        x += Headings.directionX(headingDegrees) * blocksThisTick;
        z += Headings.directionZ(headingDegrees) * blocksThisTick;
        ageTicks++;
        intensity = peakIntensity * lifeEnvelope(ageTicks, maxAgeTicks);
        dirty = true;
    }

    /**
     * Skips the ramp-up so the storm is at its peak intensity at once, for storms an operator spawns and wants to
     * see immediately; natural storms keep the ramp. A storm already past its ramp-up is left alone.
     */
    public void startAtPeak() {
        int plateauStart = (int) Math.ceil(RAMP_UP_FRACTION * maxAgeTicks);
        if (ageTicks < plateauStart) {
            ageTicks = plateauStart;
            intensity = peakIntensity * lifeEnvelope(ageTicks, maxAgeTicks);
            dirty = true;
        }
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
        float life = Mth.clamp(ageTicks / (float) maxAgeTicks, 0.0F, 1.0F);
        if (life < RAMP_UP_FRACTION) {
            return life / RAMP_UP_FRACTION;
        }
        if (life > 1.0F - RAMP_DOWN_FRACTION) {
            return (1.0F - life) / RAMP_DOWN_FRACTION;
        }
        return 1.0F;
    }

    /** The length of this storm's ramp-down in ticks: {@link #RAMP_DOWN_FRACTION} of its maximum age, rounded. */
    public int rampDownTicks() {
        return Math.round(RAMP_DOWN_FRACTION * maxAgeTicks);
    }

    /**
     * Cuts the storm's life short so that it enters its ramp-down now: the maximum age is pulled in to the age at
     * which the current age sits exactly at the start of the ramp-down, {@code ageTicks / (1 - RAMP_DOWN_FRACTION)},
     * but never closer than {@link #MIN_FADE_TICKS} ahead, so the intensity starts falling on the next tick. The
     * current intensity stays continuous: the peak intensity is recomputed so the life envelope at the new maximum
     * age reproduces it. A storm whose remaining life is already no longer than that is left alone, so repeated
     * calls are idempotent and never lengthen a storm.
     */
    public void endSoon() {
        int newMaxAge = Math.max(ageTicks + MIN_FADE_TICKS, Math.round(ageTicks / (1.0F - RAMP_DOWN_FRACTION)));
        if (remainingTicks() <= newMaxAge - ageTicks) {
            return;
        }
        float envelope = lifeEnvelope(ageTicks, newMaxAge);
        if (envelope > 0.0F) {
            peakIntensity = Mth.clamp(intensity / envelope, 0.0F, 1.0F);
        }
        maxAgeTicks = newMaxAge;
        dirty = true;
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
        tag.putBoolean(KEY_NATURAL, natural);
        tag.putInt(KEY_TICKS_UNSEEN, ticksUnseen);
        tag.putFloat(KEY_HEADING_BIAS, headingBiasDegrees);
        return tag;
    }

    /**
     * Reads a storm written by {@link #save()}. An unparseable type id falls back to the placeholder type; missing
     * radii fall back to the weather type defaults; the natural flag, the unseen ticks and the heading bias, which
     * older saves lack, default to not natural, zero and zero. The loaded storm is not dirty.
     */
    public static StormInstance load(CompoundTag tag) {
        ResourceLocation typeId = ResourceLocation.tryParse(tag.getString(KEY_TYPE));
        ResourceKey<WeatherType> type = typeId != null ? WeatherRegistries.key(typeId) : WeatherTypes.PLACEHOLDER;
        double y = tag.contains(KEY_Y) ? tag.getDouble(KEY_Y) : DEFAULT_CLOUD_BASE;
        float radius = tag.contains(KEY_RADIUS) ? tag.getFloat(KEY_RADIUS) : WeatherType.DEFAULT_RADIUS;
        float interestRadius = tag.contains(KEY_INTEREST_RADIUS)
            ? tag.getFloat(KEY_INTEREST_RADIUS)
            : WeatherType.DEFAULT_INTEREST_RADIUS;
        float peak = tag.contains(KEY_PEAK_INTENSITY) ? tag.getFloat(KEY_PEAK_INTENSITY) : tag.getFloat(KEY_INTENSITY);

        StormInstance storm = new StormInstance(tag.getInt(KEY_ID), type, tag.getDouble(KEY_X), y,
            tag.getDouble(KEY_Z), tag.getFloat(KEY_HEADING), tag.getFloat(KEY_SPEED), peak, tag.getInt(KEY_AGE),
            tag.getInt(KEY_MAX_AGE), radius, interestRadius);
        if (tag.contains(KEY_INTENSITY)) {
            storm.intensity = Mth.clamp(tag.getFloat(KEY_INTENSITY), 0.0F, 1.0F);
        }
        storm.steered = tag.getBoolean(KEY_STEERED);
        storm.natural = tag.getBoolean(KEY_NATURAL);
        if (tag.contains(KEY_TICKS_UNSEEN, Tag.TAG_ANY_NUMERIC)) {
            storm.ticksUnseen = Math.max(0, tag.getInt(KEY_TICKS_UNSEEN));
        }
        if (tag.contains(KEY_HEADING_BIAS, Tag.TAG_ANY_NUMERIC)) {
            storm.headingBiasDegrees = wrapBias(tag.getFloat(KEY_HEADING_BIAS));
        }
        storm.dirty = false;
        return storm;
    }

    /** Builds the immutable snapshot that is sent to clients. */
    public StormSnapshot toSnapshot() {
        return new StormSnapshot(id, type.location(), x, y, z, headingDegrees, speed, intensity, radius,
            interestRadius, ageTicks, maxAgeTicks);
    }

    /**
     * Sets the peak intensity, clamped to {@code [0, 1]}, and re-derives the current intensity from it through the
     * life envelope exactly as the constructor and {@link #tick} do, so the next sync and the next tick agree.
     */
    public void setPeakIntensity(float value) {
        this.peakIntensity = Mth.clamp(value, 0.0F, 1.0F);
        this.intensity = this.peakIntensity * lifeEnvelope(this.ageTicks, this.maxAgeTicks);
        this.dirty = true;
    }

    /** Overrides the wind with a fixed heading and speed until {@link #releaseSteering()} is called. */
    public void steer(float headingDegrees, float speed) {
        this.steered = true;
        this.headingDegrees = Headings.wrapDegrees(headingDegrees);
        this.speed = Mth.clamp(speed, 0.0F, MAX_STEER_SPEED);
        this.dirty = true;
    }

    /** Lets the storm follow the wind again from the next tick. */
    public void releaseSteering() {
        this.steered = false;
        this.dirty = true;
    }

    /** Marks the storm as spawned by the director. The flag is persisted and never cleared. */
    public void markNatural() {
        this.natural = true;
    }

    /**
     * Records how long no player has been near the storm, as counted by the director. Negative values are clamped
     * to zero. Not synced to clients.
     */
    public void setTicksUnseen(int ticks) {
        this.ticksUnseen = Math.max(0, ticks);
    }

    /**
     * Sets the turn the storm's heading gets away from the wind direction while it is not steered, wrapped to
     * {@code [-180, 180)}. Takes effect on the next tick; a change marks the storm dirty.
     */
    public void setHeadingBias(float degrees) {
        float wrapped = wrapBias(degrees);
        if (wrapped != this.headingBiasDegrees) {
            this.headingBiasDegrees = wrapped;
            this.dirty = true;
        }
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

    /** Whether the director spawned this storm; such storms count toward the natural storm cap. */
    public boolean isNatural() {
        return natural;
    }

    /** Ticks the director has counted without a player near the storm; zero while someone is near. */
    public int ticksUnseen() {
        return ticksUnseen;
    }

    /** The turn away from the wind direction the storm's heading gets while not steered, in {@code [-180, 180)}. */
    public float headingBiasDegrees() {
        return headingBiasDegrees;
    }

    /** Whether the storm changed since it was last sent to clients. */
    public boolean isDirty() {
        return dirty;
    }

    /** Called after the storm has been sent to interested players. */
    public void clearDirty() {
        this.dirty = false;
    }

    /** Wraps a heading bias into {@code [-180, 180)}; a non-finite value becomes zero. */
    private static float wrapBias(float degrees) {
        if (!Float.isFinite(degrees)) {
            return 0.0F;
        }
        float wrapped = Mth.wrapDegrees(degrees);
        return wrapped >= 180.0F ? -180.0F : wrapped;
    }

    @Override
    public String toString() {
        return "StormInstance[id=" + id + ", type=" + type.location() + ", x=" + x + ", z=" + z
            + ", intensity=" + intensity + ", age=" + ageTicks + "/" + maxAgeTicks
            + (natural ? ", natural" : "") + (steered ? ", steered" : "") + "]";
    }
}
