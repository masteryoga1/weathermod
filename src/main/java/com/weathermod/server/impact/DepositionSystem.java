package com.weathermod.server.impact;

import com.weathermod.core.Coverage;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.climate.ClimateSample;
import com.weathermod.server.family.FamilyContext;
import com.weathermod.server.family.PrecipitationSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Lays snow and freezes water under snow storms, the way {@code ServerLevel.tickPrecipitation} does under vanilla
 * rain but driven by the mod's climate instead of the biome's fixed temperature. Rain and sleet change nothing here:
 * vanilla's own precipitation tick handles them while the bridge keeps the rain flag on. Chunks are never loaded.
 */
public final class DepositionSystem {
    /** Vanilla refuses to lay snow or ice where block light is this bright or brighter, and so does this system. */
    public static final int MAX_BLOCK_LIGHT = 10;

    private DepositionSystem() {
    }

    /**
     * Makes up to {@code budget} attempts under the storm: each picks a point uniformly in the storm's disc, skips
     * it unless its chunk is loaded and a coverage roll accepts it, samples the climate at the surface there and,
     * when the type drops snow at that temperature, lays a snow layer and freezes the water below. Both halves obey
     * their config toggles. Returns how many blocks changed.
     */
    public static int deposit(StormInstance storm, WeatherType type, FamilyContext ctx, int budget) {
        if (budget <= 0 || !type.precipitates() || storm.intensity() <= 0.0F) {
            return 0;
        }
        boolean snow = WeatherManager.snowAccumulation();
        boolean ice = WeatherManager.iceFormation();
        if (!snow && !ice) {
            return 0;
        }
        ServerLevel level = ctx.level();
        RandomSource random = ctx.random();
        int layersTarget = snow
            ? DepositionMath.snowLayersTarget(level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT))
            : 0;
        int seaLevel = level.getSeaLevel();
        int changed = 0;
        for (int attempt = 0; attempt < budget; attempt++) {
            double[] point = DepositionMath.randomPointInDisc(storm.x(), storm.z(), storm.radius(), random);
            BlockPos column = BlockPos.containing(point[0], seaLevel, point[1]);
            if (!level.isLoaded(column)) {
                continue;
            }
            float coverage = Coverage.coverage(storm.intensity(), storm.distanceTo(point[0], point[1]), storm.radius());
            if (!DepositionMath.accept(coverage, random)) {
                continue;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            ClimateSample climate = ctx.climate().sample(level, surface);
            if (PrecipitationSystem.kindAt(type, climate) != PrecipitationKind.SNOW) {
                continue;
            }
            if (layersTarget > 0 && laySnow(level, surface, layersTarget)) {
                changed++;
            }
            if (ice && freezeWater(level, surface.below(), climate.temperature())) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Lays snow at a surface position the way vanilla does under rain in a cold biome: a fresh layer on air, one
     * more layer on existing snow up to the target, pushing entities up with it. Snow needs a surface it can
     * survive on and block light below {@link #MAX_BLOCK_LIGHT}. Returns whether a block changed.
     */
    public static boolean laySnow(ServerLevel level, BlockPos surface, int layersTarget) {
        if (layersTarget <= 0 || !level.isLoaded(surface)) {
            return false;
        }
        if (level.getBrightness(LightLayer.BLOCK, surface) >= MAX_BLOCK_LIGHT) {
            return false;
        }
        BlockState state = level.getBlockState(surface);
        if (!(state.isAir() || state.is(Blocks.SNOW)) || !Blocks.SNOW.defaultBlockState().canSurvive(level, surface)) {
            return false;
        }
        if (state.is(Blocks.SNOW)) {
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers >= Math.min(layersTarget, SnowLayerBlock.MAX_HEIGHT)) {
                return false;
            }
            BlockState taller = state.setValue(SnowLayerBlock.LAYERS, layers + 1);
            Block.pushEntitiesUp(state, taller, level, surface);
            return level.setBlockAndUpdate(surface, taller);
        }
        return level.setBlockAndUpdate(surface, Blocks.SNOW.defaultBlockState());
    }

    /**
     * Freezes still water the way vanilla does in a cold biome: the position must hold a source water block (not a
     * waterlogged block), lie in dim block light and touch something other than water on at least one side so lakes
     * freeze from the shore inward. Reads the four neighbours, so it refuses unless they are loaded. Returns whether
     * a block changed.
     */
    public static boolean freezeWater(ServerLevel level, BlockPos water, float temperatureAtSurface) {
        if (!DepositionMath.coldEnoughToFreeze(temperatureAtSurface)) {
            return false;
        }
        if (water.getY() < level.getMinBuildHeight() || water.getY() >= level.getMaxBuildHeight()
            || !level.isAreaLoaded(water, 1)) {
            return false;
        }
        if (level.getBrightness(LightLayer.BLOCK, water) >= MAX_BLOCK_LIGHT) {
            return false;
        }
        FluidState fluid = level.getFluidState(water);
        if (!fluid.isSource() || !fluid.is(FluidTags.WATER) || !(level.getBlockState(water).getBlock() instanceof LiquidBlock)) {
            return false;
        }
        boolean surrounded = level.isWaterAt(water.west()) && level.isWaterAt(water.east())
            && level.isWaterAt(water.north()) && level.isWaterAt(water.south());
        if (surrounded) {
            return false;
        }
        return level.setBlockAndUpdate(water, Blocks.ICE.defaultBlockState());
    }
}
