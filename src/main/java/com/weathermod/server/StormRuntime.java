package com.weathermod.server;

import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.impact.ActiveDownburst;
import com.weathermod.server.impact.ShieldVolumes;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Transient per-dimension state shared by the systems that act on the world: published vortices and downbursts,
 * per-tick budgets, flying block counts, recent strikes and Create shield volumes. Never saved: after a restart
 * the family systems publish their vortices again on their first tick and the flying blocks count themselves in
 * again as they load.
 *
 * <p>The class reads no configuration by itself; the simulator hands the two config values the level caps derive
 * from to {@link #beginTick} every level tick.
 */
public final class StormRuntime {
    /** The level-wide rip and flying block caps are this many times the per-storm config values. */
    public static final int LEVEL_BUDGET_STORMS = 2;
    /** Lightning bolts all storms of a level may strike in one level tick. */
    public static final int MAX_BOLTS_PER_LEVEL_TICK = 2;
    /** Entities all force passes of a level may push in one level tick. */
    public static final int MAX_FORCED_ENTITIES_PER_LEVEL_TICK = 256;
    /** Level event 2001 (break sound and up to 64 particles on every client in range) sent per level tick. */
    public static final int MAX_BREAK_EFFECTS_PER_LEVEL_TICK = 1;
    /** A strike closer than {@link #STRIKE_SPACING_BLOCKS} to a remembered one must wait this many ticks. */
    public static final int STRIKE_SPACING_TICKS = 20;
    /** Horizontal distance within which two strikes count as hitting the same spot, in blocks. */
    public static final double STRIKE_SPACING_BLOCKS = 32.0;
    /** How many of the latest strikes are remembered for the spacing rule. */
    public static final int REMEMBERED_STRIKES = 8;

    private final Int2ObjectLinkedOpenHashMap<ActiveVortex> vortices = new Int2ObjectLinkedOpenHashMap<>();
    private final Collection<ActiveVortex> vortexView = Collections.unmodifiableCollection(vortices.values());
    private final List<ActiveDownburst> downbursts = new ArrayList<>();
    private final Int2IntOpenHashMap flyingBlocks = new Int2IntOpenHashMap();
    private final ShieldVolumes shields = new ShieldVolumes();
    private final double[] strikeX = new double[REMEMBERED_STRIKES];
    private final double[] strikeZ = new double[REMEMBERED_STRIKES];
    private final int[] strikeTick = new int[REMEMBERED_STRIKES];
    private int strikeCount;
    private int nextStrike;
    private int flyingBlocksTotal;
    private int tick;
    private int blockBudgetPerTick;
    private int maxFlyingBlocksPerStorm;
    private int ripsThisTick;
    private int boltsThisTick;
    private int breakEffectsThisTick;
    private int forcedThisTick;

    /** Creates an empty runtime at tick 0 whose two level caps are zero until {@link #beginTick} sets them. */
    public StormRuntime() {
    }

    /**
     * Resets the per-tick budgets and remembers the tick and the two config values the level caps derive from.
     * Called first by the simulator every level tick with {@code WeatherManager.blockBudgetPerTick()} and
     * {@code WeatherManager.maxFlyingBlocksPerStorm()}. Negative config values count as zero. Vortices,
     * downbursts, flying block counts, remembered strikes and shields are kept.
     */
    public void beginTick(int tick, int blockBudgetPerTick, int maxFlyingBlocksPerStorm) {
        this.tick = tick;
        this.blockBudgetPerTick = Math.max(0, blockBudgetPerTick);
        this.maxFlyingBlocksPerStorm = Math.max(0, maxFlyingBlocksPerStorm);
        this.ripsThisTick = 0;
        this.boltsThisTick = 0;
        this.breakEffectsThisTick = 0;
        this.forcedThisTick = 0;
    }

    /** The weather tick the budgets were last reset for. */
    public int tick() {
        return tick;
    }

    /**
     * {@link #LEVEL_BUDGET_STORMS} times the block budget per tick: block removals per level tick across all
     * storms.
     */
    public int maxRipsPerTick() {
        return saturatedProduct(blockBudgetPerTick);
    }

    /**
     * {@link #LEVEL_BUDGET_STORMS} times the flying blocks per storm: flying blocks alive in the level across all
     * storms.
     */
    public int maxFlyingBlocks() {
        return saturatedProduct(maxFlyingBlocksPerStorm);
    }

    /** Publishes a vortex, replacing the entry of the same storm id; a replaced entry keeps its place in the order. */
    public void putVortex(ActiveVortex vortex) {
        Objects.requireNonNull(vortex, "vortex");
        vortices.put(vortex.stormId(), vortex);
    }

    /** The vortex last published for a storm, or null when the storm has none. */
    @Nullable
    public ActiveVortex vortex(int stormId) {
        return vortices.get(stormId);
    }

    /** Every published vortex in the order the storms first published one. The view cannot be modified. */
    public Collection<ActiveVortex> vortices() {
        return vortexView;
    }

    /** Withdraws the vortex of a storm; does nothing when the storm has none. */
    public void removeVortex(int stormId) {
        vortices.remove(stormId);
    }

    /** The downbursts blowing now. The list is the live one: the convective system adds to it and prunes it. */
    public List<ActiveDownburst> downbursts() {
        return downbursts;
    }

    /** Asks for one block removal: true while fewer than {@link #maxRipsPerTick()} were granted this tick. */
    public boolean tryRip() {
        if (ripsThisTick >= maxRipsPerTick()) {
            return false;
        }
        ripsThisTick++;
        return true;
    }

    /** Asks for one lightning bolt: true while fewer than {@link #MAX_BOLTS_PER_LEVEL_TICK} were granted this tick. */
    public boolean tryBolt() {
        if (boltsThisTick >= MAX_BOLTS_PER_LEVEL_TICK) {
            return false;
        }
        boltsThisTick++;
        return true;
    }

    /**
     * Asks for one block break effect: true while fewer than {@link #MAX_BREAK_EFFECTS_PER_LEVEL_TICK} were granted
     * this tick.
     */
    public boolean tryBreakEffect() {
        if (breakEffectsThisTick >= MAX_BREAK_EFFECTS_PER_LEVEL_TICK) {
            return false;
        }
        breakEffectsThisTick++;
        return true;
    }

    /** What is left of {@link #MAX_FORCED_ENTITIES_PER_LEVEL_TICK} this tick. */
    public int forcedLeft() {
        return MAX_FORCED_ENTITIES_PER_LEVEL_TICK - forcedThisTick;
    }

    /**
     * Books {@code count} entities that were really pushed this tick. Spending more than is left leaves zero;
     * a count of zero or less books nothing.
     */
    public void spendForced(int count) {
        if (count > 0) {
            forcedThisTick += Math.min(count, forcedLeft());
        }
    }

    /** Flying blocks alive that the given storm threw. */
    public int flyingBlocks(int stormId) {
        return flyingBlocks.get(stormId);
    }

    /** Flying blocks alive in the level across all storms. */
    public int flyingBlocksTotal() {
        return flyingBlocksTotal;
    }

    /** Counts one more flying block of the given storm. */
    public void flyingBlockAdded(int stormId) {
        flyingBlocks.addTo(stormId, 1);
        flyingBlocksTotal++;
    }

    /**
     * Counts one flying block of the given storm out. A storm without a counted block is left at zero, and the
     * total with it, so an entity that reports its removal twice cannot push a count below zero.
     */
    public void flyingBlockRemoved(int stormId) {
        int count = flyingBlocks.get(stormId);
        if (count <= 0) {
            return;
        }
        if (count == 1) {
            flyingBlocks.remove(stormId);
        } else {
            flyingBlocks.put(stormId, count - 1);
        }
        flyingBlocksTotal = Math.max(0, flyingBlocksTotal - 1);
    }

    /**
     * False when one of the last {@link #REMEMBERED_STRIKES} strikes was within {@link #STRIKE_SPACING_BLOCKS}
     * of the position less than {@link #STRIKE_SPACING_TICKS} ago, measured from the tick of {@link #beginTick}.
     * A remembered strike that seems to lie in the future, as after the tick counter wrapped, counts as old.
     */
    public boolean strikeAllowed(double x, double z) {
        double spacingSqr = STRIKE_SPACING_BLOCKS * STRIKE_SPACING_BLOCKS;
        for (int i = 0; i < strikeCount; i++) {
            int age = tick - strikeTick[i];
            if (age < 0 || age >= STRIKE_SPACING_TICKS) {
                continue;
            }
            double dx = x - strikeX[i];
            double dz = z - strikeZ[i];
            if (dx * dx + dz * dz <= spacingSqr) {
                return false;
            }
        }
        return true;
    }

    /** Remembers a strike at the position in the current tick, forgetting the oldest one when the memory is full. */
    public void recordStrike(double x, double z) {
        strikeX[nextStrike] = x;
        strikeZ[nextStrike] = z;
        strikeTick[nextStrike] = tick;
        nextStrike = (nextStrike + 1) % REMEMBERED_STRIKES;
        strikeCount = Math.min(REMEMBERED_STRIKES, strikeCount + 1);
    }

    /** The boxes in which no block is ripped, refreshed by the block ripper. */
    public ShieldVolumes shields() {
        return shields;
    }

    /** Drops the vortex of a removed storm. Flying block counts are left to the entities' own removal. */
    public void forgetStorm(int stormId) {
        removeVortex(stormId);
    }

    private static int saturatedProduct(int perStorm) {
        return (int) Math.min(Integer.MAX_VALUE, (long) LEVEL_BUDGET_STORMS * perStorm);
    }
}
