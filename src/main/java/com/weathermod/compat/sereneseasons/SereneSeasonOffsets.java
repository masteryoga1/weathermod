package com.weathermod.compat.sereneseasons;

import com.weathermod.server.climate.SeasonInfo;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping from Serene Seasons season names to the temperature and humidity offsets the climate field applies.
 * It works on plain strings, so it references no Serene Seasons class and is unit tested without that mod.
 */
public final class SereneSeasonOffsets {
    /** Humidity offset of every spring sub-season. */
    public static final float SPRING_HUMIDITY = 0.10F;
    /** Humidity offset of every summer sub-season. */
    public static final float SUMMER_HUMIDITY = -0.05F;
    /** Humidity offset of every autumn sub-season. */
    public static final float AUTUMN_HUMIDITY = 0.05F;
    /** Humidity offset of every winter sub-season. */
    public static final float WINTER_HUMIDITY = -0.10F;
    /** Humidity offset of the tropical wet seasons. */
    public static final float WET_HUMIDITY = 0.25F;
    /** Humidity offset of the tropical dry seasons. */
    public static final float DRY_HUMIDITY = -0.25F;

    /** Temperature offset by Serene Seasons sub-season name, as {@code Season.SubSeason#getSerializedName()} spells it. */
    private static final Map<String, Float> TEMPERATURE = Map.ofEntries(
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

    /** Humidity offset by the season part of a sub-season name. */
    private static final Map<String, Float> HUMIDITY = Map.of(
        "spring", SPRING_HUMIDITY,
        "summer", SUMMER_HUMIDITY,
        "autumn", AUTUMN_HUMIDITY,
        "winter", WINTER_HUMIDITY);

    /** Humidity offset by the wet or dry part of a tropical season name. */
    private static final Map<String, Float> TROPICAL_HUMIDITY = Map.of(
        "wet", WET_HUMIDITY,
        "dry", DRY_HUMIDITY);

    private SereneSeasonOffsets() {
    }

    /**
     * Temperature offset of a sub-season such as {@code "mid_winter"}: a ramp from {@code -0.35} in mid winter to
     * {@code +0.30} in mid summer through {@code 0.0} in mid spring and mid autumn. Unknown or null names give 0.
     */
    public static float temperatureOffset(String subSeasonName) {
        return TEMPERATURE.getOrDefault(normalize(subSeasonName), 0.0F);
    }

    /**
     * Humidity offset of a sub-season, decided by the season part of its name: spring {@code +0.10}, summer
     * {@code -0.05}, autumn {@code +0.05}, winter {@code -0.10}. Unknown or null names give 0.
     */
    public static float humidityOffset(String subSeasonName) {
        return HUMIDITY.getOrDefault(seasonPart(normalize(subSeasonName)), 0.0F);
    }

    /**
     * Humidity offset of a tropical season such as {@code "mid_wet"}: wet seasons {@code +0.25}, dry seasons
     * {@code -0.25}. Tropical seasons never change the temperature. Unknown or null names give 0.
     */
    public static float tropicalHumidityOffset(String tropicalSeasonName) {
        return TROPICAL_HUMIDITY.getOrDefault(seasonPart(normalize(tropicalSeasonName)), 0.0F);
    }

    /** True when the name is one of the twelve Serene Seasons sub-seasons, ignoring case and surrounding blanks. */
    public static boolean isSubSeason(String name) {
        return TEMPERATURE.containsKey(normalize(name));
    }

    /** True when the name ends in a wet or dry tropical season part, ignoring case and surrounding blanks. */
    public static boolean isTropicalSeason(String name) {
        return TROPICAL_HUMIDITY.containsKey(seasonPart(normalize(name)));
    }

    /**
     * Position in the seasonal cycle from Serene Seasons' cycle ticks and cycle length: ticks wrapped into the
     * cycle over its length, so the result is in {@code [0, 1)}. A cycle length of zero or less gives 0.
     */
    public static float progress(int cycleTicks, int cycleDuration) {
        if (cycleDuration <= 0) {
            return 0.0F;
        }
        return Math.floorMod(cycleTicks, cycleDuration) / (float) cycleDuration;
    }

    /**
     * The season info of a standard sub-season: the normalized name with its temperature and humidity offsets.
     * A null or blank name gives {@link SeasonInfo#NONE}.
     */
    public static SeasonInfo standardSeason(String subSeasonName, float progress) {
        String name = normalize(subSeasonName);
        if (name.isEmpty()) {
            return SeasonInfo.NONE;
        }
        return new SeasonInfo(name, temperatureOffset(name), humidityOffset(name), clampProgress(progress));
    }

    /**
     * The season info of a tropical season: the normalized name, no temperature offset and the wet or dry humidity
     * offset. A null or blank name gives {@link SeasonInfo#NONE}.
     */
    public static SeasonInfo tropicalSeason(String tropicalSeasonName, float progress) {
        String name = normalize(tropicalSeasonName);
        if (name.isEmpty()) {
            return SeasonInfo.NONE;
        }
        return new SeasonInfo(name, 0.0F, tropicalHumidityOffset(name), clampProgress(progress));
    }

    /** Lower-cases and trims a name; null becomes the empty string. */
    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** The part after the last underscore: {@code "early_spring"} gives {@code "spring"}, a bare name is returned as is. */
    private static String seasonPart(String normalizedName) {
        int separator = normalizedName.lastIndexOf('_');
        return separator < 0 ? normalizedName : normalizedName.substring(separator + 1);
    }

    /** Clamps a progress value into {@code [0, 1)}; NaN becomes 0. */
    private static float clampProgress(float progress) {
        if (!(progress > 0.0F)) {
            return 0.0F;
        }
        return Math.min(progress, Math.nextDown(1.0F));
    }
}
