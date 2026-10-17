package com.weathermod.network;

import com.weathermod.WeatherMod;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound update for one storm. The client adds the storm when it does not know the id yet and otherwise
 * overwrites what it has, so the same payload serves both first sight and periodic deltas.
 */
public record StormDeltaPayload(StormSnapshot storm) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StormDeltaPayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("storm_delta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StormDeltaPayload> STREAM_CODEC = StreamCodec.composite(
        StormSnapshot.STREAM_CODEC, StormDeltaPayload::storm,
        StormDeltaPayload::new);

    public StormDeltaPayload {
        Objects.requireNonNull(storm, "storm");
    }

    /** Id of the storm this delta describes. */
    public int id() {
        return storm.id();
    }

    @Override
    public CustomPacketPayload.Type<StormDeltaPayload> type() {
        return TYPE;
    }
}
