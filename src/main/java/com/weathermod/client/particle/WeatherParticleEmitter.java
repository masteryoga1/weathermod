package com.weathermod.client.particle;

import com.weathermod.WeatherMod;
import com.weathermod.client.ClientWeatherState;
import com.weathermod.client.LocalWeather;
import com.weathermod.client.render.WeatherLevelDriver;
import com.weathermod.config.ClientConfig;
import com.weathermod.core.WeatherParticles;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Spawns the weather particles around the camera once per client tick, after the local weather and the level driver
 * have updated: haze puffs scaled by the driven haze level, dust and leaves scaled by the wind. It only reads client
 * state and adds particles to the client level, which honours the player's particle setting; it never touches the
 * world. Game-bus events only touch {@link #INSTANCE}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherParticleEmitter {
    /** Upward velocity of a haze puff in blocks per tick. */
    public static final double HAZE_RISE = 0.002D;
    /** Lowest height above the heightmap a dust speck appears at. */
    public static final double DUST_MIN_HEIGHT = 0.1D;
    /** Height band above {@link #DUST_MIN_HEIGHT} a dust speck appears in. */
    public static final double DUST_HEIGHT_RANGE = 1.1D;
    /** Leaf tries reach this far below the camera. */
    public static final int LEAF_BELOW_CAMERA = 8;
    /** Leaf tries reach this far above the camera. */
    public static final int LEAF_ABOVE_CAMERA = 12;
    /** Initial downward velocity of a leaf in blocks per tick. */
    public static final double LEAF_FALL_SPEED = -0.02D;
    /** How far below the bottom of its leaf block a leaf appears. */
    public static final double LEAF_DROP = 0.1D;
    /** Value of {@code ClientConfig.hazeParticles} assumed while the client config has not been loaded. */
    public static final boolean DEFAULT_HAZE_PARTICLES = true;
    /** Value of {@code ClientConfig.windParticles} assumed while the client config has not been loaded. */
    public static final boolean DEFAULT_WIND_PARTICLES = true;
    /** Value of {@code ClientConfig.particleDensity} assumed while the client config has not been loaded. */
    public static final double DEFAULT_PARTICLE_DENSITY = 1.0D;
    /** Value of {@code ClientConfig.hazeStrength} assumed while the client config has not been loaded. */
    public static final double DEFAULT_HAZE_STRENGTH = 1.0D;
    /** The emitter used by the running client. */
    public static final WeatherParticleEmitter INSTANCE = new WeatherParticleEmitter();

    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    WeatherParticleEmitter() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalWeather local = LocalWeather.INSTANCE;
        if (level == null || minecraft.isPaused() || !local.active()) {
            return;
        }
        INSTANCE.tick(level, local.cameraX(), local.cameraY(), local.cameraZ(), ClientWeatherState.INSTANCE,
            WeatherLevelDriver.INSTANCE.haze());
    }

    /** Whether the client config asks for haze particles; {@link #DEFAULT_HAZE_PARTICLES} while it is not loaded. */
    public static boolean isHazeEnabledByConfig() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.hazeParticles.get() : DEFAULT_HAZE_PARTICLES;
    }

    /** Whether the client config asks for wind particles; {@link #DEFAULT_WIND_PARTICLES} while it is not loaded. */
    public static boolean isWindEnabledByConfig() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.windParticles.get() : DEFAULT_WIND_PARTICLES;
    }

    /** The configured particle density multiplier; {@link #DEFAULT_PARTICLE_DENSITY} while the config is not loaded. */
    public static double particleDensity() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.particleDensity.get() : DEFAULT_PARTICLE_DENSITY;
    }

    /** The configured haze strength multiplier; {@link #DEFAULT_HAZE_STRENGTH} while the config is not loaded. */
    public static double hazeStrength() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.hazeStrength.get() : DEFAULT_HAZE_STRENGTH;
    }

    /**
     * Spawns this tick's particles around a camera position: haze when the config allows it, dust and leaves when the
     * config allows them and a wind is known. A density of zero spawns nothing.
     *
     * @param level the level to add particles to
     * @param camX  camera x
     * @param camY  camera y
     * @param camZ  camera z
     * @param wind  the state whose wind drives dust, leaves and haze drift
     * @param haze  the driven haze level in {@code [0, 1]}
     */
    public void tick(ClientLevel level, double camX, double camY, double camZ, ClientWeatherState wind, float haze) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(wind, "wind");
        double density = particleDensity();
        if (!(density > 0.0D)) {
            return;
        }
        if (isHazeEnabledByConfig()) {
            emitHaze(level, camX, camY, camZ, wind, haze, density);
        }
        if (isWindEnabledByConfig() && wind.hasWind()) {
            emitDust(level, camX, camZ, wind, density);
            emitLeaves(level, camX, camY, camZ, wind, density);
        }
    }

    private void emitHaze(ClientLevel level, double camX, double camY, double camZ, ClientWeatherState wind,
                          float haze, double density) {
        int count = EmitterMath.stochasticCount(EmitterMath.hazeExpected(haze, hazeStrength(), density), random);
        if (count <= 0) {
            return;
        }
        double[] velocity = wind.hasWind()
            ? EmitterMath.windVelocity(wind.windDirectionDegrees(), wind.windSpeed(), EmitterMath.HAZE_DRIFT)
            : new double[] {0.0D, 0.0D};
        double lowest = camY - EmitterMath.HAZE_BELOW_CAMERA;
        double highest = camY + EmitterMath.HAZE_ABOVE_CAMERA;
        for (int i = 0; i < count; i++) {
            double[] offset = EmitterMath.ringOffset(random, EmitterMath.HAZE_MIN_DISTANCE,
                EmitterMath.HAZE_MAX_DISTANCE);
            double x = camX + offset[0];
            double z = camZ + offset[1];
            int blockX = Mth.floor(x);
            int blockZ = Mth.floor(z);
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            double y = ground + EmitterMath.HAZE_RISE_MIN
                + random.nextDouble() * (EmitterMath.HAZE_RISE_MAX - EmitterMath.HAZE_RISE_MIN);
            if (y < lowest || y > highest) {
                continue;
            }
            cursor.set(blockX, Mth.floor(y), blockZ);
            if (!level.canSeeSky(cursor)) {
                continue;
            }
            level.addParticle(WeatherParticles.HAZE.get(), x, y, z, velocity[0], HAZE_RISE, velocity[1]);
        }
    }

    private void emitDust(ClientLevel level, double camX, double camZ, ClientWeatherState wind, double density) {
        float windSpeed = wind.windSpeed();
        float gustSpeed = wind.windGustSpeed();
        int count = EmitterMath.stochasticCount(EmitterMath.dustExpected(windSpeed, gustSpeed, density), random);
        if (count <= 0) {
            return;
        }
        double[] velocity = EmitterMath.windVelocity(wind.windDirectionDegrees(), Math.max(windSpeed, gustSpeed),
            EmitterMath.DUST_DRIFT);
        int minBuildHeight = level.getMinBuildHeight();
        for (int i = 0; i < count; i++) {
            double[] offset = EmitterMath.ringOffset(random, EmitterMath.DUST_MIN_DISTANCE,
                EmitterMath.DUST_MAX_DISTANCE);
            double x = camX + offset[0];
            double z = camZ + offset[1];
            int blockX = Mth.floor(x);
            int blockZ = Mth.floor(z);
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            if (ground <= minBuildHeight) {
                continue;
            }
            cursor.set(blockX, ground - 1, blockZ);
            BlockState surface = level.getBlockState(cursor);
            if (surface.is(BlockTags.LEAVES) || surface.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            cursor.set(blockX, ground, blockZ);
            if (!level.canSeeSky(cursor)) {
                continue;
            }
            double y = ground + DUST_MIN_HEIGHT + random.nextDouble() * DUST_HEIGHT_RANGE;
            level.addParticle(WeatherParticles.WIND_DUST.get(), x, y, z, velocity[0], 0.0D, velocity[1]);
        }
    }

    private void emitLeaves(ClientLevel level, double camX, double camY, double camZ, ClientWeatherState wind,
                            double density) {
        float windSpeed = wind.windSpeed();
        if (windSpeed <= EmitterMath.LEAF_MIN_WIND) {
            return;
        }
        float chance = EmitterMath.leafChance(windSpeed, density);
        if (chance <= 0.0F) {
            return;
        }
        double[] velocity = EmitterMath.windVelocity(wind.windDirectionDegrees(), windSpeed, EmitterMath.LEAF_DRIFT);
        int cameraY = Mth.floor(camY);
        int verticalSpan = LEAF_BELOW_CAMERA + LEAF_ABOVE_CAMERA + 1;
        for (int i = 0; i < EmitterMath.LEAF_TRIES; i++) {
            int blockX = Mth.floor(camX + (random.nextDouble() * 2.0D - 1.0D) * EmitterMath.LEAF_DISTANCE);
            int blockZ = Mth.floor(camZ + (random.nextDouble() * 2.0D - 1.0D) * EmitterMath.LEAF_DISTANCE);
            int blockY = cameraY - LEAF_BELOW_CAMERA + random.nextInt(verticalSpan);
            cursor.set(blockX, blockY, blockZ);
            if (!level.getBlockState(cursor).is(BlockTags.LEAVES)) {
                continue;
            }
            cursor.set(blockX, blockY - 1, blockZ);
            if (!level.getBlockState(cursor).isAir()) {
                continue;
            }
            if (random.nextFloat() >= chance) {
                continue;
            }
            level.addParticle(WeatherParticles.WIND_LEAF.get(), blockX + 0.5D, blockY - LEAF_DROP, blockZ + 0.5D,
                velocity[0], LEAF_FALL_SPEED, velocity[1]);
        }
    }
}
