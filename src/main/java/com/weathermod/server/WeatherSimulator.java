package com.weathermod.server;

import com.weathermod.WeatherMod;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.network.WindUpdatePayload;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the server-side weather every level tick and keeps clients in step: storms and wind advance, expired
 * storms are announced, dirty storms are sent to interested players and joining players receive the full list.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherSimulator {
    /** Ticks between wind packets sent to every player in a dimension. */
    public static final int WIND_SYNC_INTERVAL_TICKS = 20;

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

    /** Runs one simulation step for a level whose dimension is enabled. */
    public static void tickLevel(ServerLevel level) {
        DimensionWeatherData data = DimensionWeatherData.get(level);
        int tick = data.advanceTick();
        WindState wind = data.wind();
        wind.tick(level.getRandom());

        for (StormInstance storm : data.storms()) {
            storm.tick(wind);
        }

        for (StormInstance expired : data.removeExpired()) {
            WeatherNetwork.sendToDimension(level, new StormRemovedPayload(expired.id()));
        }

        boolean hasPlayers = !level.players().isEmpty();
        if (hasPlayers && tick % WeatherManager.syncIntervalTicks() == 0) {
            for (StormInstance storm : data.storms()) {
                if (storm.isDirty()) {
                    WeatherNetwork.sendStormToNearby(level, storm);
                    storm.clearDirty();
                }
            }
        }

        if (hasPlayers && tick % WIND_SYNC_INTERVAL_TICKS == 0) {
            WeatherNetwork.sendToDimension(level, new WindUpdatePayload(wind.directionDegrees(), wind.speed()));
        }

        data.setDirty();
    }

    /**
     * Sends a player the complete weather of the dimension they are in: the storms whose interest radius covers
     * them, then the wind. In a dimension without weather an empty list is sent so stale storms are cleared.
     */
    public static void sendFullState(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!WeatherManager.isEnabled(level)) {
            WeatherNetwork.sendToPlayer(player, new StormListPayload(List.of()));
            return;
        }
        DimensionWeatherData data = DimensionWeatherData.get(level);
        WeatherNetwork.sendToPlayer(player, new StormListPayload(data.snapshotsNear(player.getX(), player.getZ())));
        WindState wind = data.wind();
        WeatherNetwork.sendToPlayer(player, new WindUpdatePayload(wind.directionDegrees(), wind.speed()));
    }
}
