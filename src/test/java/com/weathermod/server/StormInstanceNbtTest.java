package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

/**
 * Round-trips a storm through NBT and checks the life envelope, movement, heading bias and early-ending rules.
 * Needs no Minecraft bootstrap: only NBT, resource keys and plain arithmetic are involved.
 */
class StormInstanceNbtTest {
    private static final float EPSILON = 1.0E-5F;

    private static StormInstance sampleStorm() {
        ResourceKey<WeatherType> type = WeatherRegistries.key("rain");
        return new StormInstance(42, type, 123.5D, 160.0D, -77.25D, 215.5F, 3.25F, 0.8F, 600, 2400, 96.0F, 512.0F);
    }

    /** A tag with exactly the keys the first milestone wrote, before the director's fields existed. */
    private static CompoundTag firstMilestoneTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", 42);
        tag.putString("Type", "weathermod:rain");
        tag.putDouble("X", 123.5D);
        tag.putDouble("Y", 160.0D);
        tag.putDouble("Z", -77.25D);
        tag.putFloat("Heading", 215.5F);
        tag.putFloat("Speed", 3.25F);
        tag.putFloat("Intensity", 0.8F);
        tag.putFloat("PeakIntensity", 0.8F);
        tag.putInt("Age", 600);
        tag.putInt("MaxAge", 2400);
        tag.putFloat("Radius", 96.0F);
        tag.putFloat("InterestRadius", 512.0F);
        tag.putBoolean("Steered", false);
        return tag;
    }

    @Test
    void saveThenLoadRestoresEveryField() {
        StormInstance storm = sampleStorm();
        storm.steer(90.0F, 2.0F);
        storm.markNatural();
        storm.setTicksUnseen(300);
        storm.setHeadingBias(-15.0F);

        CompoundTag tag = storm.save();
        StormInstance loaded = StormInstance.load(tag);

        assertEquals(storm.id(), loaded.id());
        assertEquals(storm.type(), loaded.type());
        assertEquals(storm.typeId(), loaded.typeId());
        assertEquals(storm.x(), loaded.x());
        assertEquals(storm.y(), loaded.y());
        assertEquals(storm.z(), loaded.z());
        assertEquals(storm.headingDegrees(), loaded.headingDegrees(), EPSILON);
        assertEquals(storm.speed(), loaded.speed(), EPSILON);
        assertEquals(storm.intensity(), loaded.intensity(), EPSILON);
        assertEquals(storm.peakIntensity(), loaded.peakIntensity(), EPSILON);
        assertEquals(storm.ageTicks(), loaded.ageTicks());
        assertEquals(storm.maxAgeTicks(), loaded.maxAgeTicks());
        assertEquals(storm.radius(), loaded.radius(), EPSILON);
        assertEquals(storm.interestRadius(), loaded.interestRadius(), EPSILON);
        assertEquals(storm.isSteered(), loaded.isSteered());
        assertTrue(loaded.isNatural());
        assertEquals(300, loaded.ticksUnseen());
        assertEquals(-15.0F, loaded.headingBiasDegrees(), EPSILON);
        assertFalse(loaded.isDirty(), "a freshly loaded storm has nothing to sync");
    }

    @Test
    void saveWritesTheNewKeys() {
        StormInstance storm = sampleStorm();
        storm.markNatural();
        storm.setTicksUnseen(1200);
        storm.setHeadingBias(20.0F);

        CompoundTag tag = storm.save();

        assertTrue(tag.getBoolean("Natural"));
        assertEquals(1200, tag.getInt("TicksUnseen"));
        assertEquals(20.0F, tag.getFloat("HeadingBias"), EPSILON);
    }

    @Test
    void saveIsStableAcrossASecondRoundTrip() {
        CompoundTag first = sampleStorm().save();
        CompoundTag second = StormInstance.load(first).save();
        assertEquals(first, second);
    }

    @Test
    void firstMilestoneTagLoadsWithDefaultsForTheNewFields() {
        CompoundTag tag = firstMilestoneTag();

        StormInstance loaded = StormInstance.load(tag);

        assertEquals(42, loaded.id());
        assertEquals(WeatherTypes.RAIN, loaded.type());
        assertEquals(600, loaded.ageTicks());
        assertEquals(2400, loaded.maxAgeTicks());
        assertEquals(0.8F, loaded.intensity(), EPSILON);
        assertFalse(loaded.isNatural(), "storms saved before the director existed are not natural");
        assertEquals(0, loaded.ticksUnseen());
        assertEquals(0.0F, loaded.headingBiasDegrees(), EPSILON);
        assertFalse(loaded.isSteered());
        assertFalse(loaded.isDirty());

        CompoundTag resaved = loaded.save();
        assertFalse(resaved.getBoolean("Natural"));
        assertEquals(0, resaved.getInt("TicksUnseen"));
        assertEquals(0.0F, resaved.getFloat("HeadingBias"), EPSILON);
    }

    @Test
    void loadFallsBackForMissingOrBrokenValues() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", 7);
        tag.putString("Type", "not a valid id!");
        tag.putInt("TicksUnseen", -50);
        tag.putFloat("HeadingBias", 270.0F);

        StormInstance loaded = StormInstance.load(tag);

        assertEquals(7, loaded.id());
        assertEquals(WeatherTypes.PLACEHOLDER, loaded.type());
        assertEquals(StormInstance.DEFAULT_CLOUD_BASE, loaded.y());
        assertEquals(96.0F, loaded.radius(), EPSILON);
        assertEquals(512.0F, loaded.interestRadius(), EPSILON);
        assertTrue(loaded.maxAgeTicks() >= 1, "max age is never zero");
        assertEquals(0, loaded.ticksUnseen(), "negative unseen ticks are clamped to zero");
        assertEquals(-90.0F, loaded.headingBiasDegrees(), EPSILON, "a stored bias is wrapped on load");
    }

    @Test
    void lifeEnvelopeRampsUpHoldsAndRampsDown() {
        assertEquals(0.0F, StormInstance.lifeEnvelope(0, 1000), EPSILON);
        assertEquals(0.5F, StormInstance.lifeEnvelope(50, 1000), EPSILON);
        assertEquals(1.0F, StormInstance.lifeEnvelope(100, 1000), EPSILON);
        assertEquals(1.0F, StormInstance.lifeEnvelope(500, 1000), EPSILON);
        assertEquals(1.0F, StormInstance.lifeEnvelope(800, 1000), EPSILON);
        assertEquals(0.5F, StormInstance.lifeEnvelope(900, 1000), EPSILON);
        assertEquals(0.0F, StormInstance.lifeEnvelope(1000, 1000), EPSILON);
        assertEquals(0.0F, StormInstance.lifeEnvelope(5000, 1000), EPSILON);
        assertEquals(1.0F, StormInstance.lifeEnvelope(3, 0), EPSILON);
    }

    @Test
    void tickFollowsWindAgesAndExpires() {
        StormInstance storm = new StormInstance(1, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 0, 100, 64.0F, 512.0F);
        WindState wind = new WindState(90.0F, 10.0F);

        storm.clearDirty();
        storm.tick(wind);

        assertTrue(storm.isDirty());
        assertEquals(90.0F, storm.headingDegrees(), EPSILON);
        assertEquals(10.0F, storm.speed(), EPSILON);
        assertEquals(0.5D, storm.x(), 1.0E-9D, "10 blocks per second is half a block per tick along +X");
        assertEquals(0.0D, storm.z(), 1.0E-9D);
        assertEquals(1, storm.ageTicks());
        assertEquals(StormInstance.lifeEnvelope(1, 100), storm.intensity(), EPSILON);

        for (int i = 1; i < 100; i++) {
            assertFalse(storm.isExpired());
            storm.tick(wind);
        }
        assertTrue(storm.isExpired());
        assertEquals(0.0F, storm.intensity(), EPSILON);
    }

    @Test
    void headingBiasTurnsAnUnsteeredStormAwayFromTheWind() {
        StormInstance storm = new StormInstance(4, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 0, 1000, 64.0F, 512.0F);
        storm.setHeadingBias(20.0F);

        storm.tick(new WindState(90.0F, 10.0F));
        assertEquals(110.0F, storm.headingDegrees(), EPSILON, "the bias is added to the wind direction");
        assertEquals(10.0F, storm.speed(), EPSILON, "the speed is the wind's, bias or not");

        storm.setHeadingBias(-100.0F);
        storm.tick(new WindState(10.0F, 10.0F));
        assertEquals(270.0F, storm.headingDegrees(), EPSILON, "the biased heading wraps into [0, 360)");

        storm.setHeadingBias(0.0F);
        storm.tick(new WindState(10.0F, 10.0F));
        assertEquals(10.0F, storm.headingDegrees(), EPSILON, "a zero bias follows the wind exactly");
    }

    @Test
    void headingBiasWrapsIntoTheSignedHalfTurn() {
        StormInstance storm = sampleStorm();

        storm.setHeadingBias(190.0F);
        assertEquals(-170.0F, storm.headingBiasDegrees(), EPSILON);

        storm.setHeadingBias(180.0F);
        assertEquals(-180.0F, storm.headingBiasDegrees(), EPSILON, "180 is the open end of [-180, 180)");

        storm.setHeadingBias(-180.0F);
        assertEquals(-180.0F, storm.headingBiasDegrees(), EPSILON);

        storm.setHeadingBias(-190.0F);
        assertEquals(170.0F, storm.headingBiasDegrees(), EPSILON);

        storm.setHeadingBias(540.0F);
        assertEquals(-180.0F, storm.headingBiasDegrees(), EPSILON);

        storm.setHeadingBias(Float.NaN);
        assertEquals(0.0F, storm.headingBiasDegrees(), EPSILON, "a non-finite bias is dropped");

        storm.clearDirty();
        storm.setHeadingBias(0.0F);
        assertFalse(storm.isDirty(), "setting the bias it already has changes nothing");
        storm.setHeadingBias(5.0F);
        assertTrue(storm.isDirty(), "a changed bias is synced");
    }

    @Test
    void ticksUnseenNeverGoNegative() {
        StormInstance storm = sampleStorm();
        assertEquals(0, storm.ticksUnseen(), "a new storm has never been unseen");

        storm.setTicksUnseen(100);
        assertEquals(100, storm.ticksUnseen());
        storm.setTicksUnseen(-1);
        assertEquals(0, storm.ticksUnseen());
    }

    @Test
    void naturalIsOffUntilMarked() {
        StormInstance storm = sampleStorm();
        assertFalse(storm.isNatural());
        storm.markNatural();
        assertTrue(storm.isNatural());
        storm.markNatural();
        assertTrue(storm.isNatural(), "marking twice keeps the flag");
    }

    @Test
    void endSoonStartsTheRampDownNowAndKeepsTheIntensity() {
        StormInstance storm = sampleStorm();
        storm.clearDirty();
        assertEquals(480, storm.rampDownTicks());

        storm.endSoon();

        assertEquals(750, storm.maxAgeTicks(), "age 600 sits exactly at the start of the ramp-down of a 750 tick life");
        assertEquals(150, storm.remainingTicks());
        assertTrue(storm.remainingTicks() <= storm.rampDownTicks(), "no storm outlives its ramp-down");
        assertEquals(0.8F, storm.intensity(), EPSILON, "the intensity does not jump");
        assertEquals(0.8F, storm.peakIntensity(), EPSILON, "a storm on its plateau keeps its peak");
        assertTrue(storm.isDirty());

        storm.tick(new WindState(0.0F, 0.0F));
        assertEquals(0.8F * StormInstance.lifeEnvelope(601, storm.maxAgeTicks()), storm.intensity(), EPSILON,
            "the next tick continues the envelope from the new maximum age");
        assertTrue(storm.intensity() < 0.8F, "and the intensity starts falling at once");
    }

    @Test
    void endSoonDuringRampUpRescalesThePeakSoTheIntensityIsContinuous() {
        StormInstance storm = new StormInstance(5, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 50, 1000, 64.0F, 512.0F);
        assertEquals(0.5F, storm.intensity(), EPSILON, "half way up the ramp");

        storm.endSoon();

        assertEquals(50 + StormInstance.MIN_FADE_TICKS, storm.maxAgeTicks(),
            "a very young storm is still given the minimum fade");
        assertEquals(0.5F, storm.intensity(), EPSILON);
        assertEquals(0.5F, storm.peakIntensity(), EPSILON,
            "age 50 of 150 is on the plateau, so the peak must equal the current intensity");

        storm.tick(new WindState(0.0F, 0.0F));
        assertEquals(0.5F, storm.intensity(), EPSILON, "no jump on the following tick either");
    }

    @Test
    void endSoonLeavesAStormThatIsAlreadyEndingAlone() {
        StormInstance storm = new StormInstance(6, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 2000, 2400, 64.0F, 512.0F);
        storm.clearDirty();

        storm.endSoon();

        assertEquals(2400, storm.maxAgeTicks(), "fewer than one ramp-down length left: nothing to shorten");
        assertEquals(2000, storm.ageTicks());
        assertFalse(storm.isDirty());

        StormInstance shortened = sampleStorm();
        shortened.endSoon();
        int afterFirst = shortened.maxAgeTicks();
        shortened.endSoon();
        assertTrue(shortened.maxAgeTicks() <= afterFirst, "a second call never lengthens the storm");
        assertTrue(shortened.maxAgeTicks() > shortened.ageTicks(), "and never expires it outright");
    }

    @Test
    void endSoonOnANewbornStormKeepsItsPeak() {
        StormInstance storm = new StormInstance(8, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 0.9F, 0, 1000, 64.0F, 512.0F);
        assertEquals(0.0F, storm.intensity(), EPSILON);

        storm.endSoon();

        assertEquals(StormInstance.MIN_FADE_TICKS, storm.maxAgeTicks());
        assertEquals(0.9F, storm.peakIntensity(), EPSILON, "a zero envelope cannot rescale the peak, so it is kept");
        assertEquals(0.0F, storm.intensity(), EPSILON);
    }

    @Test
    void steeredStormIgnoresTheWind() {
        StormInstance storm = new StormInstance(2, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 0, 1000, 64.0F, 512.0F);
        storm.setHeadingBias(45.0F);
        storm.steer(180.0F, 20.0F);
        storm.tick(new WindState(90.0F, 5.0F));

        assertEquals(180.0F, storm.headingDegrees(), EPSILON, "steering ignores wind and bias alike");
        assertEquals(20.0F, storm.speed(), EPSILON);
        assertEquals(0.0D, storm.x(), 1.0E-9D);
        assertEquals(1.0D, storm.z(), 1.0E-9D, "heading 180 is south, toward +Z");

        storm.releaseSteering();
        storm.tick(new WindState(90.0F, 5.0F));
        assertEquals(135.0F, storm.headingDegrees(), EPSILON, "released, the bias applies again");
        assertEquals(5.0F, storm.speed(), EPSILON);
    }

    @Test
    void interestRadiusDecidesWhoReceivesTheStorm() {
        StormInstance storm = sampleStorm();
        assertTrue(storm.isWithinInterest(storm.x() + 512.0D, storm.z()));
        assertFalse(storm.isWithinInterest(storm.x() + 512.5D, storm.z()));
        assertEquals(500.0D, storm.distanceTo(storm.x() + 300.0D, storm.z() + 400.0D), 1.0E-9D);
    }

    @Test
    void settersClampAndMarkDirty() {
        StormInstance storm = sampleStorm();
        storm.clearDirty();

        storm.setPeakIntensity(4.0F);
        assertEquals(1.0F, storm.peakIntensity(), EPSILON);
        assertEquals(1.0F, storm.intensity(), EPSILON);
        assertTrue(storm.isDirty());

        storm.steer(-90.0F, 1000.0F);
        assertEquals(270.0F, storm.headingDegrees(), EPSILON);
        assertEquals(StormInstance.MAX_STEER_SPEED, storm.speed(), EPSILON);
    }

    @Test
    void peakIntensityIsShapedByTheLifeEnvelope() {
        StormInstance storm = new StormInstance(3, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 0.2F, 0, 2400, 64.0F, 512.0F);

        storm.setPeakIntensity(0.8F);

        assertEquals(0.8F, storm.peakIntensity(), EPSILON);
        assertEquals(0.0F, storm.intensity(), EPSILON, "a newborn storm starts at zero however high its peak");

        storm.tick(new WindState(0.0F, 0.0F));
        assertEquals(0.8F * StormInstance.lifeEnvelope(1, 2400), storm.intensity(), EPSILON,
            "the first tick continues the same envelope instead of jumping");
    }
}
