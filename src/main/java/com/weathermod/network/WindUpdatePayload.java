package com.weathermod.network;

import com.weathermod.WeatherMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound wind state of the player's dimension: the compass direction the wind blows toward, its sustained speed
 * in blocks per second and the momentary gust speed, which equals the sustained speed while no gust is blowing.
 * Sent about once a second and whenever a player joins or changes dimension.
 */
public record WindUpdatePayload(float directionDegrees, float speed, float gustSpeed) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WindUpdatePayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("wind_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WindUpdatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.FLOAT, WindUpdatePayload::directionDegrees,
        ByteBufCodecs.FLOAT, WindUpdatePayload::speed,
        ByteBufCodecs.FLOAT, WindUpdatePayload::gustSpeed,
        WindUpdatePayload::new);

    /** Wind without a gust: the gust speed is the sustained speed. */
    public WindUpdatePayload(float directionDegrees, float speed) {
        this(directionDegrees, speed, speed);
    }

    /** Whether a gust is blowing, that is the gust speed exceeds the sustained speed. */
    public boolean isGusting() {
        return gustSpeed > speed;
    }

    @Override
    public CustomPacketPayload.Type<WindUpdatePayload> type() {
        return TYPE;
    }
}
