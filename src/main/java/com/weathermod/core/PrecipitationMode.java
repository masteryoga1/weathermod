package com.weathermod.core;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * How a weather type decides what falls from it: {@link #AUTO} lets the local temperature choose between rain,
 * sleet and snow, the fixed modes always produce that kind, and {@link #NONE} marks a type that never precipitates.
 */
public enum PrecipitationMode implements StringRepresentable {
    AUTO("auto"),
    RAIN("rain"),
    SNOW("snow"),
    SLEET("sleet"),
    NONE("none");

    private static final PrecipitationMode[] VALUES = values();

    /** Serializes a mode as its lower-case name, for example {@code "sleet"}. */
    public static final Codec<PrecipitationMode> CODEC = StringRepresentable.fromEnum(PrecipitationMode::values);

    /** Writes a mode as its ordinal in a single VarInt. */
    public static final StreamCodec<ByteBuf, PrecipitationMode> STREAM_CODEC =
        ByteBufCodecs.idMapper(PrecipitationMode::byOrdinal, PrecipitationMode::ordinal);

    private final String serializedName;

    PrecipitationMode(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** True for the modes that always produce the same kind, which is every mode except {@link #AUTO} and {@link #NONE}. */
    public boolean isFixed() {
        return this != AUTO && this != NONE;
    }

    /**
     * Returns the mode with the given ordinal.
     *
     * @throws IllegalArgumentException when the ordinal is out of range
     */
    public static PrecipitationMode byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            throw new IllegalArgumentException("Unknown precipitation mode ordinal " + ordinal);
        }
        return VALUES[ordinal];
    }
}
