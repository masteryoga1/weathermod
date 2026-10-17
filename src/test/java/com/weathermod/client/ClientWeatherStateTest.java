package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherTypes;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.StormSnapshot;
import com.weathermod.network.WindUpdatePayload;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Feeds payload records straight into a fresh client state and checks the storm list, local prediction,
 * interpolation and the overlay text. Needs no Minecraft bootstrap: only plain records and arithmetic are used.
 */
class ClientWeatherStateTest {
    private static final float EPSILON = 1.0E-4F;
    private static final double DELTA = 1.0E-6D;

    private static StormSnapshot snapshot(int id, double x, double z, float heading, float speed, float intensity,
                                          int ageTicks, int maxAgeTicks) {
        return new StormSnapshot(id, WeatherTypes.PLACEHOLDER.location(), x, 160.0D, z, heading, speed, intensity,
            64.0F, 512.0F, ageTicks, maxAgeTicks);
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

        state.apply(new WindUpdatePayload(-90.0F, 3.5F));
        assertTrue(state.hasWind());
        assertEquals(270.0F, state.windDirectionDegrees(), EPSILON);
        assertEquals(3.5F, state.windSpeed(), EPSILON);

        state.apply(new WindUpdatePayload(450.0F, -1.0F));
        assertEquals(90.0F, state.windDirectionDegrees(), EPSILON);
        assertEquals(0.0F, state.windSpeed(), EPSILON);

        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));
        state.clear();
        assertTrue(state.isEmpty());
        assertFalse(state.hasWind());
        assertEquals(0.0F, state.windSpeed(), EPSILON);
    }

    @Test
    void listPayloadForgetsTheWindUntilTheNextUpdate() {
        ClientWeatherState state = new ClientWeatherState();
        state.apply(new WindUpdatePayload(90.0F, 2.0F));
        state.apply(new StormDeltaPayload(snapshot(1, 0.0D, 0.0D, 0.0F, 0.0F, 0.5F, 0, 2400)));

        state.apply(new StormListPayload(List.of()));

        assertTrue(state.isEmpty());
        assertFalse(state.hasWind(), "a dimension without weather sends only the list, so the old wind must go");
        assertEquals(0.0F, state.windSpeed(), EPSILON);

        state.apply(new WindUpdatePayload(180.0F, 1.0F));
        assertTrue(state.hasWind());
        assertEquals(180.0F, state.windDirectionDegrees(), EPSILON);
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
    void overlayLinesListHeaderWindCountAndStorms() {
        ClientWeatherState state = new ClientWeatherState();
        List<String> empty = WeatherDebugOverlay.buildLines(state, 0.0D, 0.0D, 0.0F);
        assertEquals(List.of(WeatherDebugOverlay.HEADER, "Wind: waiting for server", "Storms: 0"), empty);

        state.apply(new WindUpdatePayload(215.4F, 3.25F));
        state.apply(new StormDeltaPayload(snapshot(5, 30.0D, 40.0D, 0.0F, 0.0F, 0.456F, 600, 2400)));
        List<String> lines = WeatherDebugOverlay.buildLines(state, 0.0D, 0.0D, 1.0F);

        assertEquals(4, lines.size());
        assertEquals("Wind: 215 deg at 3.3 b/s", lines.get(1));
        assertEquals("Storms: 1", lines.get(2));
        assertEquals("#5 placeholder  50 m  46%  30/120 s", lines.get(3));
    }
}
