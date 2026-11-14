package com.weathermod.server.impact;

/**
 * The numbers of block ripping: how many blocks a vortex may take per tick, which blocks a tier takes at all, how
 * likely one attempt succeeds and how deep natural ground may be dug. Pure arithmetic, shared by the ripper and its
 * tests; nothing here touches a level.
 *
 * <p>Every method that takes a tier accepts any integer and treats values below 0 as tier 0 and values above 5 as
 * tier 5, the range of the Enhanced Fujita scale the tables are indexed by.
 */
public final class RipMath {
    /** Blocks no tier tag mentions are ripped when their hardness is at most this, by tier; 0 = tagged blocks only. */
    public static final float[] HARDNESS_CEILING = {0.0F, 0.3F, 0.8F, 2.0F, 3.5F, 50.0F};
    /** Columns sampled per tick are at most this many times the rip budget. */
    public static final int MAX_ATTEMPT_FACTOR = 4;
    /** Columns are sampled uniformly in a disc of this many base radii: the grab radius. */
    public static final float RIP_RADIUS_FACTOR = 1.25F;
    /** Hard blocks resist: see {@link #ripChance}. */
    public static final float RESISTANCE_WEIGHT = 0.75F;
    /** Lowest chance of an untagged block. */
    public static final float MIN_RIP_CHANCE = 0.25F;
    /** Lowest chance of a block in the tier's tag. */
    public static final float TAGGED_MIN_RIP_CHANCE = 0.5F;
    /** Natural ground is ripped at this share of its chance, by tier: the budget goes to what stands on it. */
    public static final float[] GROUND_CHANCE_FACTOR = {0.0F, 0.0F, 0.1F, 0.15F, 0.4F, 1.0F};
    /** Natural ground is never ripped more than this far below the vortex's dig reference, by tier. */
    public static final int[] MAX_DIG_BLOCKS = {0, 0, 1, 2, 4, 6};
    /** At most one flying block is launched per storm in this many ticks. */
    public static final int LAUNCH_INTERVAL_TICKS = 4;
    /** From this tier up, grass inside the core is scoured to dirt instead of being ripped. */
    public static final int SCOUR_MIN_TIER = 3;
    /** Chance that a grass block inside the core of a strong enough vortex is scoured on one attempt. */
    public static final float SCOUR_CHANCE = 0.6F;
    /** Scours per storm per tick; they have their own budget and do not use up rips. */
    public static final int MAX_SCOURS_PER_STORM_TICK = 4;
    /** Ripped blocks that drop an item are limited to this many per storm per tick. */
    public static final int MAX_DROPS_PER_STORM_TICK = 2;

    private static final int HIGHEST_TIER = 5;

    private RipMath() {
    }

    /**
     * Blocks a vortex may remove in one tick: 0 when {@code ripShare} is not above 0 (or the budget is not),
     * otherwise {@code max(1, round(blockBudgetPerTick * ripShare))}, so even the weakest ripping vortex takes one
     * block a tick.
     *
     * @param blockBudgetPerTick the configured budget of a vortex at full strength
     * @param ripShare           the share of that budget this vortex has now, its type's share times its strength
     */
    public static int ripsPerTick(int blockBudgetPerTick, float ripShare) {
        if (!(ripShare > 0.0F) || blockBudgetPerTick <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(blockBudgetPerTick * ripShare));
    }

    /** Columns a vortex may sample in one tick: {@code rips * MAX_ATTEMPT_FACTOR}, 0 for no rips. */
    public static int attemptsPerTick(int rips) {
        if (rips <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) rips * MAX_ATTEMPT_FACTOR);
    }

    /** Blocks examined per column from the top down: {@code 1 + tier}. */
    public static int sampleDepth(int tier) {
        return 1 + clampTier(tier);
    }

    /** The grab radius around the base of a vortex: {@code baseRadius * RIP_RADIUS_FACTOR}. */
    public static float ripRadius(float baseRadius) {
        return baseRadius * RIP_RADIUS_FACTOR;
    }

    /**
     * Whether a vortex of a tier takes a block at all. A negative hardness (bedrock, barriers) never; a block in
     * the tier's tag always; a block that only a higher tier's tag mentions never; a block no tier tag mentions
     * only when the tier's hardness ceiling is above 0 and the hardness is at most that ceiling, so EF0 takes
     * tagged blocks and nothing else.
     *
     * @param tier         the vortex's tier
     * @param inTierTag    whether the block is in the tier's own rippable tag (which includes the lower tiers')
     * @param inAnyTierTag whether the block is in the highest tier's tag, that is in any tier's
     * @param hardness     the block's destroy speed
     */
    public static boolean tierAllows(int tier, boolean inTierTag, boolean inAnyTierTag, float hardness) {
        if (hardness < 0.0F || Float.isNaN(hardness)) {
            return false;
        }
        if (inTierTag) {
            return true;
        }
        if (inAnyTierTag) {
            return false;
        }
        float ceiling = HARDNESS_CEILING[clampTier(tier)];
        return ceiling > 0.0F && hardness <= ceiling;
    }

    /**
     * Chance that an allowed block is ripped on one attempt. With {@code c = HARDNESS_CEILING[tier]}:
     * <pre>
     * base   = (c &lt;= 0 || hardness &lt;= 0) ? 1 : 1 - RESISTANCE_WEIGHT * min(hardness, c) / c
     * chance = max(inTierTag ? TAGGED_MIN_RIP_CHANCE : MIN_RIP_CHANCE, base)
     *          * (naturalGround ? GROUND_CHANCE_FACTOR[tier] : 1)
     * </pre>
     * A block as hard as the tier can take resists most; natural ground is thinned out so that the budget goes to
     * what stands on it.
     *
     * @param tier          the vortex's tier
     * @param hardness      the block's destroy speed
     * @param inTierTag     whether the block is in the tier's own rippable tag
     * @param naturalGround whether the block is natural ground
     */
    public static float ripChance(int tier, float hardness, boolean inTierTag, boolean naturalGround) {
        int index = clampTier(tier);
        float ceiling = HARDNESS_CEILING[index];
        float base;
        if (ceiling <= 0.0F || !(hardness > 0.0F)) {
            base = 1.0F;
        } else {
            base = 1.0F - RESISTANCE_WEIGHT * Math.min(hardness, ceiling) / ceiling;
        }
        float chance = Math.max(inTierTag ? TAGGED_MIN_RIP_CHANCE : MIN_RIP_CHANCE, base);
        return naturalGround ? chance * GROUND_CHANCE_FACTOR[index] : chance;
    }

    /** How far below its dig reference a vortex of a tier may rip natural ground, see {@link #MAX_DIG_BLOCKS}. */
    public static int maxDig(int tier) {
        return MAX_DIG_BLOCKS[clampTier(tier)];
    }

    /**
     * Whether natural ground at a height may be ripped: true when {@code digRefY} is NaN (the reference was never
     * sampled) or {@code y >= digRefY - maxDig(tier)}. Asked for natural ground only.
     *
     * @param tier    the vortex's tier
     * @param digRefY the untouched terrain height the limit is measured from, or NaN
     * @param y       the height of the block
     */
    public static boolean digAllowed(int tier, double digRefY, int y) {
        if (Double.isNaN(digRefY)) {
            return true;
        }
        return y >= digRefY - maxDig(tier);
    }

    /**
     * Flying blocks a vortex may keep in the air: {@code round(maxFlyingBlocksPerStorm * share)}, never negative.
     *
     * @param maxFlyingBlocksPerStorm the configured cap of a vortex with a share of 1
     * @param share                   the share of that cap the vortex's type has
     */
    public static int flyingBlockCap(int maxFlyingBlocksPerStorm, float share) {
        if (!(share > 0.0F) || maxFlyingBlocksPerStorm <= 0) {
            return 0;
        }
        return Math.round(maxFlyingBlocksPerStorm * share);
    }

    private static int clampTier(int tier) {
        if (tier < 0) {
            return 0;
        }
        return Math.min(tier, HIGHEST_TIER);
    }
}
