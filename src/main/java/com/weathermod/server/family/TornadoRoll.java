package com.weathermod.server.family;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The dice of tornado formation: whether a storm that may produce a tornado does, and which EF tier that tornado
 * grows to. Pure apart from the random source it is handed.
 */
public final class TornadoRoll {
    /** Relative odds of EF0..EF5: 40 % EF0 down to 2 % EF5, the shape of real tornado statistics. */
    public static final int[] TIER_WEIGHTS = {40, 28, 16, 9, 5, 2};

    private TornadoRoll() {
    }

    /**
     * A weighted tier among 0..{@code maxTier} (clamped to 0..5): each tier is drawn with its share of
     * {@link #TIER_WEIGHTS} among the tiers allowed. Reads the random source once.
     */
    public static int tier(RandomSource random, int maxTier) {
        int highest = Mth.clamp(maxTier, 0, TIER_WEIGHTS.length - 1);
        int total = 0;
        for (int tier = 0; tier <= highest; tier++) {
            total += TIER_WEIGHTS[tier];
        }
        int roll = random.nextInt(total);
        for (int tier = 0; tier < highest; tier++) {
            roll -= TIER_WEIGHTS[tier];
            if (roll < 0) {
                return tier;
            }
        }
        return highest;
    }

    /**
     * One roll at {@code min(1, typeChance * frequency)}; never when either is zero (or less, or not a number),
     * and then the random source is not read.
     *
     * @param typeChance the weather type's chance that one storm produces a tornado, 0..1
     * @param frequency  the configured multiplier on that chance
     */
    public static boolean happens(float typeChance, double frequency, RandomSource random) {
        if (!(typeChance > 0.0F) || !(frequency > 0.0)) {
            return false;
        }
        double chance = Math.min(1.0, typeChance * frequency);
        return random.nextDouble() < chance;
    }
}
