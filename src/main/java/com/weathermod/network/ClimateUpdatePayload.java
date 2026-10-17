package com.weathermod.network;

import com.weathermod.WeatherMod;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.server.climate.SeasonInfo;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Clientbound climate at the receiving player's position: temperature on the vanilla biome scale, humidity from 0
 * to 1, what would fall at that temperature, and the season name and cycle progress the server's season provider
 * reports there. Sent every second, offset from the wind update, and after the wind whenever a player joins or
 * changes dimension.
 *
 * @param temperature    the height-adjusted temperature at the player, on the vanilla biome scale
 * @param humidity       the humidity at the player, 0 bone dry to 1 saturated
 * @param kind           what falls at this temperature when a storm leaves the choice to the climate
 * @param seasonName     the lower-case season name, {@link #NO_SEASON} when the dimension has no seasons
 * @param seasonProgress position in the seasonal cycle, 0 inclusive to 1 exclusive
 */
public record ClimateUpdatePayload(float temperature, float humidity, PrecipitationKind kind, String seasonName,
                                   float seasonProgress) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClimateUpdatePayload> TYPE =
        new CustomPacketPayload.Type<>(WeatherMod.id("climate_update"));

    /** The season name sent when the provider has nothing to say, the name of {@link SeasonInfo#NONE}. */
    public static final String NO_SEASON = SeasonInfo.NONE.name();

    public static final StreamCodec<RegistryFriendlyByteBuf, ClimateUpdatePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.FLOAT, ClimateUpdatePayload::temperature,
        ByteBufCodecs.FLOAT, ClimateUpdatePayload::humidity,
        PrecipitationKind.STREAM_CODEC, ClimateUpdatePayload::kind,
        ByteBufCodecs.STRING_UTF8, ClimateUpdatePayload::seasonName,
        ByteBufCodecs.FLOAT, ClimateUpdatePayload::seasonProgress,
        ClimateUpdatePayload::new);

    public ClimateUpdatePayload {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(seasonName, "seasonName");
    }

    /** Whether the payload names a season rather than {@link #NO_SEASON}. */
    public boolean hasSeason() {
        return !NO_SEASON.equals(seasonName);
    }

    @Override
    public CustomPacketPayload.Type<ClimateUpdatePayload> type() {
        return TYPE;
    }
}
