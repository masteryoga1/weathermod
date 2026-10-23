package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.WeatherMod;
import com.weathermod.core.Coverage;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.PrecipitationMode;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.StormSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Feeds hand-built storms and weather types into the local weather arithmetic and checks coverage, screen
 * combination, the dominant storm, kind resolution and the handling of unknown types and unknown climate. Needs no
 * Minecraft bootstrap: only records, enums and arithmetic are used.
 */
class LocalWeatherMathTest {
    private static final float EPSILON = 1.0E-5F;
    private static final float RADIUS = 100.0F;
    private static final double CENTRE_X = 1000.0D;
    private static final double CENTRE_Z = -500.0D;
    private static final float WARM = 0.8F;
    private static final float COOL = 0.15F;
    private static final float COLD = -0.5F;
    private static final ResourceLocation UNKNOWN_TYPE = WeatherMod.id("mystery");

    private static final WeatherType RAIN =
        type(WeatherFamily.PRECIPITATION, 1.0F, 0.0F, 0.3F, PrecipitationMode.AUTO);
    private static final WeatherType DRIZZLE =
        type(WeatherFamily.PRECIPITATION, 0.35F, 0.0F, 0.2F, PrecipitationMode.AUTO);
    private static final WeatherType THUNDERSTORM =
        type(WeatherFamily.PRECIPITATION, 1.0F, 1.0F, 0.5F, PrecipitationMode.AUTO);
    private static final WeatherType SNOW =
        type(WeatherFamily.PRECIPITATION, 1.0F, 0.0F, 0.4F, PrecipitationMode.SNOW);
    private static final WeatherType SLEET =
        type(WeatherFamily.PRECIPITATION, 0.8F, 0.0F, 0.35F, PrecipitationMode.SLEET);
    private static final WeatherType OVERCAST =
        type(WeatherFamily.PRECIPITATION, 0.0F, 0.0F, 0.25F, PrecipitationMode.NONE);
    private static final WeatherType FOG =
        type(WeatherFamily.OBSCURANT, 0.0F, 0.0F, 0.8F, PrecipitationMode.NONE);

    private static final Map<ResourceLocation, WeatherType> TYPES = Map.of(
        WeatherTypes.RAIN.location(), RAIN,
        WeatherTypes.DRIZZLE.location(), DRIZZLE,
        WeatherTypes.THUNDERSTORM.location(), THUNDERSTORM,
        WeatherTypes.SNOW.location(), SNOW,
        WeatherTypes.SLEET.location(), SLEET,
        WeatherTypes.OVERCAST.location(), OVERCAST,
        WeatherTypes.FOG.location(), FOG);
    private static final Function<ResourceLocation, WeatherType> LOOKUP = TYPES::get;

    private static WeatherType type(WeatherFamily family, float rainLevel, float thunderLevel, float hazeStrength,
                                    PrecipitationMode mode) {
        return new WeatherType(family, WeatherType.DEFAULT_INTENSITY, WeatherType.Climate.ANY,
            WeatherType.DEFAULT_DURATION_SECONDS, RADIUS, RADIUS * 4.0F, 10,
            new WeatherType.Visuals(rainLevel, thunderLevel, hazeStrength), mode, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS, true);
    }

    private static StormSnapshot snapshot(int id, ResourceLocation typeId, double x, double z, float intensity) {
        return new StormSnapshot(id, typeId, x, 160.0D, z, 0.0F, 0.0F, intensity, RADIUS, RADIUS * 4.0F, 0, 2400);
    }

    private static ClientStorm storm(int id, ResourceLocation typeId, double x, double z, float intensity) {
        return new ClientStorm(snapshot(id, typeId, x, z, intensity));
    }

    /** A storm centred on the shared test centre, offset east by {@code distance} blocks. */
    private static ClientStorm stormAt(int id, ResourceLocation typeId, double distance, float intensity) {
        return storm(id, typeId, CENTRE_X + distance, CENTRE_Z, intensity);
    }

    private static LocalConditions atCentre(List<ClientStorm> storms, float temperature) {
        return LocalWeatherMath.compute(storms, LOOKUP, CENTRE_X, CENTRE_Z, 1.0F, temperature);
    }

    @Test
    void screenCombinesWithoutExceedingOne() {
        assertEquals(0.0F, LocalWeatherMath.screen(0.0F, 0.0F), EPSILON);
        assertEquals(0.4F, LocalWeatherMath.screen(0.4F, 0.0F), EPSILON, "zero leaves the other side unchanged");
        assertEquals(0.4F, LocalWeatherMath.screen(0.0F, 0.4F), EPSILON);
        assertEquals(0.75F, LocalWeatherMath.screen(0.5F, 0.5F), EPSILON);
        assertEquals(1.0F, LocalWeatherMath.screen(1.0F, 0.3F), EPSILON, "a full contribution saturates");
        assertEquals(1.0F, LocalWeatherMath.screen(1.0F, 1.0F), EPSILON);
        assertEquals(LocalWeatherMath.screen(0.3F, 0.6F), LocalWeatherMath.screen(0.6F, 0.3F), EPSILON,
            "screen is symmetric");
        assertTrue(LocalWeatherMath.screen(0.3F, 0.6F) > 0.6F, "screen grows past the larger contribution");
    }

    @Test
    void unknownTemperatureFallsAsRain() {
        assertEquals(0.5F, LocalWeatherMath.UNKNOWN_TEMPERATURE, EPSILON);
        assertTrue(LocalWeatherMath.UNKNOWN_TEMPERATURE >= PrecipitationKind.RAIN_FROM);
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(LocalWeatherMath.UNKNOWN_TEMPERATURE));

        LocalConditions conditions = atCentre(List.of(stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F)),
            LocalWeatherMath.UNKNOWN_TEMPERATURE);
        assertSame(PrecipitationKind.RAIN, conditions.kind());
        assertTrue(conditions.isPrecipitating());
    }

    @Test
    void noStormsGiveNone() {
        LocalConditions conditions = atCentre(List.of(), WARM);
        assertEquals(LocalConditions.NONE, conditions);
        assertFalse(conditions.isPrecipitating());
        assertEquals(-1, conditions.dominantStormId());
    }

    @Test
    void oneStormAtTheCentreIsFull() {
        LocalConditions conditions = atCentre(List.of(stormAt(7, WeatherTypes.RAIN.location(), 0.0D, 1.0F)), WARM);

        assertEquals(1.0F, conditions.rain(), EPSILON);
        assertEquals(0.0F, conditions.thunder(), EPSILON);
        assertEquals(0.3F, conditions.haze(), EPSILON);
        assertEquals(1.0F, conditions.coverage(), EPSILON);
        assertSame(PrecipitationKind.RAIN, conditions.kind());
        assertEquals(7, conditions.dominantStormId());
        assertTrue(conditions.isPrecipitating());
    }

    @Test
    void oneStormScalesWithIntensity() {
        LocalConditions conditions = atCentre(List.of(stormAt(7, WeatherTypes.THUNDERSTORM.location(), 0.0D, 0.5F)),
            WARM);

        assertEquals(0.5F, conditions.rain(), EPSILON);
        assertEquals(0.5F, conditions.thunder(), EPSILON);
        assertEquals(0.25F, conditions.haze(), EPSILON);
        assertEquals(0.5F, conditions.coverage(), EPSILON);
        assertSame(PrecipitationKind.RAIN, conditions.kind());
        assertEquals(7, conditions.dominantStormId());
    }

    @Test
    void oneStormAtTheEdgeFades() {
        LocalConditions inner = atCentre(List.of(stormAt(7, WeatherTypes.RAIN.location(), 60.0D, 1.0F)), WARM);
        assertEquals(1.0F, inner.rain(), EPSILON, "the inner fraction of the radius is still full");
        assertEquals(1.0F, inner.coverage(), EPSILON);

        LocalConditions shoulder = atCentre(List.of(stormAt(7, WeatherTypes.RAIN.location(), 80.0D, 1.0F)), WARM);
        float expected = Coverage.coverage(1.0F, 80.0D, RADIUS);
        assertEquals(0.5F, expected, EPSILON, "half way through the shoulder is half");
        assertEquals(expected, shoulder.rain(), EPSILON);
        assertEquals(0.3F * expected, shoulder.haze(), EPSILON);
        assertEquals(expected, shoulder.coverage(), EPSILON);
        assertSame(PrecipitationKind.RAIN, shoulder.kind());
        assertEquals(7, shoulder.dominantStormId());
        assertTrue(shoulder.isPrecipitating());

        LocalConditions rim = atCentre(List.of(stormAt(7, WeatherTypes.RAIN.location(), 100.0D, 1.0F)), WARM);
        assertEquals(LocalConditions.NONE, rim, "exactly the radius is no longer covered");
    }

    @Test
    void oneStormOutsideIsNone() {
        LocalConditions conditions = atCentre(List.of(stormAt(7, WeatherTypes.RAIN.location(), 150.0D, 1.0F)), WARM);

        assertEquals(LocalConditions.NONE, conditions);
        assertFalse(conditions.isPrecipitating());
        assertSame(PrecipitationKind.NONE, conditions.kind());
        assertEquals(-1, conditions.dominantStormId(), "a storm that does not cover the point is never dominant");
    }

    @Test
    void distanceIsMeasuredHorizontallyFromTheInterpolatedCentre() {
        ClientStorm storm = storm(3, WeatherTypes.RAIN.location(), CENTRE_X, CENTRE_Z, 1.0F);
        storm.update(snapshot(3, WeatherTypes.RAIN.location(), CENTRE_X + 200.0D, CENTRE_Z, 1.0F));

        LocalConditions before = LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 0.0F, WARM);
        LocalConditions midway = LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 0.5F, WARM);
        LocalConditions after = LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 1.0F, WARM);

        assertEquals(1.0F, before.rain(), EPSILON, "at partial tick 0 the storm is still overhead");
        assertEquals(LocalConditions.NONE, midway, "at partial tick 0.5 the centre is 100 blocks away");
        assertEquals(LocalConditions.NONE, after);

        ClientStorm diagonal = storm(4, WeatherTypes.RAIN.location(), CENTRE_X + 60.0D, CENTRE_Z + 80.0D, 1.0F);
        LocalConditions far = atCentre(List.of(diagonal), WARM);
        assertEquals(LocalConditions.NONE, far, "60 east and 80 south is exactly the radius away");
    }

    @Test
    void intensityIsInterpolatedToo() {
        ClientStorm storm = stormAt(5, WeatherTypes.RAIN.location(), 0.0D, 0.0F);
        storm.update(snapshot(5, WeatherTypes.RAIN.location(), CENTRE_X, CENTRE_Z, 1.0F));

        assertEquals(0.25F, LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 0.25F, WARM).rain(),
            EPSILON);
        assertEquals(1.0F, LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 1.0F, WARM).rain(),
            EPSILON);
        assertEquals(LocalConditions.NONE, LocalWeatherMath.compute(List.of(storm), LOOKUP, CENTRE_X, CENTRE_Z, 0.0F,
            WARM), "zero intensity covers nothing");
    }

    @Test
    void twoOverlappingStormsAreScreenCombined() {
        List<ClientStorm> storms = List.of(
            stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 0.5F),
            stormAt(2, WeatherTypes.THUNDERSTORM.location(), 0.0D, 0.5F));
        LocalConditions conditions = atCentre(storms, WARM);

        assertEquals(0.75F, conditions.rain(), EPSILON, "screen(0.5, 0.5)");
        assertEquals(0.5F, conditions.thunder(), EPSILON, "only the thunderstorm thunders");
        assertEquals(LocalWeatherMath.screen(0.15F, 0.25F), conditions.haze(), EPSILON);
        assertEquals(0.5F, conditions.coverage(), EPSILON, "coverage is the largest single storm, not a sum");
        assertSame(PrecipitationKind.RAIN, conditions.kind());
    }

    @Test
    void twoFullStormsNeverExceedOne() {
        List<ClientStorm> storms = List.of(
            stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F),
            stormAt(2, WeatherTypes.THUNDERSTORM.location(), 10.0D, 1.0F),
            stormAt(3, WeatherTypes.DRIZZLE.location(), -20.0D, 1.0F));
        LocalConditions conditions = atCentre(storms, WARM);

        assertEquals(1.0F, conditions.rain(), EPSILON);
        assertEquals(1.0F, conditions.thunder(), EPSILON);
        assertTrue(conditions.haze() <= 1.0F);
        assertEquals(LocalWeatherMath.screen(LocalWeatherMath.screen(0.3F, 0.5F), 0.2F), conditions.haze(), EPSILON);
        assertEquals(1.0F, conditions.coverage(), EPSILON);
    }

    @Test
    void partialOverlapCombinesEachStormAtItsOwnCoverage() {
        List<ClientStorm> storms = List.of(
            stormAt(1, WeatherTypes.RAIN.location(), 80.0D, 1.0F),
            stormAt(2, WeatherTypes.DRIZZLE.location(), 0.0D, 1.0F));
        LocalConditions conditions = atCentre(storms, WARM);

        assertEquals(LocalWeatherMath.screen(0.5F, 0.35F), conditions.rain(), EPSILON);
        assertEquals(LocalWeatherMath.screen(0.15F, 0.2F), conditions.haze(), EPSILON);
        assertEquals(1.0F, conditions.coverage(), EPSILON, "the drizzle covers the point fully");
    }

    @Test
    void dominantStormHasTheLargestRainContribution() {
        ClientStorm rainOverhead = stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F);
        ClientStorm snowAtTheEdge = stormAt(2, WeatherTypes.SNOW.location(), 80.0D, 1.0F);
        ClientStorm snowOverhead = stormAt(3, WeatherTypes.SNOW.location(), 0.0D, 1.0F);
        ClientStorm rainAtTheEdge = stormAt(4, WeatherTypes.RAIN.location(), 80.0D, 1.0F);

        LocalConditions rainWins = atCentre(List.of(rainOverhead, snowAtTheEdge), WARM);
        assertEquals(1, rainWins.dominantStormId());
        assertSame(PrecipitationKind.RAIN, rainWins.kind(), "the rain storm overhead decides the kind");

        LocalConditions snowWins = atCentre(List.of(rainAtTheEdge, snowOverhead), WARM);
        assertEquals(3, snowWins.dominantStormId());
        assertSame(PrecipitationKind.SNOW, snowWins.kind(), "a fixed snow type snows even where it is warm");

        LocalConditions weakerLevel = atCentre(List.of(
            stormAt(5, WeatherTypes.DRIZZLE.location(), 0.0D, 1.0F),
            stormAt(6, WeatherTypes.SLEET.location(), 0.0D, 0.6F)), WARM);
        assertEquals(6, weakerLevel.dominantStormId(), "0.8 * 0.6 beats 0.35 * 1.0");
        assertSame(PrecipitationKind.SLEET, weakerLevel.kind());
    }

    @Test
    void dominantTieGoesToTheFirstStorm() {
        LocalConditions conditions = atCentre(List.of(
            stormAt(11, WeatherTypes.SNOW.location(), 0.0D, 1.0F),
            stormAt(12, WeatherTypes.RAIN.location(), 0.0D, 1.0F)), WARM);
        assertEquals(11, conditions.dominantStormId());
        assertSame(PrecipitationKind.SNOW, conditions.kind());

        LocalConditions reversed = atCentre(List.of(
            stormAt(12, WeatherTypes.RAIN.location(), 0.0D, 1.0F),
            stormAt(11, WeatherTypes.SNOW.location(), 0.0D, 1.0F)), WARM);
        assertEquals(12, reversed.dominantStormId());
        assertSame(PrecipitationKind.RAIN, reversed.kind());
    }

    @Test
    void autoModeFollowsTheTemperature() {
        List<ClientStorm> storms = List.of(stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F));

        assertSame(PrecipitationKind.RAIN, atCentre(storms, WARM).kind());
        assertSame(PrecipitationKind.RAIN, atCentre(storms, 2.0F).kind(), "desert heat");
        assertSame(PrecipitationKind.RAIN, atCentre(storms, PrecipitationKind.RAIN_FROM).kind());
        assertSame(PrecipitationKind.SLEET, atCentre(storms, COOL).kind());
        assertSame(PrecipitationKind.SLEET, atCentre(storms, PrecipitationKind.SNOW_BELOW).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, 0.05F).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, COLD).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, -1.0F).kind());
        for (float temperature : new float[] {WARM, COOL, COLD}) {
            LocalConditions conditions = atCentre(storms, temperature);
            assertEquals(1.0F, conditions.rain(), EPSILON, "the rain level does not depend on the temperature");
            assertTrue(conditions.isPrecipitating());
        }
    }

    @Test
    void snowModeSnowsAtEveryTemperature() {
        List<ClientStorm> storms = List.of(stormAt(1, WeatherTypes.SNOW.location(), 0.0D, 1.0F));

        assertSame(PrecipitationKind.SNOW, atCentre(storms, WARM).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, COOL).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, COLD).kind());
        assertSame(PrecipitationKind.SNOW, atCentre(storms, LocalWeatherMath.UNKNOWN_TEMPERATURE).kind());
        assertEquals(0.4F, atCentre(storms, WARM).haze(), EPSILON);
    }

    @Test
    void sleetModeSleetsAtEveryTemperature() {
        List<ClientStorm> storms = List.of(stormAt(1, WeatherTypes.SLEET.location(), 0.0D, 1.0F));

        assertSame(PrecipitationKind.SLEET, atCentre(storms, WARM).kind());
        assertSame(PrecipitationKind.SLEET, atCentre(storms, COOL).kind());
        assertSame(PrecipitationKind.SLEET, atCentre(storms, COLD).kind());
        assertSame(PrecipitationKind.SLEET, atCentre(storms, LocalWeatherMath.UNKNOWN_TEMPERATURE).kind());
        assertEquals(0.8F, atCentre(storms, WARM).rain(), EPSILON, "sleet carries its own rain level");
        assertTrue(atCentre(storms, WARM).isPrecipitating());
    }

    @Test
    void fogGivesHazeButNoKind() {
        LocalConditions conditions = atCentre(List.of(stormAt(9, WeatherTypes.FOG.location(), 0.0D, 1.0F)), WARM);

        assertEquals(0.0F, conditions.rain(), EPSILON);
        assertEquals(0.0F, conditions.thunder(), EPSILON);
        assertEquals(0.8F, conditions.haze(), EPSILON);
        assertEquals(1.0F, conditions.coverage(), EPSILON, "the fog covers the point even though nothing falls");
        assertSame(PrecipitationKind.NONE, conditions.kind());
        assertEquals(-1, conditions.dominantStormId(), "a non-precipitating storm is never dominant");
        assertFalse(conditions.isPrecipitating());

        LocalConditions half = atCentre(List.of(stormAt(9, WeatherTypes.FOG.location(), 0.0D, 0.5F)), WARM);
        assertEquals(0.4F, half.haze(), EPSILON);
        assertEquals(0.5F, half.coverage(), EPSILON);
    }

    @Test
    void overcastIsDryEvenThoughItIsPrecipitationFamily() {
        LocalConditions conditions = atCentre(List.of(stormAt(9, WeatherTypes.OVERCAST.location(), 0.0D, 1.0F)),
            COLD);

        assertEquals(0.0F, conditions.rain(), EPSILON);
        assertEquals(0.25F, conditions.haze(), EPSILON);
        assertSame(PrecipitationKind.NONE, conditions.kind());
        assertEquals(-1, conditions.dominantStormId());
        assertFalse(conditions.isPrecipitating());
    }

    @Test
    void fogUnderRainAddsHazeAndLeavesTheKindToTheRain() {
        LocalConditions conditions = atCentre(List.of(
            stormAt(9, WeatherTypes.FOG.location(), 0.0D, 1.0F),
            stormAt(10, WeatherTypes.RAIN.location(), 0.0D, 1.0F)), WARM);

        assertEquals(1.0F, conditions.rain(), EPSILON);
        assertEquals(LocalWeatherMath.screen(0.8F, 0.3F), conditions.haze(), EPSILON);
        assertSame(PrecipitationKind.RAIN, conditions.kind());
        assertEquals(10, conditions.dominantStormId());
        assertTrue(conditions.isPrecipitating());
    }

    @Test
    void unknownTypesAreSkipped() {
        LocalConditions alone = atCentre(List.of(stormAt(1, UNKNOWN_TYPE, 0.0D, 1.0F)), WARM);
        assertEquals(LocalConditions.NONE, alone, "a storm whose type is unknown contributes nothing");

        LocalConditions mixed = atCentre(List.of(
            stormAt(1, UNKNOWN_TYPE, 0.0D, 1.0F),
            stormAt(2, WeatherTypes.RAIN.location(), 80.0D, 1.0F)), WARM);
        assertEquals(0.5F, mixed.rain(), EPSILON, "only the known storm counts");
        assertEquals(0.5F, mixed.coverage(), EPSILON);
        assertEquals(2, mixed.dominantStormId());

        LocalConditions none = LocalWeatherMath.compute(
            List.of(stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F)), id -> null, CENTRE_X, CENTRE_Z, 1.0F,
            WARM);
        assertEquals(LocalConditions.NONE, none, "a lookup that knows nothing gives no weather");
    }

    @Test
    void nullArgumentsAreRejected() {
        List<ClientStorm> storms = List.of(stormAt(1, WeatherTypes.RAIN.location(), 0.0D, 1.0F));
        assertThrows(NullPointerException.class,
            () -> LocalWeatherMath.compute(null, LOOKUP, CENTRE_X, CENTRE_Z, 1.0F, WARM));
        assertThrows(NullPointerException.class,
            () -> LocalWeatherMath.compute(storms, null, CENTRE_X, CENTRE_Z, 1.0F, WARM));
        assertThrows(NullPointerException.class, () -> new LocalConditions(0.0F, 0.0F, 0.0F, null, 0.0F, -1));
    }

    @Test
    void isPrecipitatingNeedsRainAndAKind() {
        assertFalse(LocalConditions.NONE.isPrecipitating());
        assertFalse(new LocalConditions(0.5F, 0.0F, 0.0F, PrecipitationKind.NONE, 0.5F, -1).isPrecipitating(),
            "rain without a kind is not precipitation");
        assertFalse(new LocalConditions(0.0F, 0.0F, 0.0F, PrecipitationKind.RAIN, 0.0F, 1).isPrecipitating(),
            "a kind without rain is not precipitation");
        assertTrue(new LocalConditions(0.01F, 0.0F, 0.0F, PrecipitationKind.SNOW, 0.01F, 1).isPrecipitating());
        assertTrue(new LocalConditions(1.0F, 1.0F, 0.5F, PrecipitationKind.SLEET, 1.0F, 1).isPrecipitating());
    }

    @Test
    void localWeatherUpdateUsesTheStateClimateOrFallsBackToRain() {
        LocalWeather weather = new LocalWeather();
        assertEquals(LocalConditions.NONE, weather.conditions());
        assertFalse(weather.active());

        ClientWeatherState state = new ClientWeatherState();
        state.apply(snapshot(1, WeatherTypes.RAIN.location(), CENTRE_X, CENTRE_Z, 1.0F));

        weather.update(state, LOOKUP, CENTRE_X, 70.0D, CENTRE_Z, true);
        assertTrue(weather.active());
        assertEquals(CENTRE_X, weather.cameraX(), 1.0E-9D);
        assertEquals(70.0D, weather.cameraY(), 1.0E-9D);
        assertEquals(CENTRE_Z, weather.cameraZ(), 1.0E-9D);
        assertEquals(1.0F, weather.conditions().rain(), EPSILON);
        assertSame(PrecipitationKind.RAIN, weather.conditions().kind(), "no climate yet, so auto falls as rain");
        assertEquals(1, weather.conditions().dominantStormId());

        state.apply(new ClimateUpdatePayload(COLD, 0.5F, PrecipitationKind.SNOW, "winter", 0.9F));
        weather.update(state, LOOKUP, CENTRE_X, 70.0D, CENTRE_Z, true);
        assertSame(PrecipitationKind.SNOW, weather.conditions().kind(), "the state's temperature drives auto mode");

        weather.update(state, LOOKUP, CENTRE_X + 150.0D, 70.0D, CENTRE_Z, true);
        assertEquals(LocalConditions.NONE, weather.conditions(), "the viewer moved out of the storm");
        assertEquals(CENTRE_X + 150.0D, weather.cameraX(), 1.0E-9D);
    }

    @Test
    void localWeatherInactiveAndClearGiveNone() {
        LocalWeather weather = new LocalWeather();
        ClientWeatherState state = new ClientWeatherState();
        state.apply(snapshot(1, WeatherTypes.RAIN.location(), CENTRE_X, CENTRE_Z, 1.0F));

        weather.update(state, LOOKUP, CENTRE_X, 70.0D, CENTRE_Z, false);
        assertFalse(weather.active());
        assertEquals(LocalConditions.NONE, weather.conditions(), "an unmanaged dimension shows no local weather");
        assertEquals(CENTRE_X, weather.cameraX(), 1.0E-9D, "the viewer position is still recorded");

        weather.update(state, LOOKUP, CENTRE_X, 70.0D, CENTRE_Z, true);
        assertTrue(weather.active());
        assertTrue(weather.conditions().isPrecipitating());

        weather.clear();
        assertFalse(weather.active());
        assertEquals(LocalConditions.NONE, weather.conditions());
        assertEquals(0.0D, weather.cameraX(), 1.0E-9D);
        assertEquals(0.0D, weather.cameraY(), 1.0E-9D);
        assertEquals(0.0D, weather.cameraZ(), 1.0E-9D);

        Map<ResourceLocation, WeatherType> empty = new HashMap<>();
        weather.update(state, empty::get, CENTRE_X, 70.0D, CENTRE_Z, true);
        assertTrue(weather.active());
        assertEquals(LocalConditions.NONE, weather.conditions(), "active but no type resolves");

        assertThrows(NullPointerException.class, () -> weather.update(null, LOOKUP, 0.0D, 0.0D, 0.0D, true));
        assertThrows(NullPointerException.class, () -> weather.update(state, null, 0.0D, 0.0D, 0.0D, true));
    }
}
