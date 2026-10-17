package com.weathermod.server.climate;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Seasons driven by the day count: a year of a configurable number of days splits into four equal quarters, spring
 * first, and the temperature and humidity offsets blend with a cosine through the four season centres so nothing
 * jumps at a season boundary.
 */
public final class BuiltInSeasonProvider implements SeasonProvider {
    /** Game ticks in one day, the unit the year length is given in. */
    public static final long TICKS_PER_DAY = 24000L;
    /** Number of seasons in a year. */
    public static final int SEASON_COUNT = 4;
    /** Temperature offset at the centre of summer. */
    public static final float SUMMER_TEMPERATURE_OFFSET = 0.30F;
    /** Temperature offset at the centre of winter. */
    public static final float WINTER_TEMPERATURE_OFFSET = -0.35F;

    private static final String[] NAMES = {"spring", "summer", "autumn", "winter"};
    private static final float[] TEMPERATURE_OFFSETS = {0.0F, SUMMER_TEMPERATURE_OFFSET, 0.0F, WINTER_TEMPERATURE_OFFSET};
    private static final float[] HUMIDITY_OFFSETS = {0.10F, -0.05F, 0.05F, -0.10F};
    /** Progress at the centre of spring; the other centres follow every quarter. */
    private static final double FIRST_CENTRE = 0.5 / SEASON_COUNT;

    private final int yearLengthDays;

    /** Creates a provider with a year of the given length in days. Zero or less disables seasons: every answer is {@link SeasonInfo#NONE}. */
    public BuiltInSeasonProvider(int yearLengthDays) {
        this.yearLengthDays = yearLengthDays;
    }

    /** The configured year length in days; zero or less when seasons are disabled. */
    public int yearLengthDays() {
        return yearLengthDays;
    }

    /** True when the year length is positive and seasons change. */
    public boolean isEnabled() {
        return yearLengthDays > 0;
    }

    @Override
    public SeasonInfo current(ServerLevel level, Holder<Biome> biome) {
        return seasonAt(level.getDayTime(), yearLengthDays);
    }

    @Override
    public String id() {
        return isEnabled() ? ID_BUILTIN : ID_NONE;
    }

    /**
     * The season at a level day time for a year of the given length, independent of any level. Progress runs
     * continuously through the year: {@code (dayTime / 24000 mod yearLengthDays) / yearLengthDays} in real
     * arithmetic, so a day boundary is not a jump. A year length of zero or less gives {@link SeasonInfo#NONE}.
     */
    public static SeasonInfo seasonAt(long dayTime, int yearLengthDays) {
        if (yearLengthDays <= 0) {
            return SeasonInfo.NONE;
        }
        long yearTicks = yearLengthDays * TICKS_PER_DAY;
        float progress = (float) (Math.floorMod(dayTime, yearTicks) / (double) yearTicks);
        if (progress >= 1.0F) {
            progress = 0.0F;
        }
        return new SeasonInfo(seasonName(progress), temperatureOffset(progress), humidityOffset(progress), progress);
    }

    /** The name of the quarter a progress falls in: spring, summer, autumn or winter. */
    public static String seasonName(float progress) {
        int index = (int) (wrap(progress) * SEASON_COUNT);
        return NAMES[Math.min(Math.max(index, 0), SEASON_COUNT - 1)];
    }

    /**
     * Temperature offset at a progress: a cosine blend through 0.0 at the centre of spring,
     * {@link #SUMMER_TEMPERATURE_OFFSET} at the centre of summer, 0.0 in autumn and
     * {@link #WINTER_TEMPERATURE_OFFSET} in winter.
     */
    public static float temperatureOffset(float progress) {
        return blend(TEMPERATURE_OFFSETS, progress);
    }

    /** Humidity offset at a progress: a cosine blend through +0.10 spring, -0.05 summer, +0.05 autumn and -0.10 winter. */
    public static float humidityOffset(float progress) {
        return blend(HUMIDITY_OFFSETS, progress);
    }

    /**
     * Interpolates between the values at the season centres. The segment between two centres is blended with a
     * raised cosine, so the curve is flat at each centre and periodic over the year.
     */
    private static float blend(float[] centreValues, float progress) {
        double fromSpringCentre = wrap(progress) - FIRST_CENTRE;
        if (fromSpringCentre < 0.0) {
            fromSpringCentre += 1.0;
        }
        double scaled = fromSpringCentre * SEASON_COUNT;
        int from = Math.min((int) Math.floor(scaled), SEASON_COUNT - 1);
        int to = (from + 1) % SEASON_COUNT;
        double fraction = scaled - from;
        double weight = 0.5 - 0.5 * Math.cos(Math.PI * fraction);
        return (float) (centreValues[from] + (centreValues[to] - centreValues[from]) * weight);
    }

    private static float wrap(float progress) {
        float wrapped = progress % 1.0F;
        if (wrapped < 0.0F) {
            wrapped += 1.0F;
        }
        return wrapped >= 1.0F ? 0.0F : wrapped;
    }

    @Override
    public String toString() {
        return "BuiltInSeasonProvider[" + yearLengthDays + " days]";
    }
}
