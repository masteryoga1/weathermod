package com.weathermod.server.climate;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The climate of one dimension: biome temperature and downfall with a large, slowly drifting noise anomaly, the
 * season from a {@link SeasonProvider}, the daily swing and the lapse rate above the lowlands. Ultra warm
 * dimensions and dimensions without sky light follow their own fixed rules. Sampling never loads a chunk.
 */
public final class ClimateField {
    /** Noise units per block horizontally: one anomaly cell spans about two thousand blocks. */
    public static final double ANOMALY_SCALE = 1.0 / 2048.0;
    /** Game ticks per noise unit of time, so the anomaly pattern drifts over about four days. */
    public static final double DRIFT_TICKS = 96000.0;

    private final long seed;
    private final SimplexNoise temperatureNoise;
    private final SimplexNoise humidityNoise;
    private final SeasonProvider seasons;

    /**
     * Creates a field from a seed and a season provider. The temperature noise is seeded with the seed, the
     * humidity noise with the seed plus one, so the two anomalies are independent.
     */
    public ClimateField(long seed, SeasonProvider seasons) {
        this.seed = seed;
        this.seasons = Objects.requireNonNull(seasons, "seasons");
        this.temperatureNoise = new SimplexNoise(RandomSource.create(seed));
        this.humidityNoise = new SimplexNoise(RandomSource.create(seed + 1));
    }

    /** Creates the field of a level, seeded with {@link #seedFor(ServerLevel)}. */
    public static ClimateField forLevel(ServerLevel level, SeasonProvider seasons) {
        return new ClimateField(seedFor(level), seasons);
    }

    /** The field seed of a level: the world seed mixed with the dimension id, so every dimension gets its own pattern. */
    public static long seedFor(ServerLevel level) {
        return level.getSeed() ^ level.dimension().location().hashCode();
    }

    /** True for dimensions whose climate follows the sky: lit by it and not ultra warm. Only those have seasons and a daily swing. */
    public static boolean followsSky(DimensionType dimension) {
        return dimension.hasSkyLight() && !dimension.ultraWarm();
    }

    /** The seed the noise was built from. */
    public long seed() {
        return seed;
    }

    /** The season provider this field reads. */
    public SeasonProvider seasons() {
        return seasons;
    }

    /** Raw temperature noise in about {@code [-1, 1]} at a horizontal position and game time. */
    public double temperatureNoise(double x, double z, long gameTime) {
        return temperatureNoise.getValue(x * ANOMALY_SCALE, z * ANOMALY_SCALE, gameTime / DRIFT_TICKS);
    }

    /** Raw humidity noise in about {@code [-1, 1]} at a horizontal position and game time. */
    public double humidityNoise(double x, double z, long gameTime) {
        return humidityNoise.getValue(x * ANOMALY_SCALE, z * ANOMALY_SCALE, gameTime / DRIFT_TICKS);
    }

    /** The scaled temperature anomaly at a horizontal position and game time, see {@link ClimateMath#temperatureAnomaly}. */
    public float temperatureAnomaly(double x, double z, long gameTime) {
        return ClimateMath.temperatureAnomaly(temperatureNoise(x, z, gameTime));
    }

    /** The scaled humidity anomaly at a horizontal position and game time, see {@link ClimateMath#humidityAnomaly}. */
    public float humidityAnomaly(double x, double z, long gameTime) {
        return ClimateMath.humidityAnomaly(humidityNoise(x, z, gameTime));
    }

    /**
     * The climate of the column at a horizontal position: the biome at the surface, which is the motion blocking
     * heightmap when the chunk is loaded and sea level otherwise, without height adjustment. Never loads a chunk.
     */
    public ClimateSample sample(ServerLevel level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        BlockPos surface = new BlockPos(blockX, surfaceY(level, blockX, blockZ), blockZ);
        return sample(level, level.getBiome(surface), x, z);
    }

    /**
     * The climate at a block position: the biome there, height adjusted at {@code pos.getY()} in dimensions that
     * follow the sky. Never loads a chunk.
     */
    public ClimateSample sample(ServerLevel level, BlockPos pos) {
        ClimateSample column = sample(level, level.getBiome(pos), pos.getX() + 0.5, pos.getZ() + 0.5);
        if (!followsSky(level.dimensionType())) {
            return column;
        }
        return column.withTemperature(ClimateMath.heightAdjust(column.temperature(), pos.getY()));
    }

    /**
     * The climate of a known biome at a horizontal position, without height adjustment. Ultra warm dimensions use
     * {@link ClimateMath#ultraWarm}, dimensions without sky light {@link ClimateMath#noSky}, every other dimension
     * {@link ClimateMath#combine} with the season and the time of day.
     */
    public ClimateSample sample(ServerLevel level, Holder<Biome> biome, double x, double z) {
        Biome values = biome.value();
        float baseTemperature = values.getBaseTemperature();
        long gameTime = level.getGameTime();
        float temperatureAnomaly = temperatureAnomaly(x, z, gameTime);

        DimensionType dimension = level.dimensionType();
        if (dimension.ultraWarm()) {
            return ClimateMath.ultraWarm(baseTemperature, temperatureAnomaly);
        }
        if (!dimension.hasSkyLight()) {
            return ClimateMath.noSky(baseTemperature);
        }

        float baseHumidity = values.getModifiedClimateSettings().downfall();
        float humidityAnomaly = humidityAnomaly(x, z, gameTime);
        SeasonInfo season = currentSeason(level, biome);
        return ClimateMath.combine(baseTemperature, baseHumidity, temperatureAnomaly, humidityAnomaly, season,
            ClimateMath.dailyOffset(level.getDayTime()));
    }

    /** The season at a biome in a level: {@link SeasonInfo#NONE} unless the dimension follows the sky. */
    public SeasonInfo season(ServerLevel level, Holder<Biome> biome) {
        return followsSky(level.dimensionType()) ? currentSeason(level, biome) : SeasonInfo.NONE;
    }

    private SeasonInfo currentSeason(ServerLevel level, Holder<Biome> biome) {
        SeasonInfo season = seasons.current(level, biome);
        return season != null ? season : SeasonInfo.NONE;
    }

    /** The motion blocking surface height when the chunk is loaded, else the generator's sea level. Never loads a chunk. */
    private static int surfaceY(ServerLevel level, int blockX, int blockZ) {
        if (level.hasChunk(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ))) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        }
        return level.getChunkSource().getGenerator().getSeaLevel();
    }

    @Override
    public String toString() {
        return "ClimateField[seed=" + seed + ", seasons=" + seasons.id() + "]";
    }
}
