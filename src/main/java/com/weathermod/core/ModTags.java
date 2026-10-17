package com.weathermod.core;

import com.weathermod.WeatherMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

/**
 * Tag keys owned by the mod. Block tags decide which blocks each tornado tier can rip out of the
 * ground; biome tags decide where a weather family may form.
 */
public final class ModTags {
    private ModTags() {
    }

    /** Block tags. One {@code rippable/efN} tag per Enhanced Fujita tier. */
    public static final class Blocks {
        public static final TagKey<Block> RIPPABLE_EF0 = tag("rippable/ef0");
        public static final TagKey<Block> RIPPABLE_EF1 = tag("rippable/ef1");
        public static final TagKey<Block> RIPPABLE_EF2 = tag("rippable/ef2");
        public static final TagKey<Block> RIPPABLE_EF3 = tag("rippable/ef3");
        public static final TagKey<Block> RIPPABLE_EF4 = tag("rippable/ef4");
        public static final TagKey<Block> RIPPABLE_EF5 = tag("rippable/ef5");

        private Blocks() {
        }

        private static TagKey<Block> tag(String path) {
            return TagKey.create(Registries.BLOCK, WeatherMod.id(path));
        }
    }

    /** Biome tags. {@code has_weather/<family>} marks the biomes a family may form in. */
    public static final class Biomes {
        public static final TagKey<Biome> HAS_WEATHER_PRECIPITATION = tag("has_weather/precipitation");
        public static final TagKey<Biome> HAS_WEATHER_CONVECTIVE = tag("has_weather/convective");

        private Biomes() {
        }

        private static TagKey<Biome> tag(String path) {
            return TagKey.create(Registries.BIOME, WeatherMod.id(path));
        }
    }
}
