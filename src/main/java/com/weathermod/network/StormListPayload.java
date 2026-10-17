package com.weathermod.network;

import com.weathermod.WeatherMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound full sync: every storm the receiving player should know about in their current dimension. The client
 * replaces its whole storm list with this one, so an empty list clears it.
 */
public record StormListPayload(List<StormSnapshot> storms) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StormListPayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("storm_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StormListPayload> STREAM_CODEC = StreamCodec.composite(
        StormSnapshot.STREAM_CODEC.apply(ByteBufCodecs.list()), StormListPayload::storms,
        StormListPayload::new);

    public StormListPayload {
        storms = List.copyOf(storms);
    }

    /** Whether the payload carries no storms, which tells the client to forget every storm it has. */
    public boolean isEmpty() {
        return storms.isEmpty();
    }

    @Override
    public CustomPacketPayload.Type<StormListPayload> type() {
        return TYPE;
    }
}
