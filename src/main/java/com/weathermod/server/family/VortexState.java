package com.weathermod.server.family;

import com.weathermod.server.FamilyState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * What the vortex family remembers about one storm: the ground it stands on, the terrain height its dig limit is
 * measured from, the values rolled once for its funnel, its counters and, for a natural tornado, when it may touch
 * down and how far it grows. Persisted inside the storm under {@code "Family"}; every key is optional on load, so
 * an empty tag gives the state of a vortex that was just spawned by command.
 *
 * <p>The ground height is written only once it was really sampled, and the dig reference only once it is known.
 * Three fields are transient, never saved and unset after a load: {@link #groundKnown}, {@link #groundProvisional}
 * and the height the tracked ground is moving toward.
 */
public final class VortexState implements FamilyState {
    /** Shortest time the base of a funnel takes to wander once around, in ticks. */
    public static final int MIN_WOBBLE_PERIOD_TICKS = 200;
    /** Longest time the base of a funnel takes to wander once around, in ticks. */
    public static final int MAX_WOBBLE_PERIOD_TICKS = 400;
    /** The wander period of a state that was never seeded, in ticks. */
    public static final int DEFAULT_WOBBLE_PERIOD_TICKS = 300;
    /** The height fraction of a state that was never seeded: the middle of the type's height range. */
    public static final float DEFAULT_HEIGHT_FRACTION = 0.5F;
    /** The target tier of a vortex that stays what it is. */
    public static final int NO_TARGET_TIER = -1;

    private static final int HIGHEST_TIER = 5;

    private static final String KEY_GROUND_Y = "GroundY";
    private static final String KEY_DIG_REF_Y = "DigRefY";
    private static final String KEY_SEEDED = "Seeded";
    private static final String KEY_WOBBLE_PHASE = "WobblePhase";
    private static final String KEY_WOBBLE_PERIOD = "WobblePeriod";
    private static final String KEY_HEIGHT_FRACTION = "HeightFraction";
    private static final String KEY_LAUNCH_COOLDOWN = "LaunchCooldown";
    private static final String KEY_OFF_SURFACE = "OffSurface";
    private static final String KEY_ORPHANED = "Orphaned";
    private static final String KEY_TOUCHDOWN_DELAY = "TouchdownDelay";
    private static final String KEY_TARGET_TIER = "TargetTier";
    private static final String KEY_TIER_STEP = "TierStep";

    /**
     * Smoothed ground height under the base: the y of the first free block above the ground. NaN until the ground
     * was sampled or guessed; a guess is marked by {@link #groundProvisional} and is not saved.
     */
    public double groundY = Double.NaN;
    /** Untouched terrain height the ripper's dig limit is measured from; NaN while unknown. */
    public double digRefY = Double.NaN;
    /** Whether the wander period, the wander phase and the height fraction were rolled. */
    public boolean seeded;
    /** Angle of the base's wander around the storm centre, in radians. */
    public float wobblePhase;
    /**
     * Ticks the base takes to wander once around, {@link #MIN_WOBBLE_PERIOD_TICKS} to
     * {@link #MAX_WOBBLE_PERIOD_TICKS}; rolled once.
     */
    public int wobblePeriodTicks = DEFAULT_WOBBLE_PERIOD_TICKS;
    /** Where in the type's height range this funnel lies, 0..1; rolled once. */
    public float heightFraction = DEFAULT_HEIGHT_FRACTION;
    /** Ticks until the next flying block may launch; set by the block ripper, counted down by the vortex system. */
    public int launchCooldown;
    /** Ticks a waterspout has spent over land. */
    public int offSurfaceTicks;
    /** Set once when a child that follows its parent lost it; such a child fades out and is not ended twice. */
    public boolean orphaned;
    /** The funnel reaches the ground no earlier than this age of the storm, in ticks; 0 lets it down at once. */
    public int touchdownDelayTicks;
    /** The EF tier a natural tornado grows to; {@link #NO_TARGET_TIER} when the vortex stays what it is. */
    public int targetTier = NO_TARGET_TIER;
    /** Ticks on the ground since the tornado last grew a tier. */
    public int tierStepTicks;

    /** Transient: whether the most recent ground sample succeeded. False after a load until the next sample. */
    public boolean groundKnown;
    /**
     * Transient: whether {@link #groundY} holds a guess (the height of the nearest player or the sea level)
     * because the ground under the base could not be sampled yet. The first real sample replaces a guess at once.
     */
    public boolean groundProvisional;
    /** Transient: the last sampled ground height, which {@link #groundY} moves toward; NaN when there is none. */
    double groundTargetY = Double.NaN;

    /**
     * A fresh state, as a vortex spawned by command starts with: nothing sampled, nothing rolled, no touchdown
     * delay and no target tier.
     */
    public VortexState() {
    }

    /**
     * The state a natural tornado child is born with: it grows to {@code targetTier} (clamped to 0..5) and hangs
     * aloft for {@code touchdownDelayTicks} (never negative) before it may touch down.
     */
    public static VortexState forNaturalTornado(int targetTier, int touchdownDelayTicks) {
        VortexState state = new VortexState();
        state.targetTier = Mth.clamp(targetTier, 0, HIGHEST_TIER);
        state.touchdownDelayTicks = Math.max(0, touchdownDelayTicks);
        return state;
    }

    /**
     * Writes the state to a new compound tag. The ground height is written only when it was sampled (not while it
     * is NaN or a guess) and the dig reference only when it is known; the transient fields are never written.
     */
    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        double ground = persistentGroundY();
        if (!Double.isNaN(ground)) {
            tag.putDouble(KEY_GROUND_Y, ground);
        }
        if (!Double.isNaN(digRefY)) {
            tag.putDouble(KEY_DIG_REF_Y, digRefY);
        }
        tag.putBoolean(KEY_SEEDED, seeded);
        tag.putFloat(KEY_WOBBLE_PHASE, wobblePhase);
        tag.putInt(KEY_WOBBLE_PERIOD, wobblePeriodTicks);
        tag.putFloat(KEY_HEIGHT_FRACTION, heightFraction);
        tag.putInt(KEY_LAUNCH_COOLDOWN, launchCooldown);
        tag.putInt(KEY_OFF_SURFACE, offSurfaceTicks);
        tag.putBoolean(KEY_ORPHANED, orphaned);
        tag.putInt(KEY_TOUCHDOWN_DELAY, touchdownDelayTicks);
        tag.putInt(KEY_TARGET_TIER, targetTier);
        tag.putInt(KEY_TIER_STEP, tierStepTicks);
        return tag;
    }

    /**
     * Reads a state written by {@link #save()}. Missing keys fall back to the fresh-state defaults, so an empty
     * tag gives a fresh state; values outside their ranges are brought back into them (the wander period into
     * {@link #MIN_WOBBLE_PERIOD_TICKS}..{@link #MAX_WOBBLE_PERIOD_TICKS}, the height fraction into 0..1, the target
     * tier into -1..5, counters to zero or more) and heights that are not finite numbers count as absent. The
     * transient fields start unset.
     */
    public static VortexState load(CompoundTag tag) {
        VortexState state = new VortexState();
        state.groundY = finiteOrNaN(tag, KEY_GROUND_Y);
        state.digRefY = finiteOrNaN(tag, KEY_DIG_REF_Y);
        state.seeded = tag.getBoolean(KEY_SEEDED);
        if (tag.contains(KEY_WOBBLE_PHASE, Tag.TAG_ANY_NUMERIC)) {
            float phase = tag.getFloat(KEY_WOBBLE_PHASE);
            state.wobblePhase = Float.isFinite(phase) ? phase : 0.0F;
        }
        if (tag.contains(KEY_WOBBLE_PERIOD, Tag.TAG_ANY_NUMERIC)) {
            state.wobblePeriodTicks = Mth.clamp(tag.getInt(KEY_WOBBLE_PERIOD), MIN_WOBBLE_PERIOD_TICKS,
                MAX_WOBBLE_PERIOD_TICKS);
        }
        if (tag.contains(KEY_HEIGHT_FRACTION, Tag.TAG_ANY_NUMERIC)) {
            float fraction = tag.getFloat(KEY_HEIGHT_FRACTION);
            state.heightFraction = Float.isNaN(fraction) ? DEFAULT_HEIGHT_FRACTION : Mth.clamp(fraction, 0.0F, 1.0F);
        }
        state.launchCooldown = Math.max(0, tag.getInt(KEY_LAUNCH_COOLDOWN));
        state.offSurfaceTicks = Math.max(0, tag.getInt(KEY_OFF_SURFACE));
        state.orphaned = tag.getBoolean(KEY_ORPHANED);
        state.touchdownDelayTicks = Math.max(0, tag.getInt(KEY_TOUCHDOWN_DELAY));
        if (tag.contains(KEY_TARGET_TIER, Tag.TAG_ANY_NUMERIC)) {
            state.targetTier = Mth.clamp(tag.getInt(KEY_TARGET_TIER), NO_TARGET_TIER, HIGHEST_TIER);
        }
        state.tierStepTicks = Math.max(0, tag.getInt(KEY_TIER_STEP));
        return state;
    }

    private static double finiteOrNaN(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return Double.NaN;
        }
        double value = tag.getDouble(key);
        return Double.isFinite(value) ? value : Double.NaN;
    }

    /** The ground height as it is saved: NaN while it is unset or only a guess. */
    private double persistentGroundY() {
        return groundProvisional ? Double.NaN : groundY;
    }

    /**
     * Two states are equal when they would be saved alike: the transient fields are not compared, and a guessed
     * ground height counts as none.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VortexState that)) {
            return false;
        }
        return Double.compare(persistentGroundY(), that.persistentGroundY()) == 0
            && Double.compare(digRefY, that.digRefY) == 0
            && seeded == that.seeded
            && Float.compare(wobblePhase, that.wobblePhase) == 0
            && wobblePeriodTicks == that.wobblePeriodTicks
            && Float.compare(heightFraction, that.heightFraction) == 0
            && launchCooldown == that.launchCooldown
            && offSurfaceTicks == that.offSurfaceTicks
            && orphaned == that.orphaned
            && touchdownDelayTicks == that.touchdownDelayTicks
            && targetTier == that.targetTier
            && tierStepTicks == that.tierStepTicks;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(persistentGroundY());
        result = 31 * result + Double.hashCode(digRefY);
        result = 31 * result + Boolean.hashCode(seeded);
        result = 31 * result + Float.hashCode(wobblePhase);
        result = 31 * result + Integer.hashCode(wobblePeriodTicks);
        result = 31 * result + Float.hashCode(heightFraction);
        result = 31 * result + Integer.hashCode(launchCooldown);
        result = 31 * result + Integer.hashCode(offSurfaceTicks);
        result = 31 * result + Boolean.hashCode(orphaned);
        result = 31 * result + Integer.hashCode(touchdownDelayTicks);
        result = 31 * result + Integer.hashCode(targetTier);
        result = 31 * result + Integer.hashCode(tierStepTicks);
        return result;
    }

    @Override
    public String toString() {
        return "VortexState[groundY=" + groundY + (groundProvisional ? " (provisional)" : "")
            + ", digRefY=" + digRefY + ", seeded=" + seeded + ", wobblePhase=" + wobblePhase
            + ", wobblePeriodTicks=" + wobblePeriodTicks + ", heightFraction=" + heightFraction
            + ", launchCooldown=" + launchCooldown + ", offSurfaceTicks=" + offSurfaceTicks
            + ", orphaned=" + orphaned + ", touchdownDelayTicks=" + touchdownDelayTicks
            + ", targetTier=" + targetTier + ", tierStepTicks=" + tierStepTicks
            + ", groundKnown=" + groundKnown + "]";
    }
}
