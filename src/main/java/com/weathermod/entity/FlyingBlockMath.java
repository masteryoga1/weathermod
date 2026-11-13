package com.weathermod.entity;

import net.minecraft.util.RandomSource;
import org.joml.Vector3d;

/**
 * The numbers of a flying block: how it is launched, how long it orbits and how hard it hits. Pure arithmetic,
 * shared by the ripper, the entity and their tests.
 */
public final class FlyingBlockMath {
    /** Lowest upward launch speed, blocks per tick. */
    public static final float LAUNCH_UP_MIN = 0.3F;
    /** Highest upward launch speed, blocks per tick. */
    public static final float LAUNCH_UP_MAX = 0.5F;
    /** Orbit time of a block thrown by an EF0: the time by tier is {@code 40 + 24 * tier} ticks. */
    public static final int ORBIT_BASE_TICKS = 40;
    /** Orbit time added per tier. */
    public static final int ORBIT_TICKS_PER_TIER = 24;
    /** The orbit time varies by this much either way. */
    public static final int ORBIT_JITTER_TICKS = 20;
    /** A flying block hurts what it touches above this speed, blocks per tick. */
    public static final double DAMAGE_MIN_SPEED = 0.3;

    private static final int HIGHEST_TIER = 5;
    private static final int ORBIT_MIN_TICKS = ORBIT_BASE_TICKS;
    private static final int ORBIT_MAX_TICKS = ORBIT_BASE_TICKS + ORBIT_TICKS_PER_TIER * HIGHEST_TIER;
    private static final float DAMAGE_AT_REST = 1.0F;
    private static final float DAMAGE_PER_SPEED = 4.0F;
    private static final float MAX_DAMAGE = 10.0F;
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double AXIS_EPSILON_BLOCKS = 1.0E-6;

    private FlyingBlockMath() {
    }

    /**
     * How long a block thrown by a vortex of a tier stays in orbit before it is released:
     * {@code ORBIT_BASE_TICKS + ORBIT_TICKS_PER_TIER * tier}, varied by up to {@link #ORBIT_JITTER_TICKS} either
     * way and kept within 40 to 160 ticks. A tier outside 0 to 5 counts as the nearest of the two.
     *
     * @param tier   the vortex's tier
     * @param random rolls the variation; one {@code nextInt} is drawn
     */
    public static int orbitTicks(int tier, RandomSource random) {
        int clampedTier = Math.max(0, Math.min(HIGHEST_TIER, tier));
        int jitter = random.nextInt(2 * ORBIT_JITTER_TICKS + 1) - ORBIT_JITTER_TICKS;
        int ticks = ORBIT_BASE_TICKS + ORBIT_TICKS_PER_TIER * clampedTier + jitter;
        return Math.max(ORBIT_MIN_TICKS, Math.min(ORBIT_MAX_TICKS, ticks));
    }

    /**
     * Damage points a flying block deals at a speed: {@code clamp(1 + 4 * speed, 1, 10)}. A speed that is not a
     * number counts as rest.
     *
     * @param speedBlocksPerTick the block's speed, in blocks per tick
     */
    public static float impactDamage(double speedBlocksPerTick) {
        if (!(speedBlocksPerTick > 0.0)) {
            return DAMAGE_AT_REST;
        }
        double damage = DAMAGE_AT_REST + DAMAGE_PER_SPEED * speedBlocksPerTick;
        return damage >= MAX_DAMAGE ? MAX_DAMAGE : (float) damage;
    }

    /**
     * The velocity a ripped block starts with: along the tangent of its circle around the vortex axis,
     * counter-clockwise seen from above (a block east of the axis is thrown north, toward negative z, the way the
     * vortex field turns), at {@code throwSpeed / 20} blocks per tick, plus the upward part. A block on the axis
     * itself has no tangent and starts straight up.
     *
     * @param dxFromAxis                the block's x minus the axis's x at its height
     * @param dzFromAxis                the block's z minus the axis's z at its height
     * @param throwSpeedBlocksPerSecond the horizontal launch speed of the vortex's type, in blocks per second
     * @param up                        the upward launch speed, in blocks per tick
     * @param out                       receives the velocity in blocks per tick; always written
     */
    public static void launchVelocity(double dxFromAxis, double dzFromAxis, float throwSpeedBlocksPerSecond,
                                      float up, Vector3d out) {
        double r = Math.sqrt(dxFromAxis * dxFromAxis + dzFromAxis * dzFromAxis);
        if (!(r >= AXIS_EPSILON_BLOCKS)) {
            out.set(0.0, up, 0.0);
            return;
        }
        double speed = Math.max(0.0, throwSpeedBlocksPerSecond) / TICKS_PER_SECOND;
        out.set(dzFromAxis / r * speed, up, -dxFromAxis / r * speed);
    }
}
