package com.weathermod.core;

import com.weathermod.WeatherMod;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

/**
 * Tag keys owned by the mod. Block tags decide which blocks each tornado tier can rip out of the ground, which
 * blocks no storm may touch and what hail breaks; entity type tags decide what the wind may move and what shields
 * blocks from the ripper; biome tags decide where a weather family or type may form. The tag files live under
 * {@code data/weathermod/tags/}.
 */
public final class ModTags {
    private ModTags() {
    }

    /** Block tags. One {@code rippable/efN} tag per Enhanced Fujita tier, each containing the tier below. */
    public static final class Blocks {
        public static final TagKey<Block> RIPPABLE_EF0 = tag("rippable/ef0");
        public static final TagKey<Block> RIPPABLE_EF1 = tag("rippable/ef1");
        public static final TagKey<Block> RIPPABLE_EF2 = tag("rippable/ef2");
        public static final TagKey<Block> RIPPABLE_EF3 = tag("rippable/ef3");
        public static final TagKey<Block> RIPPABLE_EF4 = tag("rippable/ef4");
        public static final TagKey<Block> RIPPABLE_EF5 = tag("rippable/ef5");
        /** Blocks no vortex of any tier rips; checked before every tier tag. */
        public static final TagKey<Block> NEVER_RIP = tag("never_rip");
        /** The only blocks with a block entity the ripper may destroy; they drop and are never thrown. */
        public static final TagKey<Block> RIPPABLE_WITH_BLOCK_ENTITY = tag("rippable/with_block_entity");
        /** Blocks hail breaks when hail block damage is on: crops and small plants. */
        public static final TagKey<Block> HAIL_BREAKS = tag("hail_breaks");
        /** Glass that strong hail breaks when the glass option is on and the block lies open to the sky. */
        public static final TagKey<Block> HAIL_BREAKS_GLASS = tag("hail_breaks_glass");
        /** Terrain a tornado scours slowly and never digs deep into (RipMath.GROUND_CHANCE_FACTOR, MAX_DIG_BLOCKS). */
        public static final TagKey<Block> NATURAL_GROUND = tag("natural_ground");

        private static final List<TagKey<Block>> RIPPABLE_BY_TIER = List.of(
            RIPPABLE_EF0, RIPPABLE_EF1, RIPPABLE_EF2, RIPPABLE_EF3, RIPPABLE_EF4, RIPPABLE_EF5);

        private Blocks() {
        }

        /** The rippable tag of a tier, 0..5; other values are clamped. */
        public static TagKey<Block> rippable(int tier) {
            return RIPPABLE_BY_TIER.get(Math.max(0, Math.min(RIPPABLE_BY_TIER.size() - 1, tier)));
        }

        private static TagKey<Block> tag(String path) {
            return TagKey.create(Registries.BLOCK, WeatherMod.id(path));
        }
    }

    /** Entity type tags. They carry the compatibility with other mods as optional entries. */
    public static final class EntityTypes {
        /** Entities whose bounding box protects the blocks inside it from the ripper (moving contraptions, glue). */
        public static final TagKey<EntityType<?>> RIP_SHIELD = tag("rip_shield");
        /** Entities that wind, downbursts and vortices never push. */
        public static final TagKey<EntityType<?>> WIND_IMMUNE = tag("wind_immune");

        private EntityTypes() {
        }

        private static TagKey<EntityType<?>> tag(String path) {
            return TagKey.create(Registries.ENTITY_TYPE, WeatherMod.id(path));
        }
    }

    /** Biome tags. {@code has_weather/<name>} marks the biomes a family or a single type may form in. */
    public static final class Biomes {
        public static final TagKey<Biome> HAS_WEATHER_PRECIPITATION = tag("has_weather/precipitation");
        public static final TagKey<Biome> HAS_WEATHER_CONVECTIVE = tag("has_weather/convective");
        /** Biomes a waterspout may form over: the oceans. */
        public static final TagKey<Biome> HAS_WEATHER_WATERSPOUT = tag("has_weather/waterspout");

        private Biomes() {
        }

        private static TagKey<Biome> tag(String path) {
            return TagKey.create(Registries.BIOME, WeatherMod.id(path));
        }
    }
}
