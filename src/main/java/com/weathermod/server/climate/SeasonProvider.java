package com.weathermod.server.climate;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Source of the current season for a level and biome. The built-in provider derives it from the day count; the
 * Serene Seasons provider reads that mod's state when it is installed. {@link SeasonProviders#create} picks one.
 */
public interface SeasonProvider {
    /** Id of the built-in day-count provider. */
    String ID_BUILTIN = "builtin";
    /** Id of the Serene Seasons provider. */
    String ID_SERENE_SEASONS = "sereneseasons";
    /** Id reported when seasons are switched off. */
    String ID_NONE = "none";

    /**
     * The season at the biome in the level. Never null: {@link SeasonInfo#NONE} when the provider has nothing to
     * say. Dimension rules are applied by {@link ClimateField}, so providers do not filter dimensions themselves.
     */
    SeasonInfo current(ServerLevel level, Holder<Biome> biome);

    /** A short id for display: {@link #ID_BUILTIN}, {@link #ID_SERENE_SEASONS} or {@link #ID_NONE}. */
    String id();
}
