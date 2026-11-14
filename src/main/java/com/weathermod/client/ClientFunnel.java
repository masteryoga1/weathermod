package com.weathermod.client;

import com.weathermod.core.FunnelSpine;
import java.util.Objects;

/**
 * Client mirror of one funnel: interpolates from the spine it showed to the last keyframe over the sync interval.
 * The server sends a keyframe with every storm delta; between keyframes the mirror blends linearly, one step per
 * client tick, and holds the keyframe once it has reached it. The spine's offsets are relative to the storm
 * centre, so the storm's own dead reckoning carries the funnel along between keyframes.
 */
public final class ClientFunnel {
    private final int stormId;
    private FunnelSpine origin;
    private FunnelSpine keyframe;
    private int intervalTicks = 1;
    private int ticksSinceKeyframe;
    private FunnelSpine blended;
    private float blendedPartialTick;

    /** Creates the mirror from the first keyframe of a storm's funnel; it shows that spine until the next one. */
    public ClientFunnel(int stormId, FunnelSpine first) {
        this.stormId = stormId;
        this.origin = Objects.requireNonNull(first, "first");
        this.keyframe = first;
    }

    /** Id of the storm the funnel belongs to. */
    public int stormId() {
        return stormId;
    }

    /**
     * A new keyframe: the spine currently shown (the one {@code spine(0)} returns, the state at the start of the
     * running tick) becomes the origin, progress restarts, the interval is at least 1. Because the blend starts
     * where the funnel is, a keyframe never makes it jump.
     */
    public void accept(FunnelSpine keyframe, int intervalTicks) {
        Objects.requireNonNull(keyframe, "keyframe");
        this.origin = spine(0.0F);
        this.keyframe = keyframe;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.ticksSinceKeyframe = 0;
        this.blended = null;
    }

    /** One client tick of progress toward the keyframe. */
    public void tick() {
        if (ticksSinceKeyframe < Integer.MAX_VALUE) {
            ticksSinceKeyframe++;
        }
        blended = null;
    }

    /**
     * The spine to draw or to compute forces with:
     * {@code FunnelSpine.lerp(origin, keyframe, (progress + partialTick) / interval)}, where progress is
     * {@link #ticksSinceKeyframe()}; the keyframe itself once the interval has passed. Tick code asks with a
     * partial tick of 1, renderers with the frame's. The result for one partial tick is reused until the next
     * tick or keyframe, so many callers in one tick share one spine.
     */
    public FunnelSpine spine(float partialTick) {
        if (origin == keyframe || ticksSinceKeyframe >= intervalTicks) {
            return keyframe;
        }
        if (blended != null && blendedPartialTick == partialTick) {
            return blended;
        }
        blended = FunnelSpine.lerp(origin, keyframe, (ticksSinceKeyframe + partialTick) / intervalTicks);
        blendedPartialTick = partialTick;
        return blended;
    }

    /** The last keyframe the server sent. */
    public FunnelSpine keyframe() {
        return keyframe;
    }

    /** Client ticks since the last keyframe arrived; it keeps counting after the blend has reached the keyframe. */
    public int ticksSinceKeyframe() {
        return ticksSinceKeyframe;
    }

    @Override
    public String toString() {
        return "ClientFunnel[storm=" + stormId + ", ticksSinceKeyframe=" + ticksSinceKeyframe + ", interval="
            + intervalTicks + "]";
    }
}
