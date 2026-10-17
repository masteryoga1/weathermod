package com.weathermod.compat.sereneseasons;

import com.weathermod.WeatherMod;
import com.weathermod.server.climate.SeasonInfo;
import com.weathermod.server.climate.SeasonProvider;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

/**
 * Season provider that follows Serene Seasons: tropical biomes report that mod's wet or dry season with a humidity
 * offset only, every other biome its sub-season with the offsets from {@link SereneSeasonOffsets}. This class links
 * against the Serene Seasons API, so it is only loaded through {@link SereneSeasonsCompat#createProvider()}.
 */
public final class SereneSeasonsSeasonProvider implements SeasonProvider {
    private boolean failureReported;

    /**
     * The Serene Seasons season at the biome. Dimensions are not filtered here: the climate field already leaves
     * out ultra warm dimensions and dimensions without sky light. Should Serene Seasons fail to answer, the failure
     * is logged once and {@link SeasonInfo#NONE} is reported.
     */
    @Override
    public SeasonInfo current(ServerLevel level, Holder<Biome> biome) {
        try {
            return read(level, biome);
        } catch (RuntimeException | LinkageError e) {
            if (!failureReported) {
                failureReported = true;
                WeatherMod.LOGGER.warn("Serene Seasons gave no season state for {}; reporting no season",
                    level.dimension().location(), e);
            }
            return SeasonInfo.NONE;
        }
    }

    private static SeasonInfo read(ServerLevel level, Holder<Biome> biome) {
        ISeasonState state = SeasonHelper.getSeasonState(level);
        if (state == null) {
            return SeasonInfo.NONE;
        }
        float progress = SereneSeasonOffsets.progress(state.getSeasonCycleTicks(), state.getCycleDuration());
        if (SeasonHelper.usesTropicalSeasons(biome)) {
            Season.TropicalSeason tropical = state.getTropicalSeason();
            if (tropical != null) {
                return SereneSeasonOffsets.tropicalSeason(tropical.name().toLowerCase(Locale.ROOT), progress);
            }
        }
        Season.SubSeason subSeason = state.getSubSeason();
        if (subSeason == null) {
            return SeasonInfo.NONE;
        }
        return SereneSeasonOffsets.standardSeason(subSeason.getSerializedName(), progress);
    }

    @Override
    public String id() {
        return SeasonProvider.ID_SERENE_SEASONS;
    }

    @Override
    public String toString() {
        return "SereneSeasonsSeasonProvider";
    }
}
