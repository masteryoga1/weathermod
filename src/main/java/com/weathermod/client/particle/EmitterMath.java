package com.weathermod.client.particle;

import com.weathermod.core.Headings;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Pure arithmetic behind the weather particle emitter and the particles themselves: how many particles a tick
 * should spawn, where in a ring around the camera they go, how fast the wind carries them, how their alpha and size
 * envelopes run and what colour dust takes over a ground. Nothing here touches a level, so every rule is
 * unit-tested.
 */
public final class EmitterMath {
    /** Expected haze puffs per tick at haze 1, haze strength 1 and particle density 1. */
    public static final float HAZE_PER_TICK = 1.5F;
    /** Nearest horizontal distance from the camera a haze puff appears at. */
    public static final double HAZE_MIN_DISTANCE = 16.0D;
    /**
     * Farthest horizontal distance from the camera a haze puff appears at. The vanilla particle path silently drops
     * a particle that is not forced past the limiter when it is more than 32 blocks from the camera, so this and
     * {@link #HAZE_ABOVE_CAMERA} are chosen to keep every puff inside that distance ({@code sqrt(27^2 + 16^2) < 32})
     * rather than spend spawn attempts on puffs that never exist.
     */
    public static final double HAZE_MAX_DISTANCE = 27.0D;
    /** Lowest height above the heightmap a haze puff appears at. */
    public static final int HAZE_RISE_MIN = 1;
    /** Highest height above the heightmap a haze puff appears at. */
    public static final int HAZE_RISE_MAX = 10;
    /** A haze puff never appears more than this far below the camera. */
    public static final int HAZE_BELOW_CAMERA = 6;
    /** A haze puff never appears more than this far above the camera. */
    public static final int HAZE_ABOVE_CAMERA = 16;

    /** Wind speed in blocks per second from which dust starts to blow. */
    public static final float DUST_MIN_WIND = 5.0F;
    /** Wind speed in blocks per second at which dust blows at its full rate. */
    public static final float DUST_FULL_WIND = 15.0F;
    /**
     * Expected dust puffs per tick at full wind and density 1. With the longest life of a puff this bounds the puffs
     * alive at once: {@code DUST_PER_TICK * WindDustParticle.MAX_LIFETIME} = 300 at density 1.
     */
    public static final float DUST_PER_TICK = 6.0F;
    /** Nearest horizontal distance from the camera a dust puff appears at. */
    public static final double DUST_MIN_DISTANCE = 4.0D;
    /** Farthest horizontal distance from the camera a dust puff appears at. */
    public static final double DUST_MAX_DISTANCE = 16.0D;
    /**
     * A dust puff never appears more than this far above or below the camera, so a puff over a cliff or a valley
     * floor stays inside the 32-block vanilla limiter ({@code sqrt(16^2 + 24^2) < 32}).
     */
    public static final double DUST_VERTICAL_REACH = 24.0D;

    /** Wind speed in blocks per second from which leaves start to come loose. */
    public static final float LEAF_MIN_WIND = 3.0F;
    /** Wind speed in blocks per second at which leaves come loose at their full rate. */
    public static final float LEAF_FULL_WIND = 15.0F;
    /**
     * Expected leaves per tick at full wind and density 1, where the canopy is dense enough to supply them. With the
     * longest life of a leaf this bounds the leaves alive at once:
     * {@code LEAF_PER_TICK * WindLeafParticle.MAX_LIFETIME} = 360 at density 1.
     */
    public static final float LEAF_PER_TICK = 3.0F;
    /**
     * Canopy columns tested for each leaf the tick wants. A forest answers most tries, so its rate follows the wind;
     * a lone tree answers few, so this is what sets how strongly it sheds.
     */
    public static final int LEAF_TRIES_PER_LEAF = 6;
    /** Nearest horizontal distance from the camera a leaf comes loose at, so none appears in the player's face. */
    public static final double LEAF_MIN_DISTANCE = 2.0D;
    /** Farthest horizontal distance from the camera a leaf comes loose at. */
    public static final double LEAF_DISTANCE = 20.0D;
    /** A leaf never appears more than this far below the camera. */
    public static final int LEAF_BELOW_CAMERA = 8;
    /**
     * A leaf never appears more than this far above the camera; with {@link #LEAF_DISTANCE} that keeps every leaf
     * inside the 32-block vanilla limiter ({@code sqrt(20^2 + 16^2) < 32}).
     */
    public static final int LEAF_ABOVE_CAMERA = 16;
    /**
     * Share of the canopy tries that look at the top face of the canopy first; the rest start at its underside. A
     * leaf off the top flies over the trees, out of sight of a viewer under the canopy, so this is the number to
     * lower first should a forest look thin from the ground.
     */
    public static final float LEAF_TOP_SHARE = 0.5F;

    /** Fraction of the wind speed a haze puff drifts at. */
    public static final float HAZE_DRIFT = 0.35F;
    /** Fraction of the wind speed a dust puff starts at; slow enough that the eye can follow it. */
    public static final float DUST_DRIFT = 0.5F;
    /** Fraction of the wind speed a leaf is carried at. */
    public static final float LEAF_DRIFT = 0.8F;
    /**
     * Fraction of the gap to the wind's carrying velocity a leaf closes each tick. Against the vanilla particle
     * friction of 0.98 a leaf settles at about 0.8 of that velocity instead of slowing to a stop.
     */
    public static final float LEAF_WIND_PULL = 0.08F;

    /** Ticks a haze puff takes to fade from nothing to its peak alpha. */
    public static final int HAZE_FADE_IN_TICKS = 20;
    /** Ticks before its death over which a haze puff fades back to nothing. */
    public static final int HAZE_FADE_OUT_TICKS = 40;
    /**
     * Alpha at the centre of the haze sprites, as {@code tools/assets/generate_textures.py} draws them. The opacity
     * of a puff is this times its vertex alpha.
     */
    public static final float HAZE_SPRITE_CENTRE_ALPHA = 0.6F;
    /**
     * Peak vertex alpha of a haze puff without a shader pack: the centre of a puff is then
     * {@code 0.6 * 0.675 = 0.405} opaque, well above the 0.1 discard.
     */
    public static final float HAZE_PEAK_ALPHA = 0.675F;
    /**
     * Peak vertex alpha of a haze puff under a shader pack. Packs commonly read a particle's vertex alpha as an
     * occlusion term and darken the particle by it, so the puff is drawn with a full vertex alpha there and takes
     * its whole opacity from the sprite; it is then lit like a white surface instead of a shadowed one.
     */
    public static final float HAZE_PEAK_ALPHA_SHADER_PACK = 1.0F;

    /** Ticks a dust puff takes to grow from nothing to its full size. */
    public static final float DUST_GROW_TICKS = 3.0F;
    /** Fraction of a dust puff's lifetime, at the end, over which it shrinks to nothing. */
    public static final float DUST_SHRINK_FRACTION = 1.0F / 3.0F;
    /** Ticks a leaf takes to grow from nothing to its full size. */
    public static final float LEAF_GROW_TICKS = 2.0F;
    /** Ticks before its death over which a leaf shrinks to nothing; an opaque particle cannot fade. */
    public static final float LEAF_SHRINK_TICKS = 10.0F;

    /** The pale colour dust is mixed toward over dark and mid-toned ground, as {@code 0xRRGGBB}. */
    public static final int DUST_PALE_RGB = 0xECE0C4;
    /** The ground colour assumed where the ground has no map colour: that of dirt. */
    public static final int DUST_DEFAULT_GROUND_RGB = 0x976D4D;
    /** How far dust moves from the ground colour toward {@link #DUST_PALE_RGB} over dark and mid-toned ground. */
    public static final float DUST_PALE_MIX = 0.75F;
    /** Factor the ground colour is darkened by to give the dust over light ground. */
    public static final float DUST_DARKEN = 0.65F;
    /** Luma (Rec. 601 weights, 0 to 1) from which a ground counts as light. */
    public static final float DUST_LIGHT_GROUND_LUMA = 0.62F;

    /** Radians a leaf rolls per tick in still air. */
    public static final float LEAF_ROLL_BASE = 0.1F;
    /** Wind speed in blocks per second that doubles the leaf roll rate. */
    public static final float LEAF_ROLL_WIND_SCALE = 10.0F;

    private static final double FULL_CIRCLE = 2.0D * Math.PI;
    private static final float LUMA_RED = 0.299F;
    private static final float LUMA_GREEN = 0.587F;
    private static final float LUMA_BLUE = 0.114F;
    private static final float CHANNEL_MAX = 255.0F;
    private static final int RGB_MASK = 0xFFFFFF;

    private EmitterMath() {
    }

    /**
     * Turns an expected count into a whole number of particles: the whole part always, plus one more with a
     * probability equal to the fractional part, so the long-run average equals the expectation. A count that is not
     * positive, or not a number, gives 0.
     */
    public static int stochasticCount(float expected, RandomSource random) {
        if (!(expected > 0.0F)) {
            return 0;
        }
        int whole = (int) expected;
        float fraction = expected - whole;
        return whole + (random.nextFloat() < fraction ? 1 : 0);
    }

    /**
     * Expected haze puffs this tick: the haze level times {@link #HAZE_PER_TICK}, the haze strength and the
     * density; nothing when any of them is not positive.
     */
    public static float hazeExpected(float haze, double hazeStrength, double density) {
        if (!(haze > 0.0F) || !(hazeStrength > 0.0D) || !(density > 0.0D)) {
            return 0.0F;
        }
        return (float) (haze * HAZE_PER_TICK * hazeStrength * density);
    }

    /**
     * Expected dust puffs this tick: nothing up to {@link #DUST_MIN_WIND}, rising linearly to {@link #DUST_PER_TICK}
     * at {@link #DUST_FULL_WIND} and capped there, using the stronger of the sustained and the gust speed, times the
     * density.
     */
    public static float dustExpected(float windSpeed, float gustSpeed, double density) {
        if (!(density > 0.0D)) {
            return 0.0F;
        }
        float wind = Math.max(windSpeed, gustSpeed);
        float ramp = Mth.clamp((wind - DUST_MIN_WIND) / (DUST_FULL_WIND - DUST_MIN_WIND), 0.0F, 1.0F);
        return (float) (ramp * DUST_PER_TICK * density);
    }

    /**
     * Expected leaves this tick: nothing up to {@link #LEAF_MIN_WIND}, rising with the square of the ramp to
     * {@link #LEAF_PER_TICK} at {@link #LEAF_FULL_WIND} and capped there, using the stronger of the sustained and
     * the gust speed, times the density. The ramp is squared so an everyday breeze sheds the odd leaf while a gale
     * strips the canopy.
     */
    public static float leafExpected(float windSpeed, float gustSpeed, double density) {
        if (!(density > 0.0D)) {
            return 0.0F;
        }
        float wind = Math.max(windSpeed, gustSpeed);
        float ramp = Mth.clamp((wind - LEAF_MIN_WIND) / (LEAF_FULL_WIND - LEAF_MIN_WIND), 0.0F, 1.0F);
        return (float) (ramp * ramp * LEAF_PER_TICK * density);
    }

    /**
     * A random horizontal offset in the ring between two distances: the angle is uniform around the circle and the
     * distance uniform in {@code [minDistance, maxDistance]}.
     *
     * @return {@code {dx, dz}}
     */
    public static double[] ringOffset(RandomSource random, double minDistance, double maxDistance) {
        double angle = random.nextDouble() * FULL_CIRCLE;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        return new double[] {Math.cos(angle) * distance, Math.sin(angle) * distance};
    }

    /**
     * Blocks per tick something carried at a fraction of a wind speed covers: the speed in blocks per second over
     * the ticks in a second, times the drift factor; zero for no wind.
     */
    public static double driftPerTick(float speedBlocksPerSecond, float factor) {
        if (!(speedBlocksPerSecond > 0.0F)) {
            return 0.0D;
        }
        return speedBlocksPerSecond / (double) SharedConstants.TICKS_PER_SECOND * factor;
    }

    /**
     * The per-tick velocity of something carried by the wind: the heading's unit vector (through
     * {@link Headings}) times {@link #driftPerTick}; zero for no wind.
     *
     * @return {@code {vx, vz}} in blocks per tick
     */
    public static double[] windVelocity(float directionDegrees, float speedBlocksPerSecond, float factor) {
        double perTick = driftPerTick(speedBlocksPerSecond, factor);
        if (perTick == 0.0D) {
            return new double[] {0.0D, 0.0D};
        }
        return new double[] {Headings.directionX(directionDegrees) * perTick,
            Headings.directionZ(directionDegrees) * perTick};
    }

    /**
     * One component of a leaf's velocity after the wind has pulled on it for a tick: it closes
     * {@link #LEAF_WIND_PULL} of the gap to the wind's carrying velocity, so a leaf follows gusts and turns of the
     * wind and picks up speed again after an obstacle let it go.
     */
    public static double leafPull(double velocity, double windVelocity) {
        return velocity + (windVelocity - velocity) * LEAF_WIND_PULL;
    }

    /** The peak vertex alpha of a haze puff: {@link #HAZE_PEAK_ALPHA}, or the shader pack value under a pack. */
    public static float hazePeakAlpha(boolean shaderPackInUse) {
        return shaderPackInUse ? HAZE_PEAK_ALPHA_SHADER_PACK : HAZE_PEAK_ALPHA;
    }

    /** Vertex alpha of a haze puff at an age without a shader pack, see {@link #hazeAlpha(int, int, float)}. */
    public static float hazeAlpha(int age, int lifetime) {
        return hazeAlpha(age, lifetime, HAZE_PEAK_ALPHA);
    }

    /**
     * Vertex alpha of a haze puff at an age: 0 at birth rising to the peak over {@link #HAZE_FADE_IN_TICKS}, the
     * peak through the middle, and back to 0 over the last {@link #HAZE_FADE_OUT_TICKS}; the lower of the two ramps
     * wins when a short life makes them overlap.
     */
    public static float hazeAlpha(int age, int lifetime, float peakAlpha) {
        if (age <= 0 || lifetime <= 0 || age >= lifetime) {
            return 0.0F;
        }
        float fadeIn = Math.min(1.0F, age / (float) HAZE_FADE_IN_TICKS);
        float fadeOut = Math.min(1.0F, (lifetime - age) / (float) HAZE_FADE_OUT_TICKS);
        return peakAlpha * Math.min(fadeIn, fadeOut);
    }

    /**
     * Size factor of an opaque particle at an age, in {@code [0, 1]}: it grows from nothing over the first
     * {@code growTicks}, holds its full size and shrinks back to nothing over the last {@code shrinkTicks}; the
     * lower of the two ramps wins when a short life makes them overlap. A negative age counts as birth, a dead
     * particle has no size, and a ramp that is not positive is skipped.
     */
    public static float sizeEnvelope(float age, int lifetime, float growTicks, float shrinkTicks) {
        if (lifetime <= 0 || !(age < lifetime)) {
            return 0.0F;
        }
        float lived = Math.max(age, 0.0F);
        float grow = growTicks > 0.0F ? Math.min(1.0F, lived / growTicks) : 1.0F;
        float shrink = shrinkTicks > 0.0F ? Math.min(1.0F, (lifetime - lived) / shrinkTicks) : 1.0F;
        return Math.min(grow, shrink);
    }

    /**
     * Size factor of a dust puff at an age: grown in over {@link #DUST_GROW_TICKS} and shrunk away over the last
     * {@link #DUST_SHRINK_FRACTION} of its life.
     */
    public static float dustScale(float age, int lifetime) {
        return sizeEnvelope(age, lifetime, DUST_GROW_TICKS, lifetime * DUST_SHRINK_FRACTION);
    }

    /**
     * Size factor of a leaf at an age: grown in over {@link #LEAF_GROW_TICKS} and shrunk away over the last
     * {@link #LEAF_SHRINK_TICKS}.
     */
    public static float leafScale(float age, int lifetime) {
        return sizeEnvelope(age, lifetime, LEAF_GROW_TICKS, LEAF_SHRINK_TICKS);
    }

    /**
     * The colour of dust raised from a ground, chosen to stand out against that ground: over dark and mid-toned
     * ground the ground colour moved {@link #DUST_PALE_MIX} of the way to {@link #DUST_PALE_RGB}, over light ground
     * (luma from {@link #DUST_LIGHT_GROUND_LUMA}) the ground colour darkened by {@link #DUST_DARKEN}. Blue is capped
     * at red, so dust never turns bluish over ice or clay. A ground without a colour counts as dirt.
     *
     * @param groundRgb the ground's map colour as {@code 0xRRGGBB}; higher bits are ignored
     * @return the dust colour as {@code 0xRRGGBB}
     */
    public static int dustColor(int groundRgb) {
        int ground = (groundRgb & RGB_MASK) == 0 ? DUST_DEFAULT_GROUND_RGB : groundRgb & RGB_MASK;
        float red = ground >> 16 & 0xFF;
        float green = ground >> 8 & 0xFF;
        float blue = ground & 0xFF;
        float luma = (LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue) / CHANNEL_MAX;
        if (luma >= DUST_LIGHT_GROUND_LUMA) {
            red *= DUST_DARKEN;
            green *= DUST_DARKEN;
            blue *= DUST_DARKEN;
        } else {
            red = Mth.lerp(DUST_PALE_MIX, red, DUST_PALE_RGB >> 16 & 0xFF);
            green = Mth.lerp(DUST_PALE_MIX, green, DUST_PALE_RGB >> 8 & 0xFF);
            blue = Mth.lerp(DUST_PALE_MIX, blue, DUST_PALE_RGB & 0xFF);
        }
        blue = Math.min(blue, red);
        return Math.round(red) << 16 | Math.round(green) << 8 | Math.round(blue);
    }

    /** Radians a leaf rolls per tick: {@link #LEAF_ROLL_BASE} times {@code 1 + windSpeed / LEAF_ROLL_WIND_SCALE}. */
    public static float leafRollStep(float windSpeed) {
        float wind = Math.max(0.0F, windSpeed);
        return LEAF_ROLL_BASE * (1.0F + wind / LEAF_ROLL_WIND_SCALE);
    }
}
