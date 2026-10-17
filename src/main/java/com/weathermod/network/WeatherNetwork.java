package com.weathermod.network;

import com.weathermod.WeatherMod;
import com.weathermod.client.ClientPayloadHandler;
import com.weathermod.server.StormInstance;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the mod's payloads and offers the send helpers the server uses to reach players. Every handler is
 * written as a lambda that calls into {@code com.weathermod.client} so that class is only loaded on the client,
 * where the clientbound payloads are actually handled.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherNetwork {
    /** Network protocol version; bump it whenever a payload's wire format changes. */
    public static final String PROTOCOL_VERSION = "2";

    private WeatherNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(StormListPayload.TYPE, StormListPayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleStormList(payload, context));
        registrar.playToClient(StormDeltaPayload.TYPE, StormDeltaPayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleStormDelta(payload, context));
        registrar.playToClient(StormRemovedPayload.TYPE, StormRemovedPayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleStormRemoved(payload, context));
        registrar.playToClient(WindUpdatePayload.TYPE, WindUpdatePayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleWindUpdate(payload, context));
        registrar.playToClient(ClimateUpdatePayload.TYPE, ClimateUpdatePayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleClimateUpdate(payload, context));
        WeatherMod.LOGGER.debug("Registered weather payloads for protocol version {}", PROTOCOL_VERSION);
    }

    /**
     * Sends a storm as a {@link StormDeltaPayload} to every player within its interest radius of its centre.
     * The caller clears the storm's dirty flag afterwards if it wants to.
     */
    public static void sendStormToNearby(ServerLevel level, StormInstance storm) {
        sendStormToNearby(level, storm.toSnapshot());
    }

    /**
     * Sends a snapshot as a {@link StormDeltaPayload} to every player within its interest radius of its centre. The
     * test is horizontal, the same one {@code DimensionWeatherData.snapshotsNear} applies to the full list, so the
     * players who receive deltas are exactly the players who received the storm on login.
     */
    public static void sendStormToNearby(ServerLevel level, StormSnapshot snapshot) {
        StormDeltaPayload payload = new StormDeltaPayload(snapshot);
        for (ServerPlayer player : level.players()) {
            if (snapshot.isWithinInterest(player.getX(), player.getZ())) {
                sendToPlayer(player, payload);
            }
        }
    }

    /** Sends a payload to every player currently in the level. */
    public static void sendToDimension(ServerLevel level, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersInDimension(level, payload);
    }

    /** Sends a payload to one player. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
