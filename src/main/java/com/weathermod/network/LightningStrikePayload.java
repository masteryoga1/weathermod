package com.weathermod.network;

import com.weathermod.WeatherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound notice of one lightning strike of the mod, for delayed thunder and the far flash. The bolt entity
 * itself is only tracked for a few chunks; this payload reaches every player who could hear the strike.
 *
 * @param x     strike position x in blocks
 * @param y     strike position y in blocks
 * @param z     strike position z in blocks
 * @param power strength of the strike, 0 to 1: the intensity of the storm it came from
 */
public record LightningStrikePayload(double x, double y, double z, float power) implements CustomPacketPayload {
    /** The server sends the payload to players this near the strike, in blocks. */
    public static final double RANGE_BLOCKS = 1024.0;

    /** Payload type {@code weathermod:lightning_strike}. */
    public static final CustomPacketPayload.Type<LightningStrikePayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("lightning_strike"));

    /** Network codec: three doubles and a float, 28 bytes. */
    public static final StreamCodec<ByteBuf, LightningStrikePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, LightningStrikePayload::x,
        ByteBufCodecs.DOUBLE, LightningStrikePayload::y,
        ByteBufCodecs.DOUBLE, LightningStrikePayload::z,
        ByteBufCodecs.FLOAT, LightningStrikePayload::power,
        LightningStrikePayload::new);

    @Override
    public CustomPacketPayload.Type<LightningStrikePayload> type() {
        return TYPE;
    }
}
