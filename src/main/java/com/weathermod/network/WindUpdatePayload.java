package com.weathermod.network;

import com.weathermod.WeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound wind state of the player's dimension: the compass direction the wind blows toward and its speed in
 * blocks per second. Sent about once a second and whenever a player joins or changes dimension.
 */
public record WindUpdatePayload(float directionDegrees, float speed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WindUpdatePayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("wind_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WindUpdatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.FLOAT, WindUpdatePayload::directionDegrees,
        ByteBufCodecs.FLOAT, WindUpdatePayload::speed,
        WindUpdatePayload::new);

    @Override
    public CustomPacketPayload.Type<WindUpdatePayload> type() {
        return TYPE;
    }
}
