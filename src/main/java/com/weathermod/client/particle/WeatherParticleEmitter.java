package com.weathermod.client.particle;

import com.weathermod.WeatherMod;
import com.weathermod.client.ClientWeatherState;
import com.weathermod.client.LocalWeather;
import com.weathermod.client.render.WeatherLevelDriver;
import com.weathermod.config.ClientConfig;
import com.weathermod.core.Headings;
import com.weathermod.core.WeatherParticles;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
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
 * have updated: haze puffs scaled by the driven haze level, dust kicked up from open ground and leaves torn off the
 * canopy, both scaled by the wind. Every count is bounded by the constants in {@link EmitterMath} and every spawn
 * point lies inside the 32-block distance the vanilla particle path accepts. It only reads client state and adds
 * particles to the client level, which honours the player's particle setting; it never touches the world. Game-bus
 * events only touch {@link #INSTANCE}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherParticleEmitter {
    /** Upward velocity of a haze puff in blocks per tick. */
    public static final double HAZE_RISE = 0.002D;
    /**
     * Lowest height above the heightmap a dust puff appears at; a puff is drawn around its position, so any lower
     * and the ground would hide half of it.
     */
    public static final double DUST_MIN_HEIGHT = 0.25D;
    /** Height band above {@link #DUST_MIN_HEIGHT} a dust puff appears in; its top stays below eye height. */
    public static final double DUST_HEIGHT_RANGE = 1.25D;
    /** Initial downward velocity, in blocks per tick, of a leaf that drops from the underside of a canopy. */
    public static final double LEAF_FALL_SPEED = -0.02D;
    /** Smallest upward velocity, in blocks per tick, of a leaf the wind lifts off the top of a canopy. */
    public static final double LEAF_KICK_MIN = 0.04D;
    /** Largest upward velocity, in blocks per tick, of a leaf the wind lifts off the top of a canopy. */
    public static final double LEAF_KICK_MAX = 0.10D;
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
     * config allows them and a wind is known. A density of zero spawns nothing. At most
     * {@code ceil(EmitterMath.HAZE_PER_TICK * hazeStrength * density)} puffs of haze,
     * {@code ceil(EmitterMath.DUST_PER_TICK * density)} puffs of dust and
     * {@code ceil(EmitterMath.LEAF_PER_TICK * density)} leaves are added per tick.
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
            emitDust(level, camX, camY, camZ, wind, density);
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

    private void emitDust(ClientLevel level, double camX, double camY, double camZ, ClientWeatherState wind,
                          double density) {
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
            double y = ground + DUST_MIN_HEIGHT + random.nextDouble() * DUST_HEIGHT_RANGE;
            if (Math.abs(y - camY) > EmitterMath.DUST_VERTICAL_REACH) {
                continue;
            }
            cursor.set(blockX, ground - 1, blockZ);
            BlockState surface = level.getBlockState(cursor);
            if (surface.is(BlockTags.LEAVES) || !surface.getFluidState().isEmpty()) {
                continue;
            }
            cursor.set(blockX, ground, blockZ);
            if (!level.canSeeSky(cursor)) {
                continue;
            }
            level.addParticle(WeatherParticles.WIND_DUST.get(), x, y, z, velocity[0], 0.0D, velocity[1]);
        }
    }

    /**
     * Tears leaves off the canopy around the camera, at the places {@link CanopyMath#shedLeaves} finds. A leaf off
     * the top face of the canopy is kicked upward so it sails over the trees, one off the underside starts falling;
     * both start at the wind's carrying velocity. The wind carries a top leaf away several times faster than the
     * kick lifts it, so leaves that reach down to the ground (a hedge, a pile of leaf blocks), which can only shed
     * off the top, hide their leaves from a viewer who stands close by on the upwind side; from the side and from
     * downwind the same leaves are in plain view.
     */
    private void emitLeaves(ClientLevel level, double camX, double camY, double camZ, ClientWeatherState wind,
                            double density) {
        float windSpeed = wind.windSpeed();
        float gustSpeed = wind.windGustSpeed();
        int wanted = EmitterMath.stochasticCount(EmitterMath.leafExpected(windSpeed, gustSpeed, density), random);
        if (wanted <= 0) {
            return;
        }
        float direction = wind.windDirectionDegrees();
        double[] velocity = EmitterMath.windVelocity(direction, Math.max(windSpeed, gustSpeed),
            EmitterMath.LEAF_DRIFT);
        CanopyMath.shedLeaves(random,
            (blockX, blockZ) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ),
            (blockX, blockY, blockZ) -> kindAt(level, blockX, blockY, blockZ),
            camX, camY, camZ, Headings.directionX(direction), Headings.directionZ(direction), wanted,
            (x, y, z, fromTop) -> {
                double rise = fromTop
                    ? LEAF_KICK_MIN + random.nextDouble() * (LEAF_KICK_MAX - LEAF_KICK_MIN)
                    : LEAF_FALL_SPEED;
                level.addParticle(WeatherParticles.WIND_LEAF.get(), x, y, z, velocity[0], rise, velocity[1]);
            });
    }

    /** What the canopy rules see at a position: air, a leaf block or anything else. */
    private CanopyMath.BlockKind kindAt(ClientLevel level, int blockX, int blockY, int blockZ) {
        BlockState state = level.getBlockState(cursor.set(blockX, blockY, blockZ));
        if (state.isAir()) {
            return CanopyMath.BlockKind.AIR;
        }
        return state.is(BlockTags.LEAVES) ? CanopyMath.BlockKind.LEAVES : CanopyMath.BlockKind.OTHER;
    }
}
