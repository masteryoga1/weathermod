package com.weathermod.config;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings shared by both sides and read before any world exists: which dimensions have weather and
 * whether the fantasy weather pack is active. Stored in {@code config/weathermod-common.toml}.
 */
public final class CommonConfig {
    private static final String TRANSLATION_PREFIX = "weathermod.configuration.";
    private static final List<String> DEFAULT_DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether");

    /** Dimension ids (for example {@code minecraft:overworld}) in which the weather simulation runs. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> enabledDimensions;
    /** Whether weather types from the fantasy family may spawn naturally. */
    public static final ModConfigSpec.BooleanValue fantasyPackEnabled;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        enabledDimensions = builder
                .comment("Dimensions in which the weather simulation runs, as resource locations such as \"minecraft:overworld\".",
                        "Dimensions not listed here never get storms. An empty list disables the simulation everywhere.")
                .translation(TRANSLATION_PREFIX + "enabledDimensions")
                .defineListAllowEmpty("enabledDimensions", DEFAULT_DIMENSIONS,
                        () -> "minecraft:overworld", CommonConfig::isValidDimensionId);

        fantasyPackEnabled = builder
                .comment("Allow weather types from the fantasy family (blood rain, meteor showers and similar) to spawn naturally.")
                .translation(TRANSLATION_PREFIX + "fantasyPackEnabled")
                .define("fantasyPackEnabled", false);

        SPEC = builder.build();
    }

    private CommonConfig() {
    }

    /**
     * Returns whether the weather simulation runs in the given dimension. Falls back to the default list
     * while the config file has not been loaded yet, so callers never see an exception.
     */
    public static boolean isDimensionEnabled(ResourceLocation dimension) {
        String id = dimension.toString();
        if (!SPEC.isLoaded()) {
            return DEFAULT_DIMENSIONS.contains(id);
        }
        for (String entry : enabledDimensions.get()) {
            if (id.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    /** Convenience overload for a dimension key such as {@code Level.OVERWORLD}. */
    public static boolean isDimensionEnabled(ResourceKey<Level> dimension) {
        return isDimensionEnabled(dimension.location());
    }

    /** Validator for list entries: accepts any string that parses as a resource location. */
    private static boolean isValidDimensionId(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }
}
