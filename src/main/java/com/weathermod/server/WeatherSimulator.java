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
import com.weathermod.server.family.FamilyContext;
import com.weathermod.server.family.FamilySystems;
import com.weathermod.server.impact.VanillaWeatherBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
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
 * storms move and run their family systems, expired storms are announced, vanilla rain follows the storms, dirty
 * storms are sent to interested players, and wind and climate are broadcast once a second. Players who join,
 * respawn or change dimension receive the full state.
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
     * Runs one simulation step for a level whose dimension is enabled: the tick counter and the wind advance, the
     * director runs, every storm moves and runs its family system, expired storms are removed and announced, the
     * vanilla weather flags follow the storms, dirty storms go to interested players on the sync interval, and the
     * wind and the climate at each player are broadcast once a second on alternating half seconds.
     */
    public static void tickLevel(ServerLevel level) {
        DimensionWeatherData data = DimensionWeatherData.get(level);
        int tick = data.advanceTick();
        WindState wind = data.wind();
        wind.tick(level.getRandom());

        StormDirector.tick(level, data, tick);

        Function<StormInstance, WeatherType> types = typeLookup(level);
        if (!data.storms().isEmpty()) {
            FamilyContext context = new FamilyContext(level, data, data.climate(level), wind, tick, level.getRandom());
            for (StormInstance storm : data.storms()) {
                storm.tick(wind);
                WeatherType type = types.apply(storm);
                if (type != null) {
                    FamilySystems.forFamily(type.family()).ifPresent(system -> system.tick(storm, type, context));
                }
            }
        }

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
     * Sends a player the complete weather of the dimension they are in: the storms whose interest radius covers
     * them, then the wind, then the climate at their position. In a dimension without weather an empty list is
     * sent so stale storms are cleared.
     */
    public static void sendFullState(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!WeatherManager.isEnabled(level)) {
            WeatherNetwork.sendToPlayer(player, new StormListPayload(List.of()));
            return;
        }
        DimensionWeatherData data = DimensionWeatherData.get(level);
        WeatherNetwork.sendToPlayer(player, new StormListPayload(data.snapshotsNear(player.getX(), player.getZ())));
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
