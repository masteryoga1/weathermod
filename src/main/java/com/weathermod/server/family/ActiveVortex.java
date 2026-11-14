package com.weathermod.server.family;

import com.weathermod.core.FunnelSpine;
import com.weathermod.core.VortexParams;
import java.util.Objects;

/**
 * A vortex as published in {@code StormRuntime} every tick: what forces, the ripper and the network need.
 * {@code groundKnown} is false while the ground under the base could not be sampled (its chunk is not loaded);
 * {@code digRefY} is the terrain height the ripper's dig limit is measured from, NaN when never sampled.
 *
 * @param stormId     id of the storm this vortex belongs to
 * @param params      the vortex block of the storm's weather type
 * @param x           the storm centre along {@code X}, which the spine's offsets are relative to
 * @param z           the storm centre along {@code Z}
 * @param spine       the funnel's geometry this tick
 * @param groundKnown whether the most recent ground sample under the base succeeded
 * @param digRefY     the untouched terrain height the dig limit is measured from, or NaN
 */
public record ActiveVortex(int stormId, VortexParams params, double x, double z, FunnelSpine spine,
                           boolean groundKnown, double digRefY) {
    public ActiveVortex {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(spine, "spine");
    }

    /** Where the funnel meets the ground along {@code X}: the storm centre plus the ground point's offset. */
    public double baseX() {
        return x + spine.points().get(0).x();
    }

    /** Where the funnel meets the ground along {@code Z}: the storm centre plus the ground point's offset. */
    public double baseZ() {
        return z + spine.points().get(0).z();
    }

    /** Whether the funnel has reached the ground, see {@link FunnelSpine#grounded()}. */
    public boolean isGrounded() {
        return spine.grounded();
    }

    /**
     * {@code isGrounded() && groundKnown}: the only state in which the server rips, burns or pushes with this
     * vortex.
     */
    public boolean actsOnWorld() {
        return isGrounded() && groundKnown;
    }
}
