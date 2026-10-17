package com.weathermod.server.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the day-count seasons: quarter names, the offsets at the season centres, smoothness over a year and the
 * disabled state. Needs no Minecraft bootstrap.
 */
class BuiltInSeasonProviderTest {
    private static final float EPSILON = 1.0E-4F;
    private static final int YEAR_DAYS = 28;
    private static final long YEAR_TICKS = YEAR_DAYS * BuiltInSeasonProvider.TICKS_PER_DAY;

    private static long atProgress(double progress) {
        return Math.round(progress * YEAR_TICKS);
    }

    @Test
    void nonPositiveYearLengthGivesNoSeason() {
        assertSame(SeasonInfo.NONE, BuiltInSeasonProvider.seasonAt(0L, 0));
        assertSame(SeasonInfo.NONE, BuiltInSeasonProvider.seasonAt(123456L, -5));
        assertTrue(BuiltInSeasonProvider.seasonAt(999L, 0).isNone());

        BuiltInSeasonProvider disabled = new BuiltInSeasonProvider(0);
        assertFalse(disabled.isEnabled());
        assertEquals(SeasonProvider.ID_NONE, disabled.id());
        assertEquals(0, disabled.yearLengthDays());
    }

    @Test
    void enabledProviderReportsTheBuiltInId() {
        BuiltInSeasonProvider provider = new BuiltInSeasonProvider(YEAR_DAYS);
        assertTrue(provider.isEnabled());
        assertEquals(SeasonProvider.ID_BUILTIN, provider.id());
        assertEquals(YEAR_DAYS, provider.yearLengthDays());
    }

    @Test
    void progressRunsThroughTheYearAndWraps() {
        assertEquals(0.0F, BuiltInSeasonProvider.seasonAt(0L, YEAR_DAYS).progress(), EPSILON);
        assertEquals(0.25F, BuiltInSeasonProvider.seasonAt(7L * 24000L, YEAR_DAYS).progress(), EPSILON, "day 7 of 28 is a quarter in");
        assertEquals(0.5F, BuiltInSeasonProvider.seasonAt(14L * 24000L, YEAR_DAYS).progress(), EPSILON);
        assertEquals(0.0F, BuiltInSeasonProvider.seasonAt(YEAR_TICKS, YEAR_DAYS).progress(), EPSILON, "a full year wraps to zero");
        assertEquals(0.25F, BuiltInSeasonProvider.seasonAt(YEAR_TICKS + 7L * 24000L, YEAR_DAYS).progress(), EPSILON);
        assertEquals(0.75F, BuiltInSeasonProvider.seasonAt(-7L * 24000L, YEAR_DAYS).progress(), EPSILON, "negative times wrap backwards");
        assertEquals(0.25F + 0.5F / YEAR_DAYS, BuiltInSeasonProvider.seasonAt(7L * 24000L + 12000L, YEAR_DAYS).progress(), EPSILON,
            "progress is continuous within a day");
    }

    @Test
    void namesFollowTheQuarters() {
        assertEquals("spring", BuiltInSeasonProvider.seasonAt(atProgress(0.0), YEAR_DAYS).name());
        assertEquals("spring", BuiltInSeasonProvider.seasonAt(atProgress(0.2), YEAR_DAYS).name());
        assertEquals("summer", BuiltInSeasonProvider.seasonAt(atProgress(0.25), YEAR_DAYS).name());
        assertEquals("summer", BuiltInSeasonProvider.seasonAt(atProgress(0.4), YEAR_DAYS).name());
        assertEquals("autumn", BuiltInSeasonProvider.seasonAt(atProgress(0.5), YEAR_DAYS).name());
        assertEquals("autumn", BuiltInSeasonProvider.seasonAt(atProgress(0.7), YEAR_DAYS).name());
        assertEquals("winter", BuiltInSeasonProvider.seasonAt(atProgress(0.75), YEAR_DAYS).name());
        assertEquals("winter", BuiltInSeasonProvider.seasonAt(YEAR_TICKS - 1L, YEAR_DAYS).name());

        assertEquals("spring", BuiltInSeasonProvider.seasonName(0.1F));
        assertEquals("winter", BuiltInSeasonProvider.seasonName(-0.1F), "progress wraps");
        assertEquals("spring", BuiltInSeasonProvider.seasonName(1.0F), "a full turn is the start again");
    }

    @Test
    void temperatureOffsetHitsTheSeasonCentres() {
        assertEquals(0.0F, BuiltInSeasonProvider.seasonAt(atProgress(0.125), YEAR_DAYS).temperatureOffset(), EPSILON, "spring");
        assertEquals(0.30F, BuiltInSeasonProvider.seasonAt(atProgress(0.375), YEAR_DAYS).temperatureOffset(), EPSILON, "summer");
        assertEquals(0.0F, BuiltInSeasonProvider.seasonAt(atProgress(0.625), YEAR_DAYS).temperatureOffset(), EPSILON, "autumn");
        assertEquals(-0.35F, BuiltInSeasonProvider.seasonAt(atProgress(0.875), YEAR_DAYS).temperatureOffset(), EPSILON, "winter");

        assertEquals(0.15F, BuiltInSeasonProvider.temperatureOffset(0.25F), EPSILON, "halfway from spring to summer is the mean of both");
        assertEquals(-0.175F, BuiltInSeasonProvider.temperatureOffset(0.0F), EPSILON, "new year is halfway from winter to spring");
        assertEquals(-0.175F, BuiltInSeasonProvider.temperatureOffset(1.0F), EPSILON);
    }

    @Test
    void humidityOffsetHitsTheSeasonCentres() {
        assertEquals(0.10F, BuiltInSeasonProvider.seasonAt(atProgress(0.125), YEAR_DAYS).humidityOffset(), EPSILON, "spring");
        assertEquals(-0.05F, BuiltInSeasonProvider.seasonAt(atProgress(0.375), YEAR_DAYS).humidityOffset(), EPSILON, "summer");
        assertEquals(0.05F, BuiltInSeasonProvider.seasonAt(atProgress(0.625), YEAR_DAYS).humidityOffset(), EPSILON, "autumn");
        assertEquals(-0.10F, BuiltInSeasonProvider.seasonAt(atProgress(0.875), YEAR_DAYS).humidityOffset(), EPSILON, "winter");
        assertEquals(0.0F, BuiltInSeasonProvider.humidityOffset(0.0F), EPSILON, "new year is halfway from winter to spring");
    }

    @Test
    void offsetsAreSmoothAndBoundedOverAWholeYear() {
        float previousTemperature = BuiltInSeasonProvider.seasonAt(-100L, YEAR_DAYS).temperatureOffset();
        float previousHumidity = BuiltInSeasonProvider.seasonAt(-100L, YEAR_DAYS).humidityOffset();
        float previousProgress = -1.0F;

        for (long tick = 0; tick <= YEAR_TICKS; tick += 100L) {
            SeasonInfo season = BuiltInSeasonProvider.seasonAt(tick, YEAR_DAYS);

            assertTrue(season.progress() >= 0.0F && season.progress() < 1.0F, "progress out of range at " + tick);
            assertTrue(season.temperatureOffset() >= -0.35F - EPSILON && season.temperatureOffset() <= 0.30F + EPSILON,
                "temperature offset out of range at " + tick + ": " + season.temperatureOffset());
            assertTrue(season.humidityOffset() >= -0.10F - EPSILON && season.humidityOffset() <= 0.10F + EPSILON,
                "humidity offset out of range at " + tick + ": " + season.humidityOffset());

            assertTrue(Math.abs(season.temperatureOffset() - previousTemperature) < 0.002F,
                "temperature offset jumped at " + tick + ": " + previousTemperature + " -> " + season.temperatureOffset());
            assertTrue(Math.abs(season.humidityOffset() - previousHumidity) < 0.001F,
                "humidity offset jumped at " + tick + ": " + previousHumidity + " -> " + season.humidityOffset());
            if (tick > 0 && tick < YEAR_TICKS) {
                assertTrue(season.progress() > previousProgress, "progress keeps increasing inside a year at " + tick);
            }

            previousTemperature = season.temperatureOffset();
            previousHumidity = season.humidityOffset();
            previousProgress = season.progress();
        }
    }

    @Test
    void summerIsWarmerThanWinterInEveryYearLength() {
        for (int yearDays : new int[]{1, 7, 28, 365, 3650}) {
            long yearTicks = yearDays * BuiltInSeasonProvider.TICKS_PER_DAY;
            SeasonInfo summer = BuiltInSeasonProvider.seasonAt(Math.round(0.375 * yearTicks), yearDays);
            SeasonInfo winter = BuiltInSeasonProvider.seasonAt(Math.round(0.875 * yearTicks), yearDays);
            assertEquals("summer", summer.name(), "year of " + yearDays + " days");
            assertEquals("winter", winter.name(), "year of " + yearDays + " days");
            assertTrue(summer.temperatureOffset() > winter.temperatureOffset(), "year of " + yearDays + " days");
            assertEquals(0.30F, summer.temperatureOffset(), 0.01F, "year of " + yearDays + " days");
            assertEquals(-0.35F, winter.temperatureOffset(), 0.01F, "year of " + yearDays + " days");
        }
    }

    @Test
    void seasonInfoNoneCarriesNoOffsets() {
        assertEquals("none", SeasonInfo.NONE.name());
        assertEquals(0.0F, SeasonInfo.NONE.temperatureOffset(), EPSILON);
        assertEquals(0.0F, SeasonInfo.NONE.humidityOffset(), EPSILON);
        assertEquals(0.0F, SeasonInfo.NONE.progress(), EPSILON);
        assertTrue(SeasonInfo.NONE.isNone());
        assertFalse(new SeasonInfo("spring", 0.0F, 0.1F, 0.1F).isNone());
    }
}
