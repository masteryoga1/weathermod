package com.weathermod.network;

import com.weathermod.WeatherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound notice of a downburst that starts now. The client ages it by its own ticks and applies the same
 * wind force functions to the local player that the server applies to everything else.
 *
 * @param x             centre x in blocks
 * @param z             centre z in blocks
 * @param radius        radius in blocks
 * @param speed         peak outflow in blocks per second
 * @param durationTicks length of the downburst in ticks
 */
public record DownburstPayload(double x, double z, float radius, float speed, int durationTicks)
        implements CustomPacketPayload {
    /** The payload is sent to players within the radius plus this many blocks of the centre. */
    public static final double NOTIFY_MARGIN_BLOCKS = 96.0;

    /** Payload type {@code weathermod:downburst}. */
    public static final CustomPacketPayload.Type<DownburstPayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("downburst"));

    /** Network codec: two doubles, two floats and a var int. */
    public static final StreamCodec<ByteBuf, DownburstPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.DOUBLE, DownburstPayload::x,
        ByteBufCodecs.DOUBLE, DownburstPayload::z,
        ByteBufCodecs.FLOAT, DownburstPayload::radius,
        ByteBufCodecs.FLOAT, DownburstPayload::speed,
        ByteBufCodecs.VAR_INT, DownburstPayload::durationTicks,
        DownburstPayload::new);

    @Override
    public CustomPacketPayload.Type<DownburstPayload> type() {
        return TYPE;
    }
}
