package com.weathermod.client;

import com.weathermod.network.DownburstPayload;
import java.util.Objects;

/**
 * A downburst the client was told about; ages every client tick. The client applies the wind force functions to
 * the local player with this copy and, later, draws the dust ring from it.
 */
public final class ClientDownburst {
    private final double x;
    private final double z;
    private final float radius;
    private final float speed;
    private final int durationTicks;
    private int ageTicks;

    /** Starts mirroring the downburst a payload announced; its age starts at zero. */
    public ClientDownburst(DownburstPayload payload) {
        Objects.requireNonNull(payload, "payload");
        this.x = payload.x();
        this.z = payload.z();
        this.radius = payload.radius();
        this.speed = payload.speed();
        this.durationTicks = payload.durationTicks();
    }

    /** Centre x in blocks. */
    public double x() {
        return x;
    }

    /** Centre z in blocks. */
    public double z() {
        return z;
    }

    /** Radius in blocks. */
    public float radius() {
        return radius;
    }

    /** Peak outflow in blocks per second. */
    public float speed() {
        return speed;
    }

    /** Client ticks since the downburst started. */
    public int ageTicks() {
        return ageTicks;
    }

    /** Length of the downburst in ticks. */
    public int durationTicks() {
        return durationTicks;
    }

    /** Ages the downburst by one client tick. */
    public void tick() {
        if (ageTicks < Integer.MAX_VALUE) {
            ageTicks++;
        }
    }

    /** Whether the downburst has lasted its whole duration; {@link ClientWeatherState#tick()} then drops it. */
    public boolean isOver() {
        return ageTicks >= durationTicks;
    }

    @Override
    public String toString() {
        return "ClientDownburst[x=" + x + ", z=" + z + ", radius=" + radius + ", speed=" + speed + ", age="
            + ageTicks + "/" + durationTicks + "]";
    }
}
