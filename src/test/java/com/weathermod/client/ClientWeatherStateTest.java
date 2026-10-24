package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.WeatherTypes;
import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.StormSnapshot;
import com.weathermod.network.WindUpdatePayload;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Feeds payload records straight into a fresh client state and checks the storm list, local prediction,
 * interpolation, wind, climate and the overlay text. Needs no Minecraft bootstrap: only plain records and
 * arithmetic are used.
 */
class ClientWeatherStateTest {
    private static final float EPSILON = 1.0E-4F;
    private static final double DELTA = 1.0E-6D;
    /** The local line the short {@code buildLines} overload reports: no weather, vanilla rain, no shader pack. */
    private static final String LOCAL_NONE =
        "Local: rain 0.00 thunder 0.00 haze 0.00 none | renderer vanilla | shaders off";

    private static StormSnapshot snapshot(int id, double x, double z, float heading, float speed, float intensity,
                                          int ageTicks, int maxAgeTicks) {
        return new StormSnapshot(id, WeatherTypes.PLACEHOLDER.location(), x, 160.0D, z, heading, speed, intensity,
            64.0F, 512.0F, ageTicks, maxAgeTicks);
    }

    private static ClimateUpdatePayload temperateSummer() {
        return new ClimateUpdatePayload(0.8F, 0.4F, PrecipitationKind.RAIN, "summer", 0.42F);
    }

    @Test
    void listPayloadReplacesEveryStorm() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));
        state.apply(new StormDeltaPayload(snapshot(2, 10.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));
        ClientStorm second = state.storm(2).orElseThrow();

        state.apply(new StormListPayload(List.of(
            snapshot(2, 20.0D, 0.0D, 0.0F, 0.0F, 0.9F, 100, 2400),
            snapshot(3, 30.0D, 0.0D, 0.0F, 0.0F, 0.1F, 0, 2400))));

        assertEquals(2, state.stormCount());
        assertTrue(state.storm(1).isEmpty());
        assertSame(second, state.storm(2).orElseThrow(), "a storm that survives a list sync keeps its object");
        assertEquals(20.0D, second.x(), DELTA);
        assertEquals(0.9F, second.intensity(), EPSILON);
        assertEquals(30.0D, state.storm(3).orElseThrow().x(), DELTA);

        state.apply(new StormListPayload(List.of()));
        assertTrue(state.isEmpty());
    }

    @Test
    void deltaAddsThenUpdatesAndKeepsInterpolationOrigin() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(7, 100.0D, -50.0D, 90.0F, 2.0F, 0.25F, 40, 2400)));
        ClientStorm storm = state.storm(7).orElseThrow();
        assertEquals(100.0D, storm.getX(0.0F), DELTA);
        assertEquals(100.0D, storm.getX(1.0F), DELTA);

        state.apply(new StormDeltaPayload(snapshot(7, 110.0D, -50.0D, 90.0F, 2.0F, 0.75F, 50, 2400)));

        assertEquals(1, state.stormCount());
        assertSame(storm, state.storm(7).orElseThrow());
        assertEquals(100.0D, storm.getX(0.0F), DELTA);
        assertEquals(105.0D, storm.getX(0.5F), DELTA);
        assertEquals(110.0D, storm.getX(1.0F), DELTA);
        assertEquals(0.5F, storm.getIntensity(0.5F), EPSILON);
        assertEquals(50, storm.ageTicks());
        assertEquals(0, storm.ticksSinceUpdate());
        assertThrows(IllegalArgumentException.class,
            () -> storm.update(snapshot(8, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F, 0, 1)));
    }

    @Test
    void removedPayloadDropsOnlyKnownStorms() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));

        state.apply(new StormRemovedPayload(99));
        assertEquals(1, state.stormCount());

        state.apply(new StormRemovedPayload(1));
        assertTrue(state.isEmpty());
        assertFalse(state.remove(1));
    }

    @Test
    void tickMovesAlongHeadingAndAges() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 90.0F, 4.0F, 0.5F, 10, 2400)));
        state.apply(new StormDeltaPayload(snapshot(2, 0.0D, 0.0D, 0.0F, 4.0F, 0.5F, 10, 2400)));

        for (int i = 0; i < 20; i++) {
            state.tick();
        }

        ClientStorm east = state.storm(1).orElseThrow();
        ClientStorm north = state.storm(2).orElseThrow();
        assertEquals(4.0D, east.x(), 1.0E-6D, "heading 90 moves east by speed blocks per second");
        assertEquals(0.0D, east.z(), 1.0E-6D);
        assertEquals(0.0D, north.x(), 1.0E-6D);
        assertEquals(-4.0D, north.z(), 1.0E-6D, "heading 0 moves north, toward negative z");
        assertEquals(30, east.ageTicks());
        assertEquals(1, east.ageSeconds());
        assertEquals(120, east.maxAgeSeconds());
        assertEquals(20, east.ticksSinceUpdate());
        assertEquals(20L, state.ticks());
        assertEquals(4.0D - 0.2D, east.getX(0.0F), 1.0E-6D, "the previous tick is the interpolation origin");
    }

    @Test
    void windIsNormalisedAndClearedWithTheStorms() {
        ClientWeatherState state = new ClientWeatherState();
        assertFalse(state.hasWind());
        assertFalse(state.isWindGusting());

        state.apply(new WindUpdatePayload(-90.0F, 3.5F));
        assertTrue(state.hasWind());
        assertEquals(270.0F, state.windDirectionDegrees(), EPSILON);
        assertEquals(3.5F, state.windSpeed(), EPSILON);
        assertEquals(3.5F, state.windGustSpeed(), EPSILON, "the two-argument payload carries no gust");
        assertFalse(state.isWindGusting());

        state.apply(new WindUpdatePayload(450.0F, -1.0F));
        assertEquals(90.0F, state.windDirectionDegrees(), EPSILON);
        assertEquals(0.0F, state.windSpeed(), EPSILON);
        assertEquals(0.0F, state.windGustSpeed(), EPSILON);

        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));
        state.apply(temperateSummer());
        state.clear();
        assertTrue(state.isEmpty());
        assertFalse(state.hasWind());
        assertEquals(0.0F, state.windSpeed(), EPSILON);
        assertEquals(0.0F, state.windGustSpeed(), EPSILON);
        assertFalse(state.hasClimate(), "clearing forgets the climate too");
        assertEquals(0L, state.ticks());
    }

    @Test
    void gustIsStoredAndNeverBelowTheSustainedSpeed() {
        ClientWeatherState state = new ClientWeatherState();

        state.apply(new WindUpdatePayload(10.0F, 4.0F, 6.5F));
        assertTrue(state.hasWind());
        assertTrue(state.isWindGusting());
        assertEquals(4.0F, state.windSpeed(), EPSILON);
        assertEquals(6.5F, state.windGustSpeed(), EPSILON);

        state.apply(new WindUpdatePayload(10.0F, 4.0F, 4.0F));
        assertFalse(state.isWindGusting(), "a gust speed equal to the speed is no gust");
        assertEquals(4.0F, state.windGustSpeed(), EPSILON);

        state.setWind(10.0F, 4.0F, 2.0F);
        assertFalse(state.isWindGusting());
        assertEquals(4.0F, state.windGustSpeed(), EPSILON, "a gust below the sustained speed is raised to it");

        state.setWind(10.0F, -3.0F, -5.0F);
        assertEquals(0.0F, state.windSpeed(), EPSILON);
        assertEquals(0.0F, state.windGustSpeed(), EPSILON);

        state.setWind(380.0F, 2.0F);
        assertEquals(20.0F, state.windDirectionDegrees(), EPSILON);
        assertEquals(2.0F, state.windGustSpeed(), EPSILON, "the two-argument setter sets gust = speed");
    }

    @Test
    void listPayloadForgetsTheWindUntilTheNextUpdate() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new WindUpdatePayload(90.0F, 2.0F, 3.0F));
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));

        state.apply(new StormListPayload(List.of()));

        assertTrue(state.isEmpty());
        assertFalse(state.hasWind(), "a dimension without weather sends only the list, so the old wind must go");
        assertEquals(0.0F, state.windSpeed(), EPSILON);
        assertEquals(0.0F, state.windGustSpeed(), EPSILON);
        assertFalse(state.isWindGusting());

        state.apply(new WindUpdatePayload(180.0F, 1.0F));
        assertTrue(state.hasWind());
        assertEquals(180.0F, state.windDirectionDegrees(), EPSILON);
    }

    @Test
    void climateIsStoredFromThePayload() {
        ClientWeatherState state = new ClientWeatherState();
        assertFalse(state.hasClimate());
        assertFalse(state.hasSeason());
        assertEquals(PrecipitationKind.NONE, state.precipitationKind());
        assertEquals(ClimateUpdatePayload.NO_SEASON, state.seasonName());

        state.apply(temperateSummer());

        assertTrue(state.hasClimate());
        assertTrue(state.hasSeason());
        assertEquals(0.8F, state.temperature(), EPSILON);
        assertEquals(0.4F, state.humidity(), EPSILON);
        assertEquals(PrecipitationKind.RAIN, state.precipitationKind());
        assertEquals("summer", state.seasonName());
        assertEquals(0.42F, state.seasonProgress(), EPSILON);

        state.apply(new ClimateUpdatePayload(-0.3F, 0.9F, PrecipitationKind.SNOW, "mid_winter", 0.875F));
        assertEquals(-0.3F, state.temperature(), EPSILON);
        assertEquals(PrecipitationKind.SNOW, state.precipitationKind());
        assertEquals("mid_winter", state.seasonName());
        assertEquals(0.875F, state.seasonProgress(), EPSILON);
    }

    @Test
    void climateWithoutSeasonAndOutOfRangeValuesAreNormalised() {
        ClientWeatherState state = new ClientWeatherState();

        state.apply(new ClimateUpdatePayload(2.1F, 0.02F, PrecipitationKind.RAIN, ClimateUpdatePayload.NO_SEASON,
            0.0F));
        assertTrue(state.hasClimate());
        assertFalse(state.hasSeason(), "the Nether reports no season");
        assertEquals(ClimateUpdatePayload.NO_SEASON, state.seasonName());

        state.setClimate(0.5F, 1.7F, PrecipitationKind.SLEET, "autumn", -0.25F);
        assertEquals(1.0F, state.humidity(), EPSILON, "humidity is clamped to 1");
        assertEquals(0.0F, state.seasonProgress(), EPSILON, "progress is clamped to 0");
        assertEquals(PrecipitationKind.SLEET, state.precipitationKind());

        state.setClimate(0.5F, -0.1F, PrecipitationKind.NONE, "spring", 1.5F);
        assertEquals(0.0F, state.humidity(), EPSILON);
        assertEquals(1.0F, state.seasonProgress(), EPSILON);

        assertThrows(NullPointerException.class, () -> state.setClimate(0.5F, 0.5F, null, "spring", 0.5F));
        assertThrows(NullPointerException.class,
            () -> state.setClimate(0.5F, 0.5F, PrecipitationKind.RAIN, null, 0.5F));
        assertThrows(NullPointerException.class, () -> state.apply((ClimateUpdatePayload) null));
    }

    @Test
    void listPayloadForgetsTheClimateUntilTheNextUpdate() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(temperateSummer());
        assertTrue(state.hasClimate());

        state.apply(new StormListPayload(List.of(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400))));

        assertEquals(1, state.stormCount(), "the storms in the list are kept");
        assertFalse(state.hasClimate(), "a dimension without weather sends only the list, so the climate must go");
        assertFalse(state.hasSeason());
        assertEquals(0.0F, state.temperature(), EPSILON);
        assertEquals(0.0F, state.humidity(), EPSILON);
        assertEquals(PrecipitationKind.NONE, state.precipitationKind());
        assertEquals(ClimateUpdatePayload.NO_SEASON, state.seasonName());
        assertEquals(0.0F, state.seasonProgress(), EPSILON);

        state.apply(new ClimateUpdatePayload(0.25F, 0.8F, PrecipitationKind.RAIN, "spring", 0.1F));
        assertTrue(state.hasClimate());
        assertEquals("spring", state.seasonName());
    }

    @Test
    void stormsWithoutUpdatesAreDroppedAfterTheStaleLimit() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 24000)));
        state.apply(new StormDeltaPayload(snapshot(2, 50.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 24000)));

        for (int i = 0; i < ClientWeatherState.STALE_TICKS; i++) {
            state.tick();
        }
        assertEquals(2, state.stormCount(), "storms are kept right up to the stale limit");
        assertEquals(ClientWeatherState.STALE_TICKS, state.storm(1).orElseThrow().ticksSinceUpdate());

        state.apply(new StormDeltaPayload(snapshot(2, 50.0D, 0.0D, 0.0F, 0.0F, 0.5F, 400, 24000)));
        state.tick();

        assertTrue(state.storm(1).isEmpty(), "a storm the server stopped sending is dropped");
        assertTrue(state.storm(2).isPresent(), "a storm that is still being sent stays");
        assertEquals(1, state.stormCount());

        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 401, 24000)));
        assertEquals(2, state.stormCount(), "a later delta brings the storm back");
    }

    @Test
    void overlayLinesListHeaderWindClimateLocalCountAndStorms() {
        ClientWeatherState state = new ClientWeatherState();
        List<String> empty = WeatherDebugOverlay.buildLines(state, 0.0D, 0.0D, 0.0F);
        assertEquals(List.of(WeatherDebugOverlay.HEADER, WeatherDebugOverlay.WIND_UNKNOWN,
            WeatherDebugOverlay.CLIMATE_UNKNOWN, LOCAL_NONE, "Storms: 0"), empty);

        state.apply(new WindUpdatePayload(215.4F, 3.25F));
        state.apply(temperateSummer());
        state.apply(new StormDeltaPayload(snapshot(5, 30.0D, 40.0D, 0.0F, 0.0F, 0.456F, 600, 2400)));
        List<String> lines = WeatherDebugOverlay.buildLines(state, 0.0D, 0.0D, 1.0F);

        assertEquals(6, lines.size());
        assertEquals("Wind: 215 deg at 3.3 b/s", lines.get(1), "no gust is mentioned while none blows");
        assertEquals("Climate: T 0.80 H 0.40 rain, season summer 42%", lines.get(2));
        assertEquals(LOCAL_NONE, lines.get(3), "the short overload reports no local weather");
        assertEquals("Storms: 1", lines.get(4));
        assertEquals("#5 placeholder  50 m  46%  30/120 s  synced 0s ago", lines.get(5));
    }

    @Test
    void overlayLinesWithLocalWeatherPutTheLocalLineAfterTheClimate() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new WindUpdatePayload(90.0F, 12.0F));
        state.apply(new ClimateUpdatePayload(0.15F, 0.6F, PrecipitationKind.SLEET, "early_spring", 0.1F));
        state.apply(new StormDeltaPayload(snapshot(7, 0.0D, 20.0D, 0.0F, 0.0F, 1.0F, 1200, 2400)));
        LocalConditions sleet = new LocalConditions(0.8F, 0.0F, 0.4F, PrecipitationKind.SLEET, 1.0F, 7);

        List<String> lines = WeatherDebugOverlay.buildLines(state, 0.0D, 0.0D, 1.0F, sleet, 0.5F, 0.1F, 0.2F,
            true, false);

        assertEquals(List.of(WeatherDebugOverlay.HEADER,
            "Wind: 90 deg at 12.0 b/s",
            "Climate: T 0.15 H 0.60 sleet, season early_spring 10%",
            "Local: rain 0.50 thunder 0.10 haze 0.20 sleet | renderer weathermod | shaders off",
            "Storms: 1",
            "#7 placeholder  20 m  100%  60/120 s  synced 0s ago"), lines);
    }

    @Test
    void overlayLocalLineShowsDrivenLevelsKindRendererAndShaders() {
        assertEquals(LOCAL_NONE, WeatherDebugOverlay.formatLocal(LocalConditions.NONE, 0.0F, 0.0F, 0.0F, false,
            false));

        LocalConditions rain = new LocalConditions(1.0F, 0.5F, 0.0F, PrecipitationKind.RAIN, 1.0F, 3);
        assertEquals("Local: rain 0.35 thunder 0.12 haze 0.00 rain | renderer weathermod | shaders on",
            WeatherDebugOverlay.formatLocal(rain, 0.35F, 0.12F, 0.0F, true, true),
            "the driven levels, not the raw conditions, are printed while they ramp");

        LocalConditions snow = new LocalConditions(1.0F, 0.0F, 0.25F, PrecipitationKind.SNOW, 0.7F, 4);
        assertEquals("Local: rain 1.00 thunder 0.00 haze 0.25 snow | renderer vanilla | shaders on",
            WeatherDebugOverlay.formatLocal(snow, 1.0F, 0.0F, 0.25F, false, true));

        LocalConditions fog = new LocalConditions(0.0F, 0.0F, 0.8F, PrecipitationKind.NONE, 1.0F, -1);
        assertEquals("Local: rain 0.00 thunder 0.00 haze 0.80 none | renderer weathermod | shaders off",
            WeatherDebugOverlay.formatLocal(fog, 0.0F, 0.0F, 0.8F, true, false));
    }

    @Test
    void overlayWindLineShowsTheGustOnlyWhileGusting() {
        ClientWeatherState state = new ClientWeatherState();

        state.apply(new WindUpdatePayload(215.4F, 3.25F, 5.06F));
        assertEquals("Wind: 215 deg at 3.3 b/s, gust 5.1 b/s", WeatherDebugOverlay.formatWind(state));

        state.apply(new WindUpdatePayload(215.4F, 3.25F, 3.25F));
        assertEquals("Wind: 215 deg at 3.3 b/s", WeatherDebugOverlay.formatWind(state));
    }

    @Test
    void overlayClimateLineHandlesEveryKindAndNoSeason() {
        ClientWeatherState state = new ClientWeatherState();
        assertEquals(WeatherDebugOverlay.CLIMATE_UNKNOWN, WeatherDebugOverlay.formatClimate(state));

        state.apply(new ClimateUpdatePayload(-0.5F, 0.4F, PrecipitationKind.SNOW, "winter", 0.875F));
        assertEquals("Climate: T -0.50 H 0.40 snow, season winter 88%", WeatherDebugOverlay.formatClimate(state));

        state.apply(new ClimateUpdatePayload(0.15F, 0.55F, PrecipitationKind.SLEET, "early_spring", 0.0F));
        assertEquals("Climate: T 0.15 H 0.55 sleet, season early_spring 0%",
            WeatherDebugOverlay.formatClimate(state));

        state.apply(new ClimateUpdatePayload(0.5F, 0.0F, PrecipitationKind.NONE, ClimateUpdatePayload.NO_SEASON,
            0.0F));
        assertEquals("Climate: T 0.50 H 0.00 none, no season", WeatherDebugOverlay.formatClimate(state));
    }

    @Test
    void overlayStormLineCountsSecondsSinceTheLastSync() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new StormDeltaPayload(snapshot(9, 0.0D, 0.0D, 0.0F, 0.0F, 1.0F, 0, 2400)));
        for (int i = 0; i < 59; i++) {
            state.tick();
        }
        ClientStorm storm = state.storm(9).orElseThrow();

        assertEquals("#9 placeholder  0 m  100%  2/120 s  synced 2s ago",
            WeatherDebugOverlay.formatStorm(storm, 0.0D, 0.0D, 1.0F));

        state.apply(new StormDeltaPayload(snapshot(9, 0.0D, 0.0D, 0.0F, 0.0F, 1.0F, 60, 2400)));
        assertEquals("#9 placeholder  0 m  100%  3/120 s  synced 0s ago",
            WeatherDebugOverlay.formatStorm(storm, 0.0D, 0.0D, 1.0F));
    }
}
