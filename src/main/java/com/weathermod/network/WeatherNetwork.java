package com.weathermod.network;

import com.weathermod.WeatherMod;
import com.weathermod.client.ClientPayloadHandler;
import com.weathermod.core.FunnelSpine;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
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
    /**
     * Network protocol version; bump it whenever a payload's wire format changes. Version 3: the storm snapshot
     * carries the parent and the hail strength, and the funnel spine, lightning strike and downburst payloads are
     * new.
     */
    public static final String PROTOCOL_VERSION = "3";

    private WeatherNetwork() {
    }

    /** Registers every clientbound payload of the mod under {@link #PROTOCOL_VERSION}. */
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
        registrar.playToClient(FunnelSpinePayload.TYPE, FunnelSpinePayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleFunnelSpine(payload, context));
        registrar.playToClient(LightningStrikePayload.TYPE, LightningStrikePayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleLightningStrike(payload, context));
        registrar.playToClient(DownburstPayload.TYPE, DownburstPayload.STREAM_CODEC,
            (payload, context) -> ClientPayloadHandler.handleDownburst(payload, context));
        WeatherMod.LOGGER.debug("Registered weather payloads for protocol version {}", PROTOCOL_VERSION);
    }

    /**
     * Sends a storm as a {@link StormDeltaPayload} to every player within its interest radius of its centre. When
     * the dimension's runtime holds a vortex for the storm, each of those players receives the delta and a
     * {@link FunnelSpinePayload} with the server's sync interval in one bundle, the delta first, so a client always
     * knows the storm before it is told the storm's funnel. The caller clears the storm's dirty flag afterwards if
     * it wants to.
     */
    public static void sendStormToNearby(ServerLevel level, StormInstance storm) {
        StormSnapshot snapshot = storm.toSnapshot();
        ActiveVortex vortex = DimensionWeatherData.get(level).runtime().vortex(storm.id());
        if (vortex == null) {
            sendStormToNearby(level, snapshot);
            return;
        }
        StormDeltaPayload delta = new StormDeltaPayload(snapshot);
        FunnelSpinePayload spine = spinePayload(storm.id(), vortex.spine());
        for (ServerPlayer player : level.players()) {
            if (snapshot.isWithinInterest(player.getX(), player.getZ())) {
                PacketDistributor.sendToPlayer(player, delta, spine);
            }
        }
    }

    /**
     * Sends a snapshot as a {@link StormDeltaPayload} to every player within its interest radius of its centre. The
     * test is horizontal, the same one {@code DimensionWeatherData.snapshotsNear} applies to the full list, so the
     * players who receive deltas are exactly the players who received the storm on login. No funnel spine goes
     * with a bare snapshot.
     */
    public static void sendStormToNearby(ServerLevel level, StormSnapshot snapshot) {
        StormDeltaPayload payload = new StormDeltaPayload(snapshot);
        for (ServerPlayer player : level.players()) {
            if (snapshot.isWithinInterest(player.getX(), player.getZ())) {
                sendToPlayer(player, payload);
            }
        }
    }

    /**
     * Sends one player the funnel of a storm as a {@link FunnelSpinePayload} with the server's sync interval. The
     * player must already know the storm, or the client drops the spine.
     */
    public static void sendSpine(ServerPlayer player, int stormId, FunnelSpine spine) {
        sendToPlayer(player, spinePayload(stormId, spine));
    }

    /**
     * Tells every player of the level within {@link LightningStrikePayload#RANGE_BLOCKS} of a strike about it, for
     * delayed thunder and the far flash.
     *
     * @param power strength of the strike, 0 to 1
     */
    public static void sendStrike(ServerLevel level, double x, double y, double z, float power) {
        PacketDistributor.sendToPlayersNear(level, null, x, y, z, LightningStrikePayload.RANGE_BLOCKS,
            new LightningStrikePayload(x, y, z, power));
    }

    /**
     * Tells every player of the level about a downburst that starts now whose horizontal distance from its centre
     * is at most the downburst's radius plus {@link DownburstPayload#NOTIFY_MARGIN_BLOCKS}.
     */
    public static void sendDownburst(ServerLevel level, DownburstPayload payload) {
        double range = payload.radius() + DownburstPayload.NOTIFY_MARGIN_BLOCKS;
        double rangeSqr = range * range;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - payload.x();
            double dz = player.getZ() - payload.z();
            if (dx * dx + dz * dz <= rangeSqr) {
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

    /** The keyframe of a storm's funnel, due again after the configured sync interval. */
    private static FunnelSpinePayload spinePayload(int stormId, FunnelSpine spine) {
        return new FunnelSpinePayload(stormId, WeatherManager.syncIntervalTicks(), spine);
    }
}
