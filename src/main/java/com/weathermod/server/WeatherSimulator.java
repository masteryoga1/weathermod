package com.weathermod.server;

import com.weathermod.WeatherMod;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.network.WindUpdatePayload;
import com.weathermod.server.climate.ClimateField;
import com.weathermod.server.climate.ClimateSample;
import com.weathermod.server.climate.SeasonInfo;
import com.weathermod.server.director.StormDirector;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.family.FamilyContext;
import com.weathermod.server.family.FamilySystems;
import com.weathermod.server.impact.EntityForces;
import com.weathermod.server.impact.VanillaWeatherBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the server-side weather every level tick and keeps clients in step: the wind and the director advance,
 * storms move and run their family systems, the children those systems asked for are spawned, entities are pushed,
 * expired storms are announced, vanilla rain follows the storms, dirty storms are sent to interested players, and
 * wind and climate are broadcast once a second. Players who join, respawn or change dimension receive the full
 * state. While the game is frozen with {@code /tick freeze} the weather stands still with it.
 *
 * <p>Every step runs in every destruction mode. The mode gates effects where they act, never the simulation: with
 * visual-only destruction storms still move, funnels still form and are synced, and bolts still flash.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherSimulator {
    /** Ticks between wind packets sent to every player in a dimension. */
    public static final int WIND_SYNC_INTERVAL_TICKS = 20;
    /** Ticks between climate packets sent to each player in a dimension. */
    public static final int CLIMATE_SYNC_INTERVAL_TICKS = 20;
    /** Offset of the climate packets inside their interval, so they never share a tick with the wind packets. */
    public static final int CLIMATE_SYNC_OFFSET_TICKS = 10;

    private WeatherSimulator() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && WeatherManager.isEnabled(level)) {
            tickLevel(level);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullState(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullState(player);
        }
    }

    /**
     * Respawning builds a new player in the respawn dimension without a dimension-change event, so the storm list
     * is resent here; otherwise storms of the dimension the player died in would linger on the client.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullState(player);
        }
    }

    /**
     * Runs one simulation step for a level whose dimension is enabled, unless the game is frozen: the tick counter
     * advances, the runtime budgets are reset, the wind advances, the director runs, every storm moves within the
     * speed limits of its type and runs its family system, the children the family systems queued are spawned,
     * entities are pushed, temporary fires burn down, expired storms are removed and announced, the vanilla weather
     * flags follow the storms, dirty storms go to interested players on the sync interval, and the wind and the
     * climate at each player are broadcast once a second on alternating half seconds.
     */
    public static void tickLevel(ServerLevel level) {
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        DimensionWeatherData data = DimensionWeatherData.get(level);
        int tick = data.advanceTick();
        StormRuntime runtime = data.runtime();
        runtime.beginTick(tick, WeatherManager.blockBudgetPerTick(), WeatherManager.maxFlyingBlocksPerStorm());
        WindState wind = data.wind();
        wind.tick(level.getRandom());

        StormDirector.tick(level, data, tick);

        Function<StormInstance, WeatherType> types = typeLookup(level);
        if (!data.storms().isEmpty()) {
            FamilyContext context = new FamilyContext(level, data, data.climate(level), wind, tick, level.getRandom());
            float driftFactor = WeatherManager.stormDriftFactor();
            for (StormInstance storm : data.storms()) {
                WeatherType type = types.apply(storm);
                if (type == null) {
                    storm.tick(wind, driftFactor);
                    runtime.removeVortex(storm.id());
                    continue;
                }
                storm.tick(wind, driftFactor * type.driftMultiplier(), type.minSpeed(), type.maxSpeed());
                if (type.vortex().isEmpty()) {
                    runtime.removeVortex(storm.id());
                }
                FamilySystems.forFamily(type.family()).ifPresent(system -> system.tick(storm, type, context));
            }
        }

        spawnQueuedChildren(level, data);
        EntityForces.tickLevel(level, data, types, tick);
        data.temporaryFires().tick(level);

        for (StormInstance expired : data.removeExpired()) {
            WeatherNetwork.sendToDimension(level, new StormRemovedPayload(expired.id()));
        }

        List<ServerPlayer> players = level.players();
        VanillaWeatherBridge.apply(level, VanillaWeatherBridge.decide(data.storms(), types, positionsOf(players)));

        boolean hasPlayers = !players.isEmpty();
        if (hasPlayers && tick % WeatherManager.syncIntervalTicks() == 0) {
            for (StormInstance storm : data.storms()) {
                if (storm.isDirty()) {
                    WeatherNetwork.sendStormToNearby(level, storm);
                    storm.clearDirty();
                }
            }
        }

        if (hasPlayers && tick % WIND_SYNC_INTERVAL_TICKS == 0) {
            WeatherNetwork.sendToDimension(level, windPayload(wind));
        }

        if (hasPlayers && tick % CLIMATE_SYNC_INTERVAL_TICKS == CLIMATE_SYNC_OFFSET_TICKS) {
            ClimateField climate = data.climate(level);
            for (ServerPlayer player : players) {
                WeatherNetwork.sendToPlayer(player, climatePayload(level, climate, player.blockPosition()));
            }
        }

        data.setDirty();
    }

    /**
     * Spawns the children the family systems asked for during the family loop, which must not change the storm
     * list itself. A child whose type is unknown or disabled, or for which the dimension has no room left, is
     * dropped. A spawned child takes the lifetime and the family state its parent prepared, when there are any,
     * and is sent to the players near it at once.
     */
    private static void spawnQueuedChildren(ServerLevel level, DimensionWeatherData data) {
        for (ChildSpawn spawn : data.drainChildQueue()) {
            Optional<Holder.Reference<WeatherType>> holder =
                WeatherRegistries.holder(level.registryAccess(), spawn.type());
            if (holder.isEmpty() || !holder.get().value().enabled()) {
                continue;
            }
            StormInstance child = data.spawnChild(holder.get(), spawn.x(), spawn.z(), spawn.parentId(),
                level.getRandom());
            if (child == null) {
                continue;
            }
            if (spawn.lifetimeTicks() > 0) {
                child.setMaxAgeTicks(spawn.lifetimeTicks());
            }
            if (!spawn.familyTag().isEmpty()) {
                child.setFamilyTag(spawn.familyTag());
            }
            WeatherManager.sync(level, child);
        }
    }

    /**
     * Sends a player the complete weather of the dimension they are in: the storms whose interest radius covers
     * them, the funnel spine of each of those storms that has a vortex, then the wind, then the climate at their
     * position. In a dimension without weather an empty list is sent so stale storms are cleared.
     */
    public static void sendFullState(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!WeatherManager.isEnabled(level)) {
            WeatherNetwork.sendToPlayer(player, new StormListPayload(List.of()));
            return;
        }
        DimensionWeatherData data = DimensionWeatherData.get(level);
        WeatherNetwork.sendToPlayer(player, new StormListPayload(data.snapshotsNear(player.getX(), player.getZ())));
        for (StormInstance storm : data.storms()) {
            if (storm.isWithinInterest(player.getX(), player.getZ())) {
                ActiveVortex vortex = data.runtime().vortex(storm.id());
                if (vortex != null) {
                    WeatherNetwork.sendSpine(player, storm.id(), vortex.spine());
                }
            }
        }
        WeatherNetwork.sendToPlayer(player, windPayload(data.wind()));
        WeatherNetwork.sendToPlayer(player, climatePayload(level, data.climate(level), player.blockPosition()));
    }

    /** The wind packet for a wind: direction, sustained speed and the momentary gust speed. */
    public static WindUpdatePayload windPayload(WindState wind) {
        return new WindUpdatePayload(wind.directionDegrees(), wind.speed(), wind.gustSpeed());
    }

    /**
     * The climate packet for a block position: the height-adjusted sample there, what would fall at that
     * temperature, and the season at the biome there.
     */
    public static ClimateUpdatePayload climatePayload(ServerLevel level, ClimateField climate, BlockPos pos) {
        ClimateSample sample = climate.sample(level, pos);
        SeasonInfo season = climate.season(level, level.getBiome(pos));
        return new ClimateUpdatePayload(sample.temperature(), sample.humidity(), sample.precipitationKind(),
            season.name(), season.progress());
    }

    /**
     * A lookup from storm to weather type bound to the level's registry for this tick. It yields {@code null} for a
     * storm whose type is no longer registered, which the bridge and the family loop both skip.
     */
    private static Function<StormInstance, WeatherType> typeLookup(ServerLevel level) {
        Optional<Registry<WeatherType>> registry = WeatherRegistries.optionalRegistry(level.registryAccess());
        if (registry.isEmpty()) {
            return storm -> null;
        }
        Registry<WeatherType> types = registry.get();
        return storm -> types.get(storm.type());
    }

    /** The horizontal positions of the players, as the bridge wants them. */
    private static List<Vec3> positionsOf(List<ServerPlayer> players) {
        if (players.isEmpty()) {
            return List.of();
        }
        List<Vec3> positions = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            positions.add(player.position());
        }
        return positions;
    }
}
