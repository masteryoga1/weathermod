package com.weathermod.network;

import com.weathermod.WeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound notice that a storm has ended or been removed. The client drops the storm with this id and ignores
 * the payload when it never knew the storm.
 */
public record StormRemovedPayload(int id) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StormRemovedPayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("storm_removed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StormRemovedPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, StormRemovedPayload::id,
        StormRemovedPayload::new);

    @Override
    public CustomPacketPayload.Type<StormRemovedPayload> type() {
        return TYPE;
    }
}
