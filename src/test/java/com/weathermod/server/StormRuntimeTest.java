package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.FunnelSpine;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherType;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.impact.ActiveDownburst;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Checks the transient runtime the world-changing systems share: the per-tick budgets and the level caps they
 * derive from, the forced-entity budget that is paid for use, the strike spacing rule, the flying block counters
 * and the published vortices and downbursts. Needs no Minecraft bootstrap: a vortex is a record of plain numbers.
 */
class StormRuntimeTest {
    /** The default of the config key {@code blockBudgetPerTick}. */
    private static final int BLOCK_BUDGET_PER_TICK = 16;
    /** The default of the config key {@code maxFlyingBlocksPerStorm}. */
    private static final int MAX_FLYING_BLOCKS_PER_STORM = 48;

    /** A grounded vortex of the given storm at the origin. */
    static ActiveVortex vortex(int stormId) {
        return vortex(stormId, 0.0D, 0.0D);
    }

    /** A grounded vortex of the given storm: a weak tornado whose funnel stands straight from y 64 to y 160. */
    static ActiveVortex vortex(int stormId, double x, double z) {
        VortexParams params = new VortexParams(VortexKind.TORNADO, 0, 3.0F, 10.0F, 24.0F, WeatherType.Range.of(0.0F),
            8.0F, 0.034F, 0.05F, 0.15F, 4.0F, 0.0F, 0.0F, 6.0F, 0.0F, 8.0F);
        FunnelSpine spine = new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(0.0F, 160.0F, 0.0F)),
            3.0F, 10.0F, 1.0F, 1.0F, true);
        return new ActiveVortex(stormId, params, x, z, spine, true, 64.0D);
    }

    private static StormRuntime startedAt(int tick) {
        StormRuntime runtime = new StormRuntime();
        runtime.beginTick(tick, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        return runtime;
    }

    private static List<Integer> stormIds(Collection<ActiveVortex> vortices) {
        List<Integer> ids = new ArrayList<>();
        for (ActiveVortex vortex : vortices) {
            ids.add(vortex.stormId());
        }
        return ids;
    }

    @Test
    void constantsMatchTheDesign() {
        assertEquals(2, StormRuntime.LEVEL_BUDGET_STORMS);
        assertEquals(2, StormRuntime.MAX_BOLTS_PER_LEVEL_TICK);
        assertEquals(256, StormRuntime.MAX_FORCED_ENTITIES_PER_LEVEL_TICK);
        assertEquals(1, StormRuntime.MAX_BREAK_EFFECTS_PER_LEVEL_TICK);
        assertEquals(20, StormRuntime.STRIKE_SPACING_TICKS);
        assertEquals(32.0D, StormRuntime.STRIKE_SPACING_BLOCKS);
        assertEquals(8, StormRuntime.REMEMBERED_STRIKES);
    }

    @Test
    void aNewRuntimeIsEmptyAndRipsNothingBeforeItsFirstTick() {
        StormRuntime runtime = new StormRuntime();

        assertEquals(0, runtime.tick());
        assertEquals(0, runtime.maxRipsPerTick(), "the level caps are unknown until the simulator hands them in");
        assertEquals(0, runtime.maxFlyingBlocks());
        assertFalse(runtime.tryRip());
        assertTrue(runtime.vortices().isEmpty());
        assertTrue(runtime.downbursts().isEmpty());
        assertEquals(0, runtime.flyingBlocksTotal());
        assertEquals(StormRuntime.MAX_FORCED_ENTITIES_PER_LEVEL_TICK, runtime.forcedLeft());
        assertTrue(runtime.strikeAllowed(0.0D, 0.0D));
        assertNotNull(runtime.shields());
        assertSame(runtime.shields(), runtime.shields(), "one set of shield volumes per runtime");
        assertTrue(runtime.shields().isEmpty());
    }

    @Test
    void levelCapsFollowTheTwoConfigValues() {
        StormRuntime runtime = new StormRuntime();

        runtime.beginTick(7, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(7, runtime.tick());
        assertEquals(32, runtime.maxRipsPerTick(), "two storms' worth of the default 16");
        assertEquals(96, runtime.maxFlyingBlocks(), "two storms' worth of the default 48");

        runtime.beginTick(8, 4, 10);
        assertEquals(8, runtime.tick());
        assertEquals(8, runtime.maxRipsPerTick(), "an admin who lowers the key lowers the level cap with it");
        assertEquals(20, runtime.maxFlyingBlocks());

        runtime.beginTick(9, 0, 0);
        assertEquals(0, runtime.maxRipsPerTick());
        assertEquals(0, runtime.maxFlyingBlocks());
        assertFalse(runtime.tryRip(), "a block budget of zero rips nothing");

        runtime.beginTick(10, -3, -1);
        assertEquals(0, runtime.maxRipsPerTick(), "a negative value counts as zero");
        assertEquals(0, runtime.maxFlyingBlocks());

        runtime.beginTick(11, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, runtime.maxRipsPerTick(), "the product saturates instead of overflowing");
        assertEquals(Integer.MAX_VALUE, runtime.maxFlyingBlocks());
    }

    @Test
    void tryRipGrantsTwiceTheBlockBudgetPerTick() {
        StormRuntime runtime = startedAt(1);

        for (int i = 0; i < 2 * BLOCK_BUDGET_PER_TICK; i++) {
            assertTrue(runtime.tryRip(), "rip " + i + " is within the level budget");
        }
        assertFalse(runtime.tryRip(), "the thirty-third rip of a tick is refused");
        assertFalse(runtime.tryRip());

        runtime.beginTick(2, 3, MAX_FLYING_BLOCKS_PER_STORM);
        for (int i = 0; i < 6; i++) {
            assertTrue(runtime.tryRip());
        }
        assertFalse(runtime.tryRip(), "the budget follows the value of the new tick");
    }

    @Test
    void tryBoltGrantsTwoAndTryBreakEffectOnePerTick() {
        StormRuntime runtime = startedAt(1);

        assertTrue(runtime.tryBolt());
        assertTrue(runtime.tryBolt());
        assertFalse(runtime.tryBolt(), "a third bolt in the same level tick is refused");

        assertTrue(runtime.tryBreakEffect());
        assertFalse(runtime.tryBreakEffect(), "one break effect per level tick, whatever is ripped");

        runtime.beginTick(2, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertTrue(runtime.tryBolt());
        assertTrue(runtime.tryBreakEffect());
    }

    @Test
    void forcedBudgetIsPaidForUse() {
        StormRuntime runtime = startedAt(1);
        assertEquals(256, runtime.forcedLeft());

        runtime.spendForced(0);
        assertEquals(256, runtime.forcedLeft(), "a pass that pushes nobody leaves the budget whole");
        runtime.spendForced(-5);
        assertEquals(256, runtime.forcedLeft(), "a negative count books nothing");

        runtime.spendForced(100);
        assertEquals(156, runtime.forcedLeft());
        runtime.spendForced(1000);
        assertEquals(0, runtime.forcedLeft(), "spending more than is left leaves zero");
        runtime.spendForced(1);
        assertEquals(0, runtime.forcedLeft());

        runtime.beginTick(2, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(256, runtime.forcedLeft(), "the next tick starts with the whole budget");
        runtime.spendForced(Integer.MAX_VALUE);
        assertEquals(0, runtime.forcedLeft(), "a huge count cannot overflow the booking");
    }

    @Test
    void strikeSpacingKeepsBoltsApartInSpaceAndTime() {
        StormRuntime runtime = startedAt(100);
        assertTrue(runtime.strikeAllowed(0.0D, 0.0D), "nothing has struck yet");

        runtime.recordStrike(0.0D, 0.0D);

        assertFalse(runtime.strikeAllowed(0.0D, 0.0D), "no double bolt on one spot");
        assertFalse(runtime.strikeAllowed(32.0D, 0.0D), "32 blocks away is still within the spacing");
        assertFalse(runtime.strikeAllowed(-20.0D, 20.0D), "28.3 blocks away, diagonally");
        assertTrue(runtime.strikeAllowed(32.5D, 0.0D), "just beyond the spacing a bolt may strike at once");
        assertTrue(runtime.strikeAllowed(-23.0D, 23.0D), "32.5 blocks away, diagonally");

        runtime.beginTick(119, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertFalse(runtime.strikeAllowed(0.0D, 0.0D), "19 ticks later the spot is still taken");
        runtime.beginTick(120, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertTrue(runtime.strikeAllowed(0.0D, 0.0D), "20 ticks later it is free again");
    }

    @Test
    void onlyTheLastEightStrikesAreRemembered() {
        StormRuntime runtime = startedAt(50);
        for (int i = 0; i < StormRuntime.REMEMBERED_STRIKES; i++) {
            runtime.recordStrike(i * 1000.0D, 0.0D);
        }
        for (int i = 0; i < StormRuntime.REMEMBERED_STRIKES; i++) {
            assertFalse(runtime.strikeAllowed(i * 1000.0D, 0.0D), "strike " + i + " is remembered");
        }

        runtime.recordStrike(8000.0D, 0.0D);

        assertTrue(runtime.strikeAllowed(0.0D, 0.0D), "the ninth strike pushes the first out of memory");
        assertFalse(runtime.strikeAllowed(1000.0D, 0.0D));
        assertFalse(runtime.strikeAllowed(8000.0D, 0.0D));

        runtime.beginTick(51, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertFalse(runtime.strikeAllowed(1000.0D, 0.0D), "a new tick keeps the remembered strikes");
    }

    @Test
    void aStrikeFromBeforeTheTickCounterWrappedCountsAsOld() {
        StormRuntime runtime = startedAt(Integer.MAX_VALUE);
        runtime.recordStrike(0.0D, 0.0D);
        assertFalse(runtime.strikeAllowed(0.0D, 0.0D));

        runtime.beginTick(1, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);

        assertTrue(runtime.strikeAllowed(0.0D, 0.0D), "a strike that seems to lie in the future blocks nothing");
    }

    @Test
    void flyingBlockCountersNeverGoBelowZero() {
        StormRuntime runtime = new StormRuntime();
        assertEquals(0, runtime.flyingBlocks(5));

        runtime.flyingBlockAdded(5);
        runtime.flyingBlockAdded(5);
        runtime.flyingBlockAdded(5);
        runtime.flyingBlockAdded(6);
        assertEquals(3, runtime.flyingBlocks(5));
        assertEquals(1, runtime.flyingBlocks(6));
        assertEquals(4, runtime.flyingBlocksTotal());

        runtime.flyingBlockRemoved(5);
        assertEquals(2, runtime.flyingBlocks(5));
        assertEquals(3, runtime.flyingBlocksTotal());

        runtime.flyingBlockRemoved(7);
        assertEquals(0, runtime.flyingBlocks(7), "a storm that never threw a block stays at zero");
        assertEquals(3, runtime.flyingBlocksTotal(), "and takes nothing from the total");

        runtime.flyingBlockRemoved(6);
        runtime.flyingBlockRemoved(6);
        assertEquals(0, runtime.flyingBlocks(6), "a block reported gone twice is counted out once");
        assertEquals(2, runtime.flyingBlocksTotal());

        runtime.beginTick(9, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(2, runtime.flyingBlocks(5), "a new tick keeps the counts");
        runtime.forgetStorm(5);
        assertEquals(2, runtime.flyingBlocks(5), "the blocks of a removed storm count until they land");
        assertEquals(2, runtime.flyingBlocksTotal());
    }

    @Test
    void vorticesArePublishedReplacedAndWithdrawn() {
        StormRuntime runtime = startedAt(1);
        runtime.putVortex(vortex(3));
        runtime.putVortex(vortex(1));
        runtime.putVortex(vortex(2));
        Collection<ActiveVortex> view = runtime.vortices();
        assertEquals(List.of(3, 1, 2), stormIds(view), "vortices come in the order they were first published");

        ActiveVortex moved = vortex(1, 50.0D, 60.0D);
        runtime.putVortex(moved);
        assertSame(moved, runtime.vortex(1), "a storm has one vortex: the last one published");
        assertEquals(3, view.size());
        assertEquals(List.of(3, 1, 2), stormIds(view), "replacing keeps the place in the order");
        assertNull(runtime.vortex(9));

        assertThrows(UnsupportedOperationException.class, view::clear, "the view cannot be modified");
        assertThrows(UnsupportedOperationException.class, () -> view.remove(moved));
        assertThrows(NullPointerException.class, () -> runtime.putVortex(null));

        runtime.beginTick(2, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(3, view.size(), "a new tick keeps the vortices until their storms publish again");

        runtime.removeVortex(3);
        runtime.removeVortex(3);
        assertNull(runtime.vortex(3));
        assertEquals(List.of(1, 2), stormIds(view), "the view follows the runtime");

        runtime.forgetStorm(1);
        assertNull(runtime.vortex(1), "forgetting a storm drops its vortex");
        runtime.forgetStorm(42);
        assertEquals(List.of(2), stormIds(runtime.vortices()));
    }

    @Test
    void downburstsAreTheLiveMutableList() {
        StormRuntime runtime = startedAt(5);
        ActiveDownburst downburst = new ActiveDownburst(1.0D, 2.0D, 24.0F, 20.0F, 5, 60);

        runtime.downbursts().add(downburst);

        assertEquals(List.of(downburst), runtime.downbursts());
        assertSame(runtime.downbursts(), runtime.downbursts());
        runtime.beginTick(6, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(1, runtime.downbursts().size(), "a new tick keeps a downburst that is still blowing");

        runtime.downbursts().removeIf(active -> active == downburst);
        assertTrue(runtime.downbursts().isEmpty(), "the convective system prunes the list itself");
    }

    @Test
    void beginTickResetsEveryBudgetAndNothingElse() {
        StormRuntime runtime = startedAt(1);
        while (runtime.tryRip()) {
            assertTrue(runtime.maxRipsPerTick() > 0);
        }
        runtime.tryBolt();
        runtime.tryBolt();
        runtime.tryBreakEffect();
        runtime.spendForced(256);
        runtime.putVortex(vortex(4));
        runtime.flyingBlockAdded(4);
        runtime.recordStrike(10.0D, 10.0D);
        assertFalse(runtime.tryRip());
        assertFalse(runtime.tryBolt());
        assertFalse(runtime.tryBreakEffect());
        assertEquals(0, runtime.forcedLeft());

        runtime.beginTick(2, BLOCK_BUDGET_PER_TICK, MAX_FLYING_BLOCKS_PER_STORM);

        assertEquals(2, runtime.tick());
        assertTrue(runtime.tryRip());
        assertTrue(runtime.tryBolt());
        assertTrue(runtime.tryBreakEffect());
        assertEquals(256, runtime.forcedLeft());
        assertNotNull(runtime.vortex(4));
        assertEquals(1, runtime.flyingBlocks(4));
        assertFalse(runtime.strikeAllowed(10.0D, 10.0D), "one tick later the strike is still remembered");
    }
}
