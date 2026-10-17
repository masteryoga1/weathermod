package com.weathermod.compat.sereneseasons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.server.climate.SeasonInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the season name to climate offset mapping the Serene Seasons provider relies on, without that mod. */
class SereneSeasonOffsetsTest {
    private static final float EPSILON = 1.0E-6F;

    /** Every Serene Seasons sub-season in cycle order, as {@code Season.SubSeason#getSerializedName()} spells them. */
    private static final List<String> SUB_SEASONS = List.of(
        "early_spring", "mid_spring", "late_spring",
        "early_summer", "mid_summer", "late_summer",
        "early_autumn", "mid_autumn", "late_autumn",
        "early_winter", "mid_winter", "late_winter");

    /** Every Serene Seasons tropical season, lower-cased like the provider does. */
    private static final List<String> TROPICAL_SEASONS = List.of(
        "early_dry", "mid_dry", "late_dry", "early_wet", "mid_wet", "late_wet");

    @Test
    void temperatureOffsetsFollowTheDesignTable() {
        Map<String, Float> expected = Map.ofEntries(
            Map.entry("early_spring", -0.10F),
            Map.entry("mid_spring", 0.0F),
            Map.entry("late_spring", 0.10F),
            Map.entry("early_summer", 0.20F),
            Map.entry("mid_summer", 0.30F),
            Map.entry("late_summer", 0.20F),
            Map.entry("early_autumn", 0.10F),
            Map.entry("mid_autumn", 0.0F),
            Map.entry("late_autumn", -0.10F),
            Map.entry("early_winter", -0.25F),
            Map.entry("mid_winter", -0.35F),
            Map.entry("late_winter", -0.25F));
        assertEquals(SUB_SEASONS.size(), expected.size());
        for (String name : SUB_SEASONS) {
            assertEquals(expected.get(name), SereneSeasonOffsets.temperatureOffset(name), EPSILON, name);
        }
    }

    @Test
    void temperatureRampIsSymmetricAroundTheMidSeasons() {
        assertEquals(SereneSeasonOffsets.temperatureOffset("early_summer"),
            SereneSeasonOffsets.temperatureOffset("late_summer"), EPSILON);
        assertEquals(SereneSeasonOffsets.temperatureOffset("early_winter"),
            SereneSeasonOffsets.temperatureOffset("late_winter"), EPSILON);
        assertEquals(SereneSeasonOffsets.temperatureOffset("late_spring"),
            SereneSeasonOffsets.temperatureOffset("early_autumn"), EPSILON);
        assertEquals(SereneSeasonOffsets.temperatureOffset("early_spring"),
            SereneSeasonOffsets.temperatureOffset("late_autumn"), EPSILON);
        assertEquals(0.0F, SereneSeasonOffsets.temperatureOffset("mid_spring"), EPSILON);
        assertEquals(0.0F, SereneSeasonOffsets.temperatureOffset("mid_autumn"), EPSILON);
    }

    @Test
    void midSummerIsWarmestAndMidWinterColdest() {
        float warmest = Float.NEGATIVE_INFINITY;
        float coldest = Float.POSITIVE_INFINITY;
        String warmestName = null;
        String coldestName = null;
        for (String name : SUB_SEASONS) {
            float offset = SereneSeasonOffsets.temperatureOffset(name);
            if (offset > warmest) {
                warmest = offset;
                warmestName = name;
            }
            if (offset < coldest) {
                coldest = offset;
                coldestName = name;
            }
        }
        assertEquals("mid_summer", warmestName);
        assertEquals("mid_winter", coldestName);
        assertEquals(0.30F, warmest, EPSILON);
        assertEquals(-0.35F, coldest, EPSILON);
    }

    @Test
    void temperatureOffsetsAreContinuousAroundTheCycle() {
        // Neighbouring sub-seasons never jump by more than the largest designed step of 0.15.
        for (int i = 0; i < SUB_SEASONS.size(); i++) {
            float here = SereneSeasonOffsets.temperatureOffset(SUB_SEASONS.get(i));
            float next = SereneSeasonOffsets.temperatureOffset(SUB_SEASONS.get((i + 1) % SUB_SEASONS.size()));
            assertTrue(Math.abs(next - here) <= 0.15F + EPSILON,
                SUB_SEASONS.get(i) + " -> " + SUB_SEASONS.get((i + 1) % SUB_SEASONS.size()));
        }
    }

    @Test
    void humidityOffsetsDependOnTheSeasonPartOnly() {
        for (String prefix : List.of("early_", "mid_", "late_")) {
            assertEquals(0.10F, SereneSeasonOffsets.humidityOffset(prefix + "spring"), EPSILON, prefix + "spring");
            assertEquals(-0.05F, SereneSeasonOffsets.humidityOffset(prefix + "summer"), EPSILON, prefix + "summer");
            assertEquals(0.05F, SereneSeasonOffsets.humidityOffset(prefix + "autumn"), EPSILON, prefix + "autumn");
            assertEquals(-0.10F, SereneSeasonOffsets.humidityOffset(prefix + "winter"), EPSILON, prefix + "winter");
        }
        assertEquals(SereneSeasonOffsets.SPRING_HUMIDITY, SereneSeasonOffsets.humidityOffset("spring"), EPSILON);
        assertEquals(SereneSeasonOffsets.SUMMER_HUMIDITY, SereneSeasonOffsets.humidityOffset("summer"), EPSILON);
        assertEquals(SereneSeasonOffsets.AUTUMN_HUMIDITY, SereneSeasonOffsets.humidityOffset("autumn"), EPSILON);
        assertEquals(SereneSeasonOffsets.WINTER_HUMIDITY, SereneSeasonOffsets.humidityOffset("winter"), EPSILON);
    }

    @Test
    void tropicalHumidityIsWetOrDry() {
        for (String name : TROPICAL_SEASONS) {
            float expected = name.endsWith("_wet") ? 0.25F : -0.25F;
            assertEquals(expected, SereneSeasonOffsets.tropicalHumidityOffset(name), EPSILON, name);
            assertTrue(SereneSeasonOffsets.isTropicalSeason(name), name);
        }
        assertEquals(SereneSeasonOffsets.WET_HUMIDITY, SereneSeasonOffsets.tropicalHumidityOffset("wet"), EPSILON);
        assertEquals(SereneSeasonOffsets.DRY_HUMIDITY, SereneSeasonOffsets.tropicalHumidityOffset("dry"), EPSILON);
        assertEquals(-SereneSeasonOffsets.WET_HUMIDITY, SereneSeasonOffsets.DRY_HUMIDITY, EPSILON);
    }

    @Test
    void temperateAndTropicalNamesDoNotCrossOver() {
        for (String name : TROPICAL_SEASONS) {
            assertEquals(0.0F, SereneSeasonOffsets.temperatureOffset(name), EPSILON, name);
            assertEquals(0.0F, SereneSeasonOffsets.humidityOffset(name), EPSILON, name);
            assertFalse(SereneSeasonOffsets.isSubSeason(name), name);
        }
        for (String name : SUB_SEASONS) {
            assertEquals(0.0F, SereneSeasonOffsets.tropicalHumidityOffset(name), EPSILON, name);
            assertTrue(SereneSeasonOffsets.isSubSeason(name), name);
            assertFalse(SereneSeasonOffsets.isTropicalSeason(name), name);
        }
    }

    @Test
    void unknownAndNullNamesGiveNoOffset() {
        for (String name : new String[] {null, "", "   ", "monsoon", "mid_", "_", "mid_monsoon", "early_spring_x"}) {
            assertEquals(0.0F, SereneSeasonOffsets.temperatureOffset(name), EPSILON, String.valueOf(name));
            assertEquals(0.0F, SereneSeasonOffsets.humidityOffset(name), EPSILON, String.valueOf(name));
            assertEquals(0.0F, SereneSeasonOffsets.tropicalHumidityOffset(name), EPSILON, String.valueOf(name));
            assertFalse(SereneSeasonOffsets.isSubSeason(name), String.valueOf(name));
            assertFalse(SereneSeasonOffsets.isTropicalSeason(name), String.valueOf(name));
        }
    }

    @Test
    void namesAreMatchedIgnoringCaseAndBlanks() {
        assertEquals(-0.35F, SereneSeasonOffsets.temperatureOffset("MID_WINTER"), EPSILON);
        assertEquals(-0.35F, SereneSeasonOffsets.temperatureOffset("  Mid_Winter "), EPSILON);
        assertEquals(-0.10F, SereneSeasonOffsets.humidityOffset("Late_WINTER"), EPSILON);
        assertEquals(0.25F, SereneSeasonOffsets.tropicalHumidityOffset("MID_WET"), EPSILON);
        assertTrue(SereneSeasonOffsets.isSubSeason(" EARLY_SPRING"));
    }

    @Test
    void progressWrapsTheCycleTicksIntoTheUnitInterval() {
        assertEquals(0.0F, SereneSeasonOffsets.progress(0, 1000), EPSILON);
        assertEquals(0.25F, SereneSeasonOffsets.progress(250, 1000), EPSILON);
        assertEquals(0.999F, SereneSeasonOffsets.progress(999, 1000), EPSILON);
        assertEquals(0.0F, SereneSeasonOffsets.progress(1000, 1000), EPSILON, "a full cycle wraps to the start");
        assertEquals(0.5F, SereneSeasonOffsets.progress(1500, 1000), EPSILON);
        assertEquals(0.75F, SereneSeasonOffsets.progress(-250, 1000), EPSILON, "negative ticks wrap backwards");
        for (int ticks = -3000; ticks <= 3000; ticks += 7) {
            float progress = SereneSeasonOffsets.progress(ticks, 1000);
            assertTrue(progress >= 0.0F && progress < 1.0F, "ticks " + ticks);
        }
    }

    @Test
    void progressWithoutACycleIsZero() {
        assertEquals(0.0F, SereneSeasonOffsets.progress(123, 0), EPSILON);
        assertEquals(0.0F, SereneSeasonOffsets.progress(123, -5), EPSILON);
    }

    @Test
    void standardSeasonCarriesNameAndOffsets() {
        SeasonInfo winter = SereneSeasonOffsets.standardSeason("mid_winter", 0.875F);
        assertEquals("mid_winter", winter.name());
        assertEquals(-0.35F, winter.temperatureOffset(), EPSILON);
        assertEquals(-0.10F, winter.humidityOffset(), EPSILON);
        assertEquals(0.875F, winter.progress(), EPSILON);
        assertFalse(winter.isNone());

        SeasonInfo summer = SereneSeasonOffsets.standardSeason(" EARLY_SUMMER ", 0.3F);
        assertEquals("early_summer", summer.name(), "names are normalized");
        assertEquals(0.20F, summer.temperatureOffset(), EPSILON);
        assertEquals(-0.05F, summer.humidityOffset(), EPSILON);
    }

    @Test
    void tropicalSeasonChangesHumidityOnly() {
        SeasonInfo wet = SereneSeasonOffsets.tropicalSeason("mid_wet", 0.6F);
        assertEquals("mid_wet", wet.name());
        assertEquals(0.0F, wet.temperatureOffset(), EPSILON);
        assertEquals(0.25F, wet.humidityOffset(), EPSILON);
        assertEquals(0.6F, wet.progress(), EPSILON);

        SeasonInfo dry = SereneSeasonOffsets.tropicalSeason("LATE_DRY", 0.1F);
        assertEquals("late_dry", dry.name());
        assertEquals(0.0F, dry.temperatureOffset(), EPSILON);
        assertEquals(-0.25F, dry.humidityOffset(), EPSILON);
    }

    @Test
    void blankNamesGiveNoSeason() {
        assertSame(SeasonInfo.NONE, SereneSeasonOffsets.standardSeason(null, 0.5F));
        assertSame(SeasonInfo.NONE, SereneSeasonOffsets.standardSeason("  ", 0.5F));
        assertSame(SeasonInfo.NONE, SereneSeasonOffsets.tropicalSeason(null, 0.5F));
        assertSame(SeasonInfo.NONE, SereneSeasonOffsets.tropicalSeason("", 0.5F));
    }

    @Test
    void unknownNamesStillMakeASeasonWithoutOffsets() {
        SeasonInfo odd = SereneSeasonOffsets.standardSeason("monsoon", 0.2F);
        assertEquals("monsoon", odd.name());
        assertEquals(0.0F, odd.temperatureOffset(), EPSILON);
        assertEquals(0.0F, odd.humidityOffset(), EPSILON);
        assertFalse(odd.isNone());
    }

    @Test
    void seasonProgressIsClampedIntoTheUnitInterval() {
        assertEquals(0.0F, SereneSeasonOffsets.standardSeason("mid_spring", -0.5F).progress(), EPSILON);
        assertEquals(0.0F, SereneSeasonOffsets.standardSeason("mid_spring", Float.NaN).progress(), EPSILON);
        assertTrue(SereneSeasonOffsets.standardSeason("mid_spring", 1.0F).progress() < 1.0F);
        assertTrue(SereneSeasonOffsets.tropicalSeason("mid_wet", 7.0F).progress() < 1.0F);
        assertEquals(0.5F, SereneSeasonOffsets.tropicalSeason("mid_wet", 0.5F).progress(), EPSILON);
    }
}
