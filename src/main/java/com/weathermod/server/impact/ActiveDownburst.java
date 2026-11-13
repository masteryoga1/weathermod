package com.weathermod.server.impact;

import com.weathermod.core.WindForce;

/**
 * A downburst in progress. Times are simulation ticks of the dimension.
 *
 * @param x             centre x in blocks
 * @param z             centre z in blocks
 * @param radius        radius in blocks
 * @param speed         peak outflow in blocks per second
 * @param startTick     the simulation tick the downburst started in
 * @param durationTicks how long it lasts, in ticks
 */
public record ActiveDownburst(double x, double z, float radius, float speed, int startTick, int durationTicks) {
    /** Every downburst lasts five seconds: one second to build, two at full strength, two to fade. */
    public static final int DURATION_TICKS = 100;

    /** Ticks since the downburst started; negative when {@code now} lies before its start. */
    public int ageTicks(int now) {
        return now - startTick;
    }

    /**
     * Whether the downburst has blown itself out. One that seems to start in the future, as after the
     * dimension's tick counter wrapped, counts as over.
     */
    public boolean isOver(int now) {
        int age = ageTicks(now);
        return age < 0 || age >= durationTicks;
    }

    /** How strongly the downburst blows now, 0..1, see {@link WindForce#downburstEnvelope(int, int)}. */
    public float envelope(int now) {
        return WindForce.downburstEnvelope(ageTicks(now), durationTicks);
    }
}
