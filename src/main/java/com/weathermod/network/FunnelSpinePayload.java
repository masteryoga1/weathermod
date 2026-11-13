package com.weathermod.network;

import com.weathermod.WeatherMod;
import com.weathermod.core.FunnelSpine;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound keyframe of the funnel of one storm. Sent with the storm's delta every sync interval, in the same
 * bundle, so the client always knows the storm before it sees the spine; the client blends from the spine it shows
 * to this one over {@code intervalTicks}. A keyframe of {@link FunnelSpine#POINTS} points is about 325 bytes on
 * the wire.
 *
 * @param stormId       id of the storm the funnel belongs to
 * @param intervalTicks ticks until the next keyframe is due, the server's sync interval
 * @param spine         the funnel's geometry now
 */
public record FunnelSpinePayload(int stormId, int intervalTicks, FunnelSpine spine) implements CustomPacketPayload {
    /** Payload type {@code weathermod:funnel_spine}. */
    public static final CustomPacketPayload.Type<FunnelSpinePayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("funnel_spine"));

    /** Network codec: two var ints and the spine. A list of more than {@link FunnelSpine#MAX_POINTS} is refused. */
    public static final StreamCodec<ByteBuf, FunnelSpinePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, FunnelSpinePayload::stormId,
        ByteBufCodecs.VAR_INT, FunnelSpinePayload::intervalTicks,
        FunnelSpine.STREAM_CODEC, FunnelSpinePayload::spine,
        FunnelSpinePayload::new);

    public FunnelSpinePayload {
        Objects.requireNonNull(spine, "spine");
    }

    @Override
    public CustomPacketPayload.Type<FunnelSpinePayload> type() {
        return TYPE;
    }
}
