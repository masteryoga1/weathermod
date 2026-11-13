package com.weathermod.server.family;

import com.weathermod.core.FunnelSpine;
import com.weathermod.core.Headings;
import com.weathermod.core.VortexField;
import com.weathermod.core.VortexParams;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/**
 * Builds the spine of a funnel: the line of points from the ground to the top that the forces, the ripper and the
 * renderer all read. The base wanders in a slow circle, the top leans ahead in the direction of travel and the
 * middle sways, so a funnel is never a straight, still tube. Pure and deterministic.
 */
public final class FunnelSpineMath {
    /** The base of a rated tornado wanders by {@code max(this, WOBBLE_CORE_FRACTION * base radius)} blocks. */
    public static final float WOBBLE_MIN_RATED_BLOCKS = 2.0F;
    /** The base of every other vortex wanders by {@code max(this, WOBBLE_CORE_FRACTION * base radius)} blocks. */
    public static final float WOBBLE_MIN_SMALL_BLOCKS = 0.5F;
    /** Share of the base radius the base wanders by when that is more than the minimum. */
    public static final float WOBBLE_CORE_FRACTION = 0.5F;
    /** The top leads the base by the distance travelled in this many seconds. */
    public static final float LEAN_SECONDS = 3.0F;
    /** The top never leads the base by more than this fraction of the top radius. */
    public static final float LEAN_MAX_FRACTION = 0.3F;
    /** The middle of the funnel sways by this fraction of the top radius. */
    public static final float SWAY_FRACTION = 0.15F;
    /** Period of the sway along {@code X}; with {@link #SWAY_PERIOD_B_TICKS} it never repeats visibly. */
    public static final int SWAY_PERIOD_A_TICKS = 137;
    /** Period of the sway along {@code Z}. */
    public static final int SWAY_PERIOD_B_TICKS = 211;

    /** The lean grows with the height to this power: little near the ground, most at the top. */
    private static final double LEAN_EXPONENT = 1.5;
    private static final double TWO_PI = Math.PI * 2.0;

    private FunnelSpineMath() {
    }

    /**
     * Builds the {@link FunnelSpine#POINTS}-point spine of a vortex for one tick. Deterministic in its arguments.
     *
     * <p>The radii are the type's core and top radius times {@link VortexField#scale(float)}. Point {@code i} lies
     * at the fraction {@code t = i / (POINTS - 1)} of the height between {@code groundY} and {@code topY}; its
     * horizontal offset from the storm centre is {@code wobble * (1 - t)^2 + lean * t^1.5 + sway(t)}: the wobble is
     * the base's wander along the angle {@code wobblePhase}, the lean points along the heading and is
     * {@code min(LEAN_MAX_FRACTION * topRadius, speed * LEAN_SECONDS)} long, and the sway is
     * {@code SWAY_FRACTION * topRadius * sin(pi * t)} times
     * {@code (sin(2 pi tick / SWAY_PERIOD_A_TICKS + seed), cos(2 pi tick / SWAY_PERIOD_B_TICKS + seed))}. The
     * points always run from the ground to the top: the descent is carried along for the mesh to draw and moves
     * no point.
     *
     * @param strength             the storm's intensity, 0..1
     * @param descent              how far the funnel has come down, see {@link VortexMath#descent}
     * @param groundY              height of the ground point
     * @param topY                 height of the top point
     * @param headingDegrees       compass heading the storm moves toward
     * @param speedBlocksPerSecond the storm's ground speed
     * @param wobblePhase          angle of the base's wander, in radians
     * @param tick                 the dimension's simulation tick
     * @param seed                 a per-storm value that shifts the sway, normally the storm id
     */
    public static FunnelSpine build(VortexParams params, float strength, float descent, double groundY,
                                    double topY, float headingDegrees, float speedBlocksPerSecond,
                                    float wobblePhase, int tick, int seed) {
        float scale = VortexField.scale(strength);
        float baseRadius = params.coreRadius() * scale;
        float topRadius = params.topRadius() * scale;

        double wobble = Math.max(
            params.kind().isRated() ? WOBBLE_MIN_RATED_BLOCKS : WOBBLE_MIN_SMALL_BLOCKS,
            WOBBLE_CORE_FRACTION * baseRadius);
        double wobbleX = wobble * Math.cos(wobblePhase);
        double wobbleZ = wobble * Math.sin(wobblePhase);

        double lean = Math.min(LEAN_MAX_FRACTION * topRadius, Math.max(0.0F, speedBlocksPerSecond) * LEAN_SECONDS);
        double leanX = Headings.directionX(headingDegrees) * lean;
        double leanZ = Headings.directionZ(headingDegrees) * lean;

        double sway = SWAY_FRACTION * topRadius;
        double swayX = sway * Math.sin(TWO_PI * tick / SWAY_PERIOD_A_TICKS + seed);
        double swayZ = sway * Math.cos(TWO_PI * tick / SWAY_PERIOD_B_TICKS + seed);

        int last = FunnelSpine.POINTS - 1;
        List<Vector3f> points = new ArrayList<>(FunnelSpine.POINTS);
        for (int i = 0; i <= last; i++) {
            float t = i / (float) last;
            double baseShare = (1.0 - t) * (1.0 - t);
            double leanShare = Math.pow(t, LEAN_EXPONENT);
            double swayShare = Math.sin(Math.PI * t);
            double y = groundY + (topY - groundY) * t;
            points.add(new Vector3f(
                (float) (wobbleX * baseShare + leanX * leanShare + swayX * swayShare),
                (float) y,
                (float) (wobbleZ * baseShare + leanZ * leanShare + swayZ * swayShare)));
        }
        return new FunnelSpine(points, baseRadius, topRadius, strength, descent, VortexMath.grounded(descent));
    }
}
