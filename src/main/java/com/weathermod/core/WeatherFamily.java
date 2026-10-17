package com.weathermod.core;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * The simulation family a weather type belongs to. The family selects the server behaviour and the
 * client visuals of a storm; the weather type JSON only tunes that behaviour.
 */
public enum WeatherFamily implements StringRepresentable {
    PRECIPITATION("precipitation"),
    CONVECTIVE("convective"),
    VORTEX("vortex"),
    TROPICAL_CYCLONE("tropical_cyclone"),
    FRONT("front"),
    WINTER("winter"),
    OBSCURANT("obscurant"),
    REGIME("regime"),
    OPTICS("optics"),
    FANTASY("fantasy");

    private static final WeatherFamily[] VALUES = values();

    /** Serializes a family as its lower-case name, for example {@code "tropical_cyclone"}. */
    public static final Codec<WeatherFamily> CODEC = StringRepresentable.fromEnum(WeatherFamily::values);

    /** Writes a family as its ordinal in a single VarInt. */
    public static final StreamCodec<ByteBuf, WeatherFamily> STREAM_CODEC =
        ByteBufCodecs.idMapper(WeatherFamily::byOrdinal, WeatherFamily::ordinal);

    private final String serializedName;

    WeatherFamily(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * Returns the family with the given ordinal.
     *
     * @throws IllegalArgumentException when the ordinal is out of range
     */
    public static WeatherFamily byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            throw new IllegalArgumentException("Unknown weather family ordinal " + ordinal);
        }
        return VALUES[ordinal];
    }

    /** Looks a family up by its serialized name. */
    public static Optional<WeatherFamily> byName(String name) {
        for (WeatherFamily family : VALUES) {
            if (family.serializedName.equals(name)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }
}
