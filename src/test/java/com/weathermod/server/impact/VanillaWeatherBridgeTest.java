package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Coverage;
import com.weathermod.core.PrecipitationMode;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.impact.VanillaWeatherBridge.Decision;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Checks the pure half of the vanilla weather bridge: which storms and players switch vanilla rain and thunder on.
 * Storms are built directly, types come from a map, players are plain vectors; no Minecraft bootstrap is needed.
 */
class VanillaWeatherBridgeTest {
    private static final float EPSILON = 1.0E-6F;
    private static final float RADIUS = 100.0F;
    private static final float INTEREST = 512.0F;

    private static final ResourceKey<WeatherType> RAIN = WeatherRegistries.key("rain");
    private static final ResourceKey<WeatherType> THUNDERSTORM = WeatherRegistries.key("thunderstorm");
    private static final ResourceKey<WeatherType> SNOW = WeatherRegistries.key("snow");
    private static final ResourceKey<WeatherType> OVERCAST = WeatherRegistries.key("overcast");
    private static final ResourceKey<WeatherType> FOG = WeatherRegistries.key("fog");
    private static final ResourceKey<WeatherType> UNKNOWN = WeatherRegistries.key("not_registered");

    private static final Map<ResourceKey<WeatherType>, WeatherType> TYPES = Map.of(
        RAIN, type(WeatherFamily.PRECIPITATION, PrecipitationMode.AUTO, 0.0F),
        THUNDERSTORM, type(WeatherFamily.PRECIPITATION, PrecipitationMode.AUTO, 0.8F),
        SNOW, type(WeatherFamily.PRECIPITATION, PrecipitationMode.SNOW, 0.0F),
        OVERCAST, type(WeatherFamily.PRECIPITATION, PrecipitationMode.NONE, 0.0F),
        FOG, type(WeatherFamily.OBSCURANT, PrecipitationMode.AUTO, 0.9F));
    private static final Function<StormInstance, WeatherType> LOOKUP = storm -> TYPES.get(storm.type());

    private static WeatherType type(WeatherFamily family, PrecipitationMode precipitation, float thunderLevel) {
        return new WeatherType(family, WeatherType.DEFAULT_INTENSITY, WeatherType.Climate.ANY,
            WeatherType.DEFAULT_DURATION_SECONDS, RADIUS, INTEREST, WeatherType.DEFAULT_WEIGHT,
            new WeatherType.Visuals(1.0F, thunderLevel, 0.2F), precipitation, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS, true);
    }

    /** A storm in the plateau of its life, so its current intensity equals the given peak. */
    private static StormInstance storm(int id, ResourceKey<WeatherType> type, double x, double z, float intensity) {
        return new StormInstance(id, type, x, StormInstance.DEFAULT_CLOUD_BASE, z, 0.0F, 0.0F, intensity,
            500, 1000, RADIUS, INTEREST);
    }

    private static Position player(double x, double z) {
        return new Vec3(x, 64.0D, z);
    }

    private static Decision decide(List<StormInstance> storms, List<? extends Position> players) {
        return VanillaWeatherBridge.decide(storms, LOOKUP, players);
    }

    @Test
    void thresholdsMatchTheDesign() {
        assertEquals(0.25F, VanillaWeatherBridge.RAIN_COVERAGE_THRESHOLD, EPSILON);
        assertEquals(0.5F, VanillaWeatherBridge.THUNDER_VISUAL_THRESHOLD, EPSILON);
        assertEquals(1_000_000, VanillaWeatherBridge.REARM_TICKS);
        assertEquals(Decision.CLEAR, new Decision(false, false));
    }

    @Test
    void nothingToDecideIsClear() {
        assertEquals(Decision.CLEAR, decide(List.of(), List.of(player(0.0D, 0.0D))));
        assertEquals(Decision.CLEAR, decide(List.of(storm(1, RAIN, 0.0D, 0.0D, 1.0F)), List.of()));
        assertEquals(Decision.CLEAR, decide(List.of(), List.of()));
    }

    @Test
    void playerUnderARainStormTurnsVanillaRainOn() {
        Decision decision = decide(List.of(storm(1, RAIN, 0.0D, 0.0D, 1.0F)), List.of(player(10.0D, -10.0D)));

        assertTrue(decision.raining());
        assertFalse(decision.thundering(), "plain rain never thunders");
    }

    @Test
    void snowStormsCountAsPrecipitationToo() {
        Decision decision = decide(List.of(storm(1, SNOW, 50.0D, 50.0D, 0.6F)), List.of(player(40.0D, 60.0D)));

        assertTrue(decision.raining(), "vanilla has one flag for rain and snow alike");
        assertFalse(decision.thundering());
    }

    @Test
    void coverageMustReachTheThreshold() {
        StormInstance full = storm(1, RAIN, 0.0D, 0.0D, 1.0F);
        assertTrue(Coverage.coverage(full.intensity(), full.distanceTo(95.0D, 0.0D), full.radius())
            < VanillaWeatherBridge.RAIN_COVERAGE_THRESHOLD, "the test point must sit in the thin outer shoulder");
        assertFalse(decide(List.of(full), List.of(player(95.0D, 0.0D))).raining(), "the outer edge is too thin");
        assertFalse(decide(List.of(full), List.of(player(150.0D, 0.0D))).raining(), "outside the radius is dry");
        assertTrue(decide(List.of(full), List.of(player(60.0D, 0.0D))).raining(), "the inner disc is full coverage");

        assertFalse(decide(List.of(storm(2, RAIN, 0.0D, 0.0D, 0.2F)), List.of(player(0.0D, 0.0D))).raining(),
            "a weak storm never reaches the threshold even overhead");
        assertTrue(decide(List.of(storm(3, RAIN, 0.0D, 0.0D, 0.25F)), List.of(player(0.0D, 0.0D))).raining(),
            "the threshold itself is inclusive");
    }

    @Test
    void stormsStillRampingUpDoNotCount() {
        StormInstance newborn = new StormInstance(1, RAIN, 0.0D, StormInstance.DEFAULT_CLOUD_BASE, 0.0D, 0.0F, 0.0F,
            1.0F, 0, 1000, RADIUS, INTEREST);
        assertEquals(0.0F, newborn.intensity(), EPSILON);

        assertEquals(Decision.CLEAR, decide(List.of(newborn), List.of(player(0.0D, 0.0D))));
    }

    @Test
    void thunderNeedsACoveringStormWithEnoughThunderLevel() {
        Decision overhead = decide(List.of(storm(1, THUNDERSTORM, 0.0D, 0.0D, 1.0F)), List.of(player(5.0D, 5.0D)));
        assertTrue(overhead.raining());
        assertTrue(overhead.thundering());

        Decision farThunderNearRain = decide(
            List.of(storm(1, THUNDERSTORM, 1000.0D, 1000.0D, 1.0F), storm(2, RAIN, 0.0D, 0.0D, 1.0F)),
            List.of(player(0.0D, 0.0D)));
        assertTrue(farThunderNearRain.raining());
        assertFalse(farThunderNearRain.thundering(), "a thunderstorm covering nobody adds no thunder");

        Decision thunderAfterRain = decide(
            List.of(storm(1, RAIN, 0.0D, 0.0D, 1.0F), storm(2, THUNDERSTORM, 0.0D, 0.0D, 1.0F)),
            List.of(player(0.0D, 0.0D)));
        assertTrue(thunderAfterRain.thundering(), "storm order does not matter");
    }

    @Test
    void onlyPrecipitatingTypesSwitchRainOn() {
        List<Position> players = List.of(player(0.0D, 0.0D));

        assertEquals(Decision.CLEAR, decide(List.of(storm(1, OVERCAST, 0.0D, 0.0D, 1.0F)), players),
            "overcast has precipitation none");
        assertEquals(Decision.CLEAR, decide(List.of(storm(2, FOG, 0.0D, 0.0D, 1.0F)), players),
            "an obscurant never rains, however high its thunder level");
        assertTrue(decide(List.of(storm(1, OVERCAST, 0.0D, 0.0D, 1.0F), storm(3, RAIN, 0.0D, 0.0D, 1.0F)), players)
            .raining(), "a real rain storm next to them still counts");
    }

    @Test
    void unresolvedTypesAreIgnored() {
        Decision decision = decide(List.of(storm(1, UNKNOWN, 0.0D, 0.0D, 1.0F)), List.of(player(0.0D, 0.0D)));
        assertEquals(Decision.CLEAR, decision);

        Function<StormInstance, WeatherType> nothing = storm -> null;
        assertEquals(Decision.CLEAR, VanillaWeatherBridge.decide(List.of(storm(2, RAIN, 0.0D, 0.0D, 1.0F)), nothing,
            List.of(player(0.0D, 0.0D))));
    }

    @Test
    void anyCoveredPlayerIsEnough() {
        StormInstance storm = storm(1, RAIN, 0.0D, 0.0D, 1.0F);
        List<Position> players = List.of(player(5000.0D, 0.0D), player(-3000.0D, 200.0D), player(20.0D, 20.0D));

        assertTrue(decide(List.of(storm), players).raining());
        assertTrue(VanillaWeatherBridge.coversAnyone(storm, players));
        assertFalse(VanillaWeatherBridge.coversAnyone(storm, players.subList(0, 2)));
    }

    @Test
    void coverageAtIgnoresHeight() {
        StormInstance storm = storm(1, RAIN, 0.0D, 0.0D, 0.8F);

        assertEquals(0.8F, VanillaWeatherBridge.coverageAt(storm, new Vec3(0.0D, 64.0D, 0.0D)), EPSILON);
        assertEquals(0.8F, VanillaWeatherBridge.coverageAt(storm, new Vec3(0.0D, 300.0D, 0.0D)), EPSILON);
        assertEquals(0.0F, VanillaWeatherBridge.coverageAt(storm, new Vec3(RADIUS, 64.0D, 0.0D)), EPSILON);
    }

    @Test
    void rearmIsDueOnceTheTimerFallsBelowHalf() {
        int half = VanillaWeatherBridge.REARM_TICKS / 2;

        assertFalse(VanillaWeatherBridge.rearmDue(VanillaWeatherBridge.REARM_TICKS));
        assertFalse(VanillaWeatherBridge.rearmDue(half));
        assertTrue(VanillaWeatherBridge.rearmDue(half - 1));
        assertTrue(VanillaWeatherBridge.rearmDue(0),
            "a fresh world or a vanilla weather command leaves the timer at zero");
    }

    @Test
    void decisionsCompareByValue() {
        assertEquals(new Decision(true, false), new Decision(true, false));
        assertEquals(new Decision(true, false).hashCode(), new Decision(true, false).hashCode());
        assertNotEquals(new Decision(true, false), new Decision(true, true));
        assertNotEquals(Decision.CLEAR, new Decision(true, false));
    }
}
