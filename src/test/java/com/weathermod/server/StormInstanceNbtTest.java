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
 * Round-trips a storm through NBT and checks the life envelope and movement rules. Needs no Minecraft
 * bootstrap: only NBT, resource keys and plain arithmetic are involved.
 */
class StormInstanceNbtTest {
    private static final float EPSILON = 1.0E-5F;

    private static StormInstance sampleStorm() {
        ResourceKey<WeatherType> type = WeatherRegistries.key("rain");
        return new StormInstance(42, type, 123.5D, 160.0D, -77.25D, 215.5F, 3.25F, 0.8F, 600, 2400, 96.0F, 512.0F);
    }

    @Test
    void saveThenLoadRestoresEveryField() {
        StormInstance storm = sampleStorm();
        storm.steer(90.0F, 2.0F);

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
        assertFalse(loaded.isDirty(), "a freshly loaded storm has nothing to sync");
    }

    @Test
    void saveIsStableAcrossASecondRoundTrip() {
        CompoundTag first = sampleStorm().save();
        CompoundTag second = StormInstance.load(first).save();
        assertEquals(first, second);
    }

    @Test
    void loadFallsBackForMissingOrBrokenValues() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", 7);
        tag.putString("Type", "not a valid id!");

        StormInstance loaded = StormInstance.load(tag);

        assertEquals(7, loaded.id());
        assertEquals(WeatherTypes.PLACEHOLDER, loaded.type());
        assertEquals(StormInstance.DEFAULT_CLOUD_BASE, loaded.y());
        assertEquals(96.0F, loaded.radius(), EPSILON);
        assertEquals(512.0F, loaded.interestRadius(), EPSILON);
        assertTrue(loaded.maxAgeTicks() >= 1, "max age is never zero");
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
    void steeredStormIgnoresTheWind() {
        StormInstance storm = new StormInstance(2, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 0, 1000, 64.0F, 512.0F);
        storm.steer(180.0F, 20.0F);
        storm.tick(new WindState(90.0F, 5.0F));

        assertEquals(180.0F, storm.headingDegrees(), EPSILON);
        assertEquals(20.0F, storm.speed(), EPSILON);
        assertEquals(0.0D, storm.x(), 1.0E-9D);
        assertEquals(1.0D, storm.z(), 1.0E-9D, "heading 180 is south, toward +Z");

        storm.releaseSteering();
        storm.tick(new WindState(90.0F, 5.0F));
        assertEquals(90.0F, storm.headingDegrees(), EPSILON);
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

        storm.setIntensity(4.0F);
        assertEquals(1.0F, storm.peakIntensity(), EPSILON);
        assertEquals(1.0F, storm.intensity(), EPSILON);
        assertTrue(storm.isDirty());

        storm.steer(-90.0F, 1000.0F);
        assertEquals(270.0F, storm.headingDegrees(), EPSILON);
        assertEquals(StormInstance.MAX_STEER_SPEED, storm.speed(), EPSILON);
    }
}
