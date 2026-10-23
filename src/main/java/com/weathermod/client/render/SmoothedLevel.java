package com.weathermod.client.render;

import net.minecraft.util.Mth;

/**
 * A value that moves toward a target by at most a fixed step per tick and remembers the previous tick's value, so a
 * frame can interpolate between the two exactly like vanilla's {@code oRainLevel} and {@code rainLevel} pair. Pure
 * arithmetic: no level or renderer is involved, which is what lets the drivers built on it be unit-tested.
 */
public final class SmoothedLevel {
    private final float maxStep;
    private float previous;
    private float current;

    /**
     * Creates a level at zero that moves by at most {@code maxStep} per {@link #approach} call.
     *
     * @param maxStep the largest change per tick, finite and positive
     */
    public SmoothedLevel(float maxStep) {
        if (!(maxStep > 0.0F) || Float.isInfinite(maxStep)) {
            throw new IllegalArgumentException("maxStep must be finite and positive, was " + maxStep);
        }
        this.maxStep = maxStep;
    }

    /** The largest change one {@link #approach} call makes. */
    public float maxStep() {
        return maxStep;
    }

    /**
     * Advances one tick toward the target: the previous value becomes the current one and the current value moves
     * by {@code clamp(target - current, -maxStep, maxStep)} through {@link Mth#approach}, landing exactly on the
     * target once it lies within one step so a long ramp ends on the target and not a rounding error beside it.
     *
     * @param target the value to move toward; must not be NaN
     */
    public void approach(float target) {
        if (Float.isNaN(target)) {
            throw new IllegalArgumentException("target must not be NaN");
        }
        previous = current;
        current = Mth.approach(current, target, maxStep);
    }

    /**
     * Sets both the previous and the current value, so the next frames show the value without movement.
     *
     * @param value the new value; must not be NaN
     */
    public void snap(float value) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException("value must not be NaN");
        }
        previous = value;
        current = value;
    }

    /** Snaps to zero. */
    public void reset() {
        snap(0.0F);
    }

    /** The value after the last tick. */
    public float current() {
        return current;
    }

    /** The value before the last tick, which the frames of the current tick interpolate from. */
    public float previous() {
        return previous;
    }

    /**
     * The value at a partial tick: {@code lerp(partialTick, previous, current)}, the same interpolation vanilla's
     * {@code Level.getRainLevel} applies to its own pair of fields.
     */
    public float get(float partialTick) {
        return Mth.lerp(partialTick, previous, current);
    }

    @Override
    public String toString() {
        return "SmoothedLevel[previous=" + previous + ", current=" + current + ", maxStep=" + maxStep + "]";
    }
}
