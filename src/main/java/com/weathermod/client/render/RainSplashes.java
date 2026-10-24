package com.weathermod.client.render;

import com.weathermod.client.LocalConditions;
import com.weathermod.client.LocalWeather;
import com.weathermod.config.ClientConfig;
import com.weathermod.core.PrecipitationKind;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The tick half of the vanilla rain hook: the splash particles that hit the ground around the camera while the mod
 * says it rains or sleets there. The vanilla algorithm minus its biome gate and its sounds, which the ambience owns,
 * scaled by {@code ClientConfig.particleDensity}.
 */
public final class RainSplashes {
    /** Value of {@code ClientConfig.particleDensity} assumed while the client config has not been loaded. */
    public static final double DEFAULT_PARTICLE_DENSITY = 1.0D;

    /** Per-tick seed multiplier of the splash random source (vanilla). */
    private static final long TICK_SEED = 312987231L;
    /** Splashes land within this many blocks of the camera on each horizontal axis (vanilla). */
    private static final int SPREAD = 10;
    /** Splashes land within this many blocks above or below the camera (vanilla). */
    private static final int HEIGHT_WINDOW = 10;
    /** Splashes per tick at full rain with fancy graphics before the density and particle option scaling (vanilla). */
    private static final float SPLASHES_AT_FULL_RAIN = 100.0F;

    private RainSplashes() {
    }

    /** The client config's particle density multiplier; {@link #DEFAULT_PARTICLE_DENSITY} while it is not loaded. */
    public static double particleDensity() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.particleDensity.get() : DEFAULT_PARTICLE_DENSITY;
    }

    /**
     * The tickRain hook body. Spawns the splash particles for this tick where the mod says it rains or sleets and
     * returns true so vanilla ticks nothing of its own, even when nothing was spawned. {@link WeatherDimensionEffects}
     * only calls it while the mod manages the weather here; in an unmanaged dimension it returns false itself so
     * vanilla ticks its own splashes and rain sounds.
     *
     * @param level  the client level
     * @param ticks  the level renderer's tick counter
     * @param camera the camera the splashes land around
     * @return true when this ticker handled the tick, false to let vanilla tick
     */
    public static boolean tick(ClientLevel level, int ticks, Camera camera) {
        LocalConditions conditions = LocalWeather.INSTANCE.conditions();
        PrecipitationKind kind = conditions.kind();
        if (kind != PrecipitationKind.RAIN && kind != PrecipitationKind.SLEET) {
            return true;
        }
        float rain = WeatherLevelDriver.INSTANCE.rain() / (Minecraft.useFancyGraphics() ? 1.0F : 2.0F);
        if (rain <= 0.0F) {
            return true;
        }

        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        int count = (int) (SPLASHES_AT_FULL_RAIN * rain * rain * particleDensity())
            / (status == ParticleStatus.DECREASED ? 2 : 1);
        if (count <= 0) {
            return true;
        }

        RandomSource random = RandomSource.create((long) ticks * TICK_SEED);
        BlockPos cameraPos = BlockPos.containing(camera.getPosition());
        int minY = level.getMinBuildHeight();

        for (int i = 0; i < count; i++) {
            int dx = random.nextInt(2 * SPREAD + 1) - SPREAD;
            int dz = random.nextInt(2 * SPREAD + 1) - SPREAD;
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, cameraPos.offset(dx, 0, dz));
            if (surface.getY() <= minY
                || surface.getY() > cameraPos.getY() + HEIGHT_WINDOW
                || surface.getY() < cameraPos.getY() - HEIGHT_WINDOW) {
                continue;
            }
            BlockPos ground = surface.below();
            double offsetX = random.nextDouble();
            double offsetZ = random.nextDouble();
            BlockState blockState = level.getBlockState(ground);
            FluidState fluidState = level.getFluidState(ground);
            VoxelShape shape = blockState.getCollisionShape(level, ground);
            double shapeTop = shape.max(Direction.Axis.Y, offsetX, offsetZ);
            double fluidTop = (double) fluidState.getHeight(level, ground);
            double top = Math.max(shapeTop, fluidTop);
            ParticleOptions particle = !fluidState.is(FluidTags.LAVA)
                && !blockState.is(Blocks.MAGMA_BLOCK)
                && !CampfireBlock.isLitCampfire(blockState)
                ? ParticleTypes.RAIN
                : ParticleTypes.SMOKE;
            level.addParticle(particle, (double) ground.getX() + offsetX, (double) ground.getY() + top,
                (double) ground.getZ() + offsetZ, 0.0D, 0.0D, 0.0D);
            if (status == ParticleStatus.MINIMAL) {
                break;
            }
        }
        return true;
    }
}
