package com.weathermod.core;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.joml.Vector3f;

/**
 * The geometry of one vortex at one instant, shared by server forces, the ripper, the client forces and the
 * renderer. The points run from the ground (index 0) to the top; each holds the horizontal offset from the storm
 * centre in x and z and the absolute height in y. They always span the whole height of the funnel, whatever the
 * descent, so the mesh can cut them at the descent and the forces never read a moving ground point.
 *
 * <p>The list is copied on construction, the vectors in it are not: whoever builds a spine hands its vectors over
 * and nobody changes them afterwards.
 *
 * @param points     at least two points from the ground to the top: offset from the storm centre in x and z,
 *                   absolute height in y
 * @param baseRadius the Rankine core radius at the ground as it is now, already scaled by the strength, in blocks
 * @param topRadius  the funnel radius at its top as it is now, in blocks
 * @param strength   the storm's intensity, 0 to 1
 * @param descent    how far the visible funnel has come down from its top: 0 is only a stub under the cloud, 1 is
 *                   on the ground; clamped to 0..1
 * @param grounded   true exactly when the descent has reached 1; only then does the vortex push, rip or burn
 */
public record FunnelSpine(List<Vector3f> points, float baseRadius, float topRadius, float strength,
                          float descent, boolean grounded) {
    /** Points every spine built by the server has. */
    public static final int POINTS = 24;
    /** Decode limit of the list. */
    public static final int MAX_POINTS = 32;
    /** Exponent of the radius profile: the funnel stays narrow and flares near its top. */
    public static final float FLARE_EXPONENT = 2.2F;

    /**
     * Network codec: the point list (at most {@link #MAX_POINTS}), four floats and a flag. Six fields, the most a
     * composite codec takes.
     */
    public static final StreamCodec<ByteBuf, FunnelSpine> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list(MAX_POINTS)), FunnelSpine::points,
        ByteBufCodecs.FLOAT, FunnelSpine::baseRadius,
        ByteBufCodecs.FLOAT, FunnelSpine::topRadius,
        ByteBufCodecs.FLOAT, FunnelSpine::strength,
        ByteBufCodecs.FLOAT, FunnelSpine::descent,
        ByteBufCodecs.BOOL, FunnelSpine::grounded,
        FunnelSpine::new);

    /**
     * Copies the point list and clamps the descent to 0..1 (a descent that is not a number counts as 0).
     *
     * @throws IllegalArgumentException if there are fewer than two points
     */
    public FunnelSpine {
        Objects.requireNonNull(points, "points");
        points = List.copyOf(points);
        if (points.size() < 2) {
            throw new IllegalArgumentException("A funnel spine needs at least 2 points, got " + points.size());
        }
        if (!(descent > 0.0F)) {
            descent = 0.0F;
        } else if (descent > 1.0F) {
            descent = 1.0F;
        }
    }

    /** Height of the ground point, the first of the spine. */
    public float groundY() {
        return points.get(0).y();
    }

    /** Height of the top point, the last of the spine. */
    public float topY() {
        return points.get(points.size() - 1).y();
    }

    /** 0 at or below the ground point, 1 at or above the top point, linear in height between. */
    public float fractionAt(double y) {
        float ground = groundY();
        float top = topY();
        if (y <= ground) {
            return 0.0F;
        }
        if (y >= top) {
            return 1.0F;
        }
        return (float) ((y - ground) / ((double) top - ground));
    }

    /**
     * The spine's horizontal offset from the storm centre at a height, interpolated linearly between the two
     * points the height lies between; below the ground point it is the ground point's offset, above the top point
     * the top point's. Writes x and z of {@code out} and leaves its y alone.
     */
    public void offsetAt(double y, Vector3f out) {
        Vector3f lower = points.get(0);
        if (!(y > lower.y())) {
            out.x = lower.x();
            out.z = lower.z();
            return;
        }
        int count = points.size();
        for (int i = 1; i < count; i++) {
            Vector3f upper = points.get(i);
            if (y <= upper.y()) {
                double span = (double) upper.y() - lower.y();
                float t = span > 0.0D ? (float) ((y - lower.y()) / span) : 1.0F;
                if (t < 0.0F) {
                    t = 0.0F;
                }
                out.x = lower.x() + (upper.x() - lower.x()) * t;
                out.z = lower.z() + (upper.z() - lower.z()) * t;
                return;
            }
            lower = upper;
        }
        out.x = lower.x();
        out.z = lower.z();
    }

    /**
     * The funnel radius at a height: {@code baseRadius + (topRadius - baseRadius) * fractionAt(y)^FLARE_EXPONENT}.
     */
    public float radiusAt(double y) {
        float fraction = fractionAt(y);
        return baseRadius + (topRadius - baseRadius) * (float) Math.pow(fraction, FLARE_EXPONENT);
    }

    /**
     * Component-wise interpolation of two spines with the same point count: points, radii, strength and descent.
     * {@code t} is clamped to 0..1; {@code grounded} and the point count come from {@code to}. Spines with
     * different point counts cannot be blended, so {@code to} is returned for them, as it is for {@code t >= 1}.
     */
    public static FunnelSpine lerp(FunnelSpine from, FunnelSpine to, float t) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        int count = to.points.size();
        if (from.points.size() != count || t >= 1.0F) {
            return to;
        }
        float clamped = t > 0.0F ? t : 0.0F;
        Vector3f[] blended = new Vector3f[count];
        for (int i = 0; i < count; i++) {
            Vector3f a = from.points.get(i);
            Vector3f b = to.points.get(i);
            blended[i] = new Vector3f(mix(a.x(), b.x(), clamped), mix(a.y(), b.y(), clamped),
                mix(a.z(), b.z(), clamped));
        }
        return new FunnelSpine(List.of(blended), mix(from.baseRadius, to.baseRadius, clamped),
            mix(from.topRadius, to.topRadius, clamped), mix(from.strength, to.strength, clamped),
            mix(from.descent, to.descent, clamped), to.grounded);
    }

    private static float mix(float start, float end, float t) {
        return start + (end - start) * t;
    }
}
