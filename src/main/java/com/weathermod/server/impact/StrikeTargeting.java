package com.weathermod.server.impact;

import java.util.List;
import net.minecraft.core.Position;
import net.minecraft.util.RandomSource;

/**
 * Chooses where a storm's lightning comes down. Most strikes land in sight of a player, so the storm is seen and
 * heard, but none is aimed at one: a near strike keeps at least {@link #NEAR_MIN_BLOCKS} away. The rest fall
 * anywhere under the storm. Downbursts pick their centre the same way. Pure arithmetic over positions.
 */
public final class StrikeTargeting {
    /** Share of strikes placed near a player so the storm is seen and heard. */
    public static final float NEAR_PLAYER_CHANCE = 0.6F;
    /** A strike near a player lands at least this far from the player, in blocks. */
    public static final double NEAR_MIN_BLOCKS = 16.0;
    /** A strike near a player lands at most this far from the player, in blocks. */
    public static final double NEAR_MAX_BLOCKS = 64.0;
    /** Other strikes fall uniformly in this fraction of the storm radius. */
    public static final float DISC_FRACTION = 0.8F;

    private StrikeTargeting() {
    }

    /**
     * Picks a strike point inside the storm. With players and a chance of {@link #NEAR_PLAYER_CHANCE} the point
     * lies {@link #NEAR_MIN_BLOCKS} to {@link #NEAR_MAX_BLOCKS} from one of them, picked at random, in a random
     * direction; a near-player point that falls outside the radius is pulled back onto it, which never moves it
     * further from a player who stands inside the storm. Otherwise the point is uniform over the disc of
     * {@link #DISC_FRACTION} of the radius. A storm without a radius gives its centre. Writes {@code out[0] = x}
     * and {@code out[1] = z}.
     *
     * @param playersInside the positions of the players inside the storm radius, possibly none
     */
    public static void pick(double stormX, double stormZ, float radius, List<? extends Position> playersInside,
                            RandomSource random, double[] out) {
        if (!(radius > 0.0F)) {
            out[0] = stormX;
            out[1] = stormZ;
            return;
        }
        if (!playersInside.isEmpty() && random.nextFloat() < NEAR_PLAYER_CHANCE) {
            Position player = playersInside.get(random.nextInt(playersInside.size()));
            double minSqr = NEAR_MIN_BLOCKS * NEAR_MIN_BLOCKS;
            double maxSqr = NEAR_MAX_BLOCKS * NEAR_MAX_BLOCKS;
            double distance = Math.sqrt(minSqr + random.nextDouble() * (maxSqr - minSqr));
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dx = player.x() + Math.cos(angle) * distance - stormX;
            double dz = player.z() + Math.sin(angle) * distance - stormZ;
            double fromCentre = Math.sqrt(dx * dx + dz * dz);
            if (fromCentre > radius) {
                double scale = radius / fromCentre;
                dx *= scale;
                dz *= scale;
            }
            out[0] = stormX + dx;
            out[1] = stormZ + dz;
            return;
        }
        double distance = (double) DISC_FRACTION * radius * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble() * Math.PI * 2.0;
        out[0] = stormX + Math.cos(angle) * distance;
        out[1] = stormZ + Math.sin(angle) * distance;
    }
}
