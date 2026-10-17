package com.weathermod.server.family;

import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.WindState;
import com.weathermod.server.climate.ClimateField;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * Everything a {@link FamilySystem} needs for one tick of one dimension: the level, its weather data, climate and
 * wind, the simulation tick counter and the random source to roll with. The simulator builds one per level tick and
 * hands it to every storm in that dimension.
 *
 * @param level   the level being simulated
 * @param data    the level's weather data
 * @param climate the level's climate field
 * @param wind    the level's wind, already advanced for this tick
 * @param tick    the dimension's simulation tick counter after this tick's advance
 * @param random  the random source family systems roll with, normally the level's
 */
public record FamilyContext(
    ServerLevel level,
    DimensionWeatherData data,
    ClimateField climate,
    WindState wind,
    int tick,
    RandomSource random
) {
    public FamilyContext {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(climate, "climate");
        Objects.requireNonNull(wind, "wind");
        Objects.requireNonNull(random, "random");
    }
}
