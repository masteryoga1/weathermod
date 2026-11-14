package com.weathermod.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.util.ExtraCodecs;

/**
 * The {@code vortex} block of a weather type: the kind of vortex, its EF tier, the geometry of its funnel and the
 * numbers of the Rankine force field the server, the local player and the debris particles all compute with.
 * Every type of the vortex family carries one. Only {@code kind} is required in JSON. Radii and the pull, lift and
 * wind values describe the vortex at full strength; the force model scales them with the storm's intensity.
 *
 * @param kind             what the vortex is
 * @param tier             EF tier, 0 to 5: rip tag, hardness ceiling, sample depth and shelter factor
 * @param coreRadius       Rankine core radius at the ground, in blocks
 * @param topRadius        funnel radius at its top, in blocks; at least the core radius
 * @param influenceRadius  beyond this the vortex does nothing, in blocks; at least the top radius
 * @param height           the range the funnel height is picked from, in blocks; 0..0 means "up to the cloud base"
 * @param windSpeed        tangential speed of an airborne body at the core radius, in blocks per second
 * @param pull             inward acceleration at the core radius, in blocks per tick squared
 * @param lift             upward acceleration at the core radius, in blocks per tick squared
 * @param riseCap          no lift is added above this upward speed, in blocks per tick
 * @param ejectHeight      lift stops this far above the ground, in blocks; 0 means the funnel top
 * @param ripShare         share of the block budget per tick this vortex may rip, 0 to 1; 0 rips nothing
 * @param flyingBlockShare share of the flying block cap this vortex may keep airborne, 0 to 1
 * @param throwSpeed       launch speed of a flying block, in blocks per second
 * @param minSpeed         slowest ground speed, in blocks per second
 * @param maxSpeed         fastest ground speed, in blocks per second; at least the slowest
 */
public record VortexParams(VortexKind kind, int tier, float coreRadius, float topRadius, float influenceRadius,
                           WeatherType.Range height, float windSpeed, float pull, float lift, float riseCap,
                           float ejectHeight, float ripShare, float flyingBlockShare, float throwSpeed,
                           float minSpeed, float maxSpeed) {
    private static final int HIGHEST_TIER = 5;
    private static final int DEFAULT_TIER = 0;
    private static final float DEFAULT_CORE_RADIUS_BLOCKS = 3.0F;
    private static final float DEFAULT_TOP_RADIUS_BLOCKS = 10.0F;
    private static final float DEFAULT_INFLUENCE_RADIUS_BLOCKS = 24.0F;
    private static final WeatherType.Range CLOUD_BASE_HEIGHT = WeatherType.Range.of(0.0F);
    private static final float DEFAULT_WIND_SPEED = 8.0F;
    private static final float DEFAULT_PULL = 0.034F;
    private static final float DEFAULT_LIFT = 0.05F;
    private static final float DEFAULT_RISE_CAP = 0.15F;
    private static final float DEFAULT_EJECT_HEIGHT_BLOCKS = 4.0F;
    private static final float DEFAULT_RIP_SHARE = 0.0F;
    private static final float DEFAULT_FLYING_BLOCK_SHARE = 0.0F;
    private static final float DEFAULT_THROW_SPEED = 6.0F;
    private static final float DEFAULT_MIN_SPEED = 0.0F;

    private static final Codec<Float> NON_NEGATIVE = Codec.floatRange(0.0F, Float.MAX_VALUE);
    private static final Codec<Float> FRACTION = Codec.floatRange(0.0F, 1.0F);

    /**
     * JSON codec. {@code kind} is required; every other key is optional, falls back to the default of a weak
     * rope-like vortex and is omitted when encoding that default. Keys are snake case: {@code core_radius},
     * {@code flying_block_share} and so on. The group holds 16 fields, the most the codec library allows: a
     * further setting needs a nested record.
     */
    public static final Codec<VortexParams> CODEC = RecordCodecBuilder.<VortexParams>create(instance -> instance.group(
        VortexKind.CODEC.fieldOf("kind").forGetter(VortexParams::kind),
        Codec.intRange(0, HIGHEST_TIER).optionalFieldOf("tier", DEFAULT_TIER).forGetter(VortexParams::tier),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("core_radius", DEFAULT_CORE_RADIUS_BLOCKS)
            .forGetter(VortexParams::coreRadius),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("top_radius", DEFAULT_TOP_RADIUS_BLOCKS)
            .forGetter(VortexParams::topRadius),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("influence_radius", DEFAULT_INFLUENCE_RADIUS_BLOCKS)
            .forGetter(VortexParams::influenceRadius),
        WeatherType.Range.CODEC.optionalFieldOf("height", CLOUD_BASE_HEIGHT).forGetter(VortexParams::height),
        NON_NEGATIVE.optionalFieldOf("wind_speed", DEFAULT_WIND_SPEED).forGetter(VortexParams::windSpeed),
        NON_NEGATIVE.optionalFieldOf("pull", DEFAULT_PULL).forGetter(VortexParams::pull),
        NON_NEGATIVE.optionalFieldOf("lift", DEFAULT_LIFT).forGetter(VortexParams::lift),
        NON_NEGATIVE.optionalFieldOf("rise_cap", DEFAULT_RISE_CAP).forGetter(VortexParams::riseCap),
        NON_NEGATIVE.optionalFieldOf("eject_height", DEFAULT_EJECT_HEIGHT_BLOCKS)
            .forGetter(VortexParams::ejectHeight),
        FRACTION.optionalFieldOf("rip_share", DEFAULT_RIP_SHARE).forGetter(VortexParams::ripShare),
        FRACTION.optionalFieldOf("flying_block_share", DEFAULT_FLYING_BLOCK_SHARE)
            .forGetter(VortexParams::flyingBlockShare),
        NON_NEGATIVE.optionalFieldOf("throw_speed", DEFAULT_THROW_SPEED).forGetter(VortexParams::throwSpeed),
        NON_NEGATIVE.optionalFieldOf("min_speed", DEFAULT_MIN_SPEED).forGetter(VortexParams::minSpeed),
        NON_NEGATIVE.optionalFieldOf("max_speed", WeatherType.UNLIMITED_SPEED).forGetter(VortexParams::maxSpeed)
    ).apply(instance, VortexParams::new)).validate(VortexParams::validate);

    public VortexParams {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(height, "height");
    }

    private static DataResult<VortexParams> validate(VortexParams params) {
        if (params.topRadius < params.coreRadius) {
            return DataResult.error(() -> "top_radius " + params.topRadius
                + " must be at least core_radius " + params.coreRadius);
        }
        if (params.influenceRadius < params.topRadius) {
            return DataResult.error(() -> "influence_radius " + params.influenceRadius
                + " must be at least top_radius " + params.topRadius);
        }
        if (params.height.min() < 0.0F) {
            return DataResult.error(() -> "height must not be negative, got " + params.height);
        }
        if (params.maxSpeed < params.minSpeed) {
            return DataResult.error(() -> "max_speed " + params.maxSpeed
                + " must be at least min_speed " + params.minSpeed);
        }
        return DataResult.success(params);
    }

    /** True when {@code height} is 0..0: the funnel reaches from the ground to the cloud base. */
    public boolean reachesCloudBase() {
        return height.min() == 0.0F && height.max() == 0.0F;
    }
}
