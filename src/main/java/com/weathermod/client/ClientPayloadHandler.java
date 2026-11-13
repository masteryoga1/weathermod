package com.weathermod.client;

import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.DownburstPayload;
import com.weathermod.network.FunnelSpinePayload;
import com.weathermod.network.LightningStrikePayload;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WindUpdatePayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Receives the clientbound weather payloads and hands them to {@link ClientWeatherState#INSTANCE} on the main
 * thread. The network layer only refers to these methods through lambdas, so this class is never loaded on a
 * dedicated server.
 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    /** Replaces the client's storm list with the one the server sent. */
    public static void handleStormList(StormListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Adds or updates one storm. */
    public static void handleStormDelta(StormDeltaPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Drops one storm. */
    public static void handleStormRemoved(StormRemovedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Stores the dimension's wind. */
    public static void handleWindUpdate(WindUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Stores the climate at the player. */
    public static void handleClimateUpdate(ClimateUpdatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Creates or updates the funnel of a storm the client knows. */
    public static void handleFunnelSpine(FunnelSpinePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Remembers a lightning strike and tells the strike listener. */
    public static void handleLightningStrike(LightningStrikePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }

    /** Starts mirroring a downburst. */
    public static void handleDownburst(DownburstPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientWeatherState.INSTANCE.apply(payload));
    }
}
