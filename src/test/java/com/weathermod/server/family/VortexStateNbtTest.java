package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.server.StormInstance;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the vortex family's per-storm state through NBT, checks the keys it writes, the heights that are
 * left out while they are unknown or only guessed, the defaults of an empty tag, the state a natural tornado is
 * born with and what happens to values outside their ranges. Needs no Minecraft bootstrap.
 */
class VortexStateNbtTest {
    private static final String KEY_GROUND_Y = "GroundY";
    private static final String KEY_DIG_REF_Y = "DigRefY";
    private static final String KEY_SEEDED = "Seeded";
    private static final String KEY_WOBBLE_PHASE = "WobblePhase";
    private static final String KEY_WOBBLE_PERIOD = "WobblePeriod";
    private static final String KEY_HEIGHT_FRACTION = "HeightFraction";
    private static final String KEY_LAUNCH_COOLDOWN = "LaunchCooldown";
    private static final String KEY_OFF_SURFACE = "OffSurface";
    private static final String KEY_ORPHANED = "Orphaned";
    private static final String KEY_TOUCHDOWN_DELAY = "TouchdownDelay";
    private static final String KEY_TARGET_TIER = "TargetTier";
    private static final String KEY_TIER_STEP = "TierStep";

    /** A state in which every persisted field differs from its default. */
    private static VortexState busyState() {
        VortexState state = new VortexState();
        state.groundY = 71.25D;
        state.digRefY = 69.5D;
        state.seeded = true;
        state.wobblePhase = 2.75F;
        state.wobblePeriodTicks = 347;
        state.heightFraction = 0.8125F;
        state.launchCooldown = 3;
        state.offSurfaceTicks = 42;
        state.orphaned = true;
        state.touchdownDelayTicks = 517;
        state.targetTier = 4;
        state.tierStepTicks = 123;
        return state;
    }

    private static void assertFresh(VortexState state) {
        assertTrue(Double.isNaN(state.groundY), "no ground was sampled");
        assertTrue(Double.isNaN(state.digRefY), "no dig reference is known");
        assertFalse(state.seeded);
        assertEquals(0.0F, state.wobblePhase);
        assertEquals(300, state.wobblePeriodTicks);
        assertEquals(0.5F, state.heightFraction);
        assertEquals(0, state.launchCooldown);
        assertEquals(0, state.offSurfaceTicks);
        assertFalse(state.orphaned);
        assertEquals(0, state.touchdownDelayTicks, "no touchdown delay: the funnel comes down at once");
        assertEquals(-1, state.targetTier, "no target tier: the vortex stays what it is");
        assertEquals(0, state.tierStepTicks);
        assertFalse(state.groundKnown);
        assertFalse(state.groundProvisional);
    }

    @Test
    void aNewStateIsTheOneOfAVortexSpawnedByCommand() {
        assertFresh(new VortexState());
        assertEquals(300, VortexState.DEFAULT_WOBBLE_PERIOD_TICKS);
        assertEquals(200, VortexState.MIN_WOBBLE_PERIOD_TICKS);
        assertEquals(400, VortexState.MAX_WOBBLE_PERIOD_TICKS);
        assertEquals(0.5F, VortexState.DEFAULT_HEIGHT_FRACTION);
        assertEquals(-1, VortexState.NO_TARGET_TIER);
    }

    @Test
    void anEmptyTagLoadsTheDefaults() {
        VortexState loaded = VortexState.load(new CompoundTag());

        assertFresh(loaded);
        assertEquals(new VortexState(), loaded);
        assertEquals(new VortexState().hashCode(), loaded.hashCode());
    }

    @Test
    void saveThenLoadRestoresEveryField() {
        VortexState state = busyState();

        VortexState loaded = VortexState.load(state.save());

        assertEquals(71.25D, loaded.groundY);
        assertEquals(69.5D, loaded.digRefY);
        assertTrue(loaded.seeded);
        assertEquals(2.75F, loaded.wobblePhase);
        assertEquals(347, loaded.wobblePeriodTicks);
        assertEquals(0.8125F, loaded.heightFraction);
        assertEquals(3, loaded.launchCooldown);
        assertEquals(42, loaded.offSurfaceTicks);
        assertTrue(loaded.orphaned);
        assertEquals(517, loaded.touchdownDelayTicks);
        assertEquals(4, loaded.targetTier);
        assertEquals(123, loaded.tierStepTicks);
        assertEquals(state, loaded);
        assertEquals(state.hashCode(), loaded.hashCode());
        assertEquals(state.save(), loaded.save(), "a second round trip writes the same tag");
    }

    @Test
    void saveWritesTheAgreedKeys() {
        CompoundTag tag = busyState().save();

        assertEquals(Set.of(KEY_GROUND_Y, KEY_DIG_REF_Y, KEY_SEEDED, KEY_WOBBLE_PHASE, KEY_WOBBLE_PERIOD,
            KEY_HEIGHT_FRACTION, KEY_LAUNCH_COOLDOWN, KEY_OFF_SURFACE, KEY_ORPHANED, KEY_TOUCHDOWN_DELAY,
            KEY_TARGET_TIER, KEY_TIER_STEP), tag.getAllKeys());
        assertTrue(tag.contains(KEY_GROUND_Y, Tag.TAG_DOUBLE));
        assertEquals(71.25D, tag.getDouble(KEY_GROUND_Y));
        assertTrue(tag.contains(KEY_DIG_REF_Y, Tag.TAG_DOUBLE));
        assertEquals(69.5D, tag.getDouble(KEY_DIG_REF_Y));
        assertTrue(tag.getBoolean(KEY_SEEDED));
        assertEquals(2.75F, tag.getFloat(KEY_WOBBLE_PHASE));
        assertEquals(347, tag.getInt(KEY_WOBBLE_PERIOD));
        assertEquals(0.8125F, tag.getFloat(KEY_HEIGHT_FRACTION));
        assertEquals(3, tag.getInt(KEY_LAUNCH_COOLDOWN));
        assertEquals(42, tag.getInt(KEY_OFF_SURFACE));
        assertTrue(tag.getBoolean(KEY_ORPHANED));
        assertEquals(517, tag.getInt(KEY_TOUCHDOWN_DELAY));
        assertEquals(4, tag.getInt(KEY_TARGET_TIER));
        assertEquals(123, tag.getInt(KEY_TIER_STEP));
    }

    @Test
    void anUnknownGroundAndAnUnknownDigReferenceAreNotWritten() {
        CompoundTag fresh = new VortexState().save();
        assertFalse(fresh.contains(KEY_GROUND_Y), "a ground that was never sampled is not saved");
        assertFalse(fresh.contains(KEY_DIG_REF_Y), "a dig reference that was never sampled is not saved");
        assertFresh(VortexState.load(fresh));

        VortexState onlyGround = new VortexState();
        onlyGround.groundY = 80.0D;
        CompoundTag groundTag = onlyGround.save();
        assertTrue(groundTag.contains(KEY_GROUND_Y));
        assertFalse(groundTag.contains(KEY_DIG_REF_Y));
        assertEquals(80.0D, VortexState.load(groundTag).groundY);
        assertTrue(Double.isNaN(VortexState.load(groundTag).digRefY));

        VortexState onlyReference = new VortexState();
        onlyReference.digRefY = 77.0D;
        CompoundTag referenceTag = onlyReference.save();
        assertFalse(referenceTag.contains(KEY_GROUND_Y));
        assertTrue(referenceTag.contains(KEY_DIG_REF_Y));
        assertEquals(77.0D, VortexState.load(referenceTag).digRefY);
        assertTrue(Double.isNaN(VortexState.load(referenceTag).groundY));
    }

    @Test
    void aGuessedGroundIsNotWritten() {
        VortexState state = busyState();
        state.groundProvisional = true;

        CompoundTag tag = state.save();
        VortexState loaded = VortexState.load(tag);

        assertFalse(tag.contains(KEY_GROUND_Y), "a guess must not come back as a sampled height");
        assertEquals(69.5D, tag.getDouble(KEY_DIG_REF_Y), "the dig reference does not depend on the guess");
        assertTrue(Double.isNaN(loaded.groundY));
        assertFalse(loaded.groundProvisional);
        assertEquals(71.25D, state.groundY, "saving leaves the guess in place for the running game");
        assertEquals(state, loaded, "a guessed ground counts as none");
        assertEquals(state.hashCode(), loaded.hashCode());
        assertNotEquals(busyState(), loaded, "a sampled ground is not the same as none");
    }

    @Test
    void theTransientFieldsAreNeverSaved() {
        VortexState state = busyState();
        state.groundKnown = true;

        CompoundTag tag = state.save();
        VortexState loaded = VortexState.load(tag);

        assertEquals(12, tag.size(), "nothing but the twelve persisted fields");
        assertFalse(loaded.groundKnown, "after a load the ground counts as unknown until it is sampled again");
        assertFalse(loaded.groundProvisional);
        assertEquals(state, loaded, "the transient fields are not compared");
    }

    @Test
    void aNaturalTornadoIsBornWithItsTargetTierAndDelay() {
        VortexState state = VortexState.forNaturalTornado(3, 500);

        assertEquals(3, state.targetTier);
        assertEquals(500, state.touchdownDelayTicks);
        assertTrue(Double.isNaN(state.groundY));
        assertTrue(Double.isNaN(state.digRefY));
        assertFalse(state.seeded, "the funnel values are rolled at the first tick");
        assertEquals(0, state.tierStepTicks);
        assertFalse(state.orphaned);

        CompoundTag tag = state.save();
        assertEquals(3, tag.getInt(KEY_TARGET_TIER));
        assertEquals(500, tag.getInt(KEY_TOUCHDOWN_DELAY));
        VortexState loaded = VortexState.load(tag);
        assertEquals(3, loaded.targetTier);
        assertEquals(500, loaded.touchdownDelayTicks);
        assertEquals(state, loaded);
        assertNotEquals(new VortexState(), loaded);
    }

    @Test
    void aNaturalTornadoKeepsItsValuesInRange() {
        assertEquals(5, VortexState.forNaturalTornado(9, 400).targetTier);
        assertEquals(0, VortexState.forNaturalTornado(-2, 400).targetTier, "a natural tornado always has a target");
        assertEquals(0, VortexState.forNaturalTornado(0, 400).targetTier);
        assertEquals(0, VortexState.forNaturalTornado(2, -5).touchdownDelayTicks);
        assertEquals(600, VortexState.forNaturalTornado(2, 600).touchdownDelayTicks);
    }

    @Test
    void aStormCarriesTheStateOfItsTornadoThroughItsOwnTag() {
        StormInstance storm = new StormInstance(7, WeatherRegistries.key("tornado_ef0"), 10.0D, 160.0D, -20.0D, 45.0F,
            3.0F, 0.6F, 0, 6000, 24.0F, 512.0F);
        storm.setFamilyTag(VortexState.forNaturalTornado(4, 450).save());

        VortexState state = storm.familyState(VortexState.class, VortexState::load);
        assertEquals(4, state.targetTier);
        assertEquals(450, state.touchdownDelayTicks);
        assertSame(state, storm.familyState(VortexState.class, VortexState::load), "the state is loaded once");

        state.groundY = 66.0D;
        state.tierStepTicks = 200;
        VortexState reloaded = StormInstance.load(storm.save()).familyState(VortexState.class, VortexState::load);
        assertEquals(66.0D, reloaded.groundY);
        assertEquals(200, reloaded.tierStepTicks);
        assertEquals(state, reloaded);
    }

    @Test
    void valuesOutsideTheirRangesAreBroughtBackOnLoad() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_WOBBLE_PERIOD, 5);
        tag.putFloat(KEY_HEIGHT_FRACTION, 3.0F);
        tag.putInt(KEY_TARGET_TIER, 9);
        tag.putInt(KEY_LAUNCH_COOLDOWN, -4);
        tag.putInt(KEY_OFF_SURFACE, -1);
        tag.putInt(KEY_TOUCHDOWN_DELAY, -600);
        tag.putInt(KEY_TIER_STEP, -7);
        tag.putFloat(KEY_WOBBLE_PHASE, Float.NaN);
        tag.putDouble(KEY_GROUND_Y, Double.POSITIVE_INFINITY);
        tag.putDouble(KEY_DIG_REF_Y, Double.NaN);

        VortexState low = VortexState.load(tag);

        assertEquals(200, low.wobblePeriodTicks, "a period of zero would stop the base from wandering");
        assertEquals(1.0F, low.heightFraction);
        assertEquals(5, low.targetTier);
        assertEquals(0, low.launchCooldown);
        assertEquals(0, low.offSurfaceTicks);
        assertEquals(0, low.touchdownDelayTicks);
        assertEquals(0, low.tierStepTicks);
        assertEquals(0.0F, low.wobblePhase);
        assertTrue(Double.isNaN(low.groundY), "a height that is not a finite number counts as unknown");
        assertTrue(Double.isNaN(low.digRefY));

        tag.putInt(KEY_WOBBLE_PERIOD, 9999);
        tag.putFloat(KEY_HEIGHT_FRACTION, -1.0F);
        tag.putInt(KEY_TARGET_TIER, -7);
        VortexState high = VortexState.load(tag);
        assertEquals(400, high.wobblePeriodTicks);
        assertEquals(0.0F, high.heightFraction);
        assertEquals(-1, high.targetTier);

        tag.putFloat(KEY_HEIGHT_FRACTION, Float.NaN);
        assertEquals(0.5F, VortexState.load(tag).heightFraction);
    }

    @Test
    void keysOfAnotherTypeAreTakenAsNumbersOrIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat(KEY_GROUND_Y, 70.5F);
        tag.putString(KEY_DIG_REF_Y, "high");
        tag.putString(KEY_TARGET_TIER, "five");
        tag.putString(KEY_WOBBLE_PERIOD, "slow");

        VortexState loaded = VortexState.load(tag);

        assertEquals(70.5D, loaded.groundY, "any numeric tag is a height");
        assertTrue(Double.isNaN(loaded.digRefY));
        assertEquals(-1, loaded.targetTier);
        assertEquals(300, loaded.wobblePeriodTicks);
    }
}
