package com.weathermod.client;

import com.weathermod.core.Coverage;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.WeatherType;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * Pure arithmetic that turns the storms the client knows into the {@link LocalConditions} at one point. It needs no
 * level and no registry, only a lookup from type id to {@link WeatherType}, so it is unit-tested directly.
 */
public final class LocalWeatherMath {
    /**
     * Temperature assumed while no climate update has arrived. It lies at or above
     * {@link PrecipitationKind#RAIN_FROM}, so a type in auto mode falls as rain until the server says otherwise.
     */
    public static final float UNKNOWN_TEMPERATURE = 0.5F;

    private LocalWeatherMath() {
    }

    /**
     * Screen blend {@code 1 - (1 - a)(1 - b)}: combines two overlapping contributions in {@code [0, 1]} so the
     * result grows with each one but never exceeds 1, and a zero contribution leaves the other unchanged.
     */
    public static float screen(float a, float b) {
        return 1.0F - (1.0F - a) * (1.0F - b);
    }

    /**
     * Combines every storm whose type the lookup resolves (a {@code null} type skips the storm). For each storm the
     * coverage is {@link Coverage#coverage(float, double, float)} of its interpolated intensity, the horizontal
     * distance from {@code (x, z)} to its interpolated centre and its radius; rain, thunder and haze are
     * screen-combined from the type's visuals scaled by that coverage; the coverage reported is the largest single
     * one. The dominant storm is the covering storm whose type {@link WeatherType#precipitates() precipitates} with
     * the largest {@code rainLevel * coverage} (the first such storm wins a tie), and the kind is its precipitation
     * mode resolved at the temperature; without such a storm the kind is {@link PrecipitationKind#NONE}.
     *
     * @param storms      the storms to consider, in the order ties are broken
     * @param types       resolves a type id to its type, or to {@code null} when the id is unknown
     * @param x           the point's x
     * @param z           the point's z
     * @param partialTick interpolation between the storms' previous and current tick
     * @param temperature the temperature at the point on the vanilla biome scale, {@link #UNKNOWN_TEMPERATURE} when
     *                    no climate is known
     */
    public static LocalConditions compute(Collection<ClientStorm> storms, Function<ResourceLocation, WeatherType> types,
                                          double x, double z, float partialTick, float temperature) {
        Objects.requireNonNull(storms, "storms");
        Objects.requireNonNull(types, "types");
        float rain = 0.0F;
        float thunder = 0.0F;
        float haze = 0.0F;
        float coverage = 0.0F;
        WeatherType dominantType = null;
        int dominantId = -1;
        float dominantWeight = 0.0F;
        for (ClientStorm storm : storms) {
            WeatherType type = types.apply(storm.typeId());
            if (type == null) {
                continue;
            }
            double dx = x - storm.getX(partialTick);
            double dz = z - storm.getZ(partialTick);
            float cov = Coverage.coverage(storm.getIntensity(partialTick), Math.sqrt(dx * dx + dz * dz),
                storm.radius());
            if (cov <= 0.0F) {
                continue;
            }
            WeatherType.Visuals visuals = type.visuals();
            float rainContribution = clampUnit(visuals.rainLevel() * cov);
            rain = screen(rain, rainContribution);
            thunder = screen(thunder, clampUnit(visuals.thunderLevel() * cov));
            haze = screen(haze, clampUnit(visuals.hazeStrength() * cov));
            if (cov > coverage) {
                coverage = cov;
            }
            if (type.precipitates() && (dominantType == null || rainContribution > dominantWeight)) {
                dominantType = type;
                dominantId = storm.id();
                dominantWeight = rainContribution;
            }
        }
        PrecipitationKind kind = dominantType == null
            ? PrecipitationKind.NONE
            : PrecipitationKind.resolve(dominantType.precipitation(), temperature);
        return new LocalConditions(rain, thunder, haze, kind, coverage, dominantId);
    }

    private static float clampUnit(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
