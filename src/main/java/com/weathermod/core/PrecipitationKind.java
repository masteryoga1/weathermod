package com.weathermod.core;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What is actually falling at a point: the result of applying a {@link PrecipitationMode} to the local
 * temperature. Temperatures use the vanilla biome scale, where snow falls below {@link #SNOW_BELOW} and rain from
 * {@link #RAIN_FROM} upward, with sleet in between.
 */
public enum PrecipitationKind {
    NONE,
    RAIN,
    SNOW,
    SLEET;

    /** Temperatures strictly below this fall as snow. */
    public static final float SNOW_BELOW = 0.10F;
    /** Temperatures at or above this fall as rain; between {@link #SNOW_BELOW} and this they fall as sleet. */
    public static final float RAIN_FROM = 0.20F;

    private static final PrecipitationKind[] VALUES = values();

    /** Writes a kind as its ordinal in a single VarInt. */
    public static final StreamCodec<ByteBuf, PrecipitationKind> STREAM_CODEC =
        ByteBufCodecs.idMapper(PrecipitationKind::byOrdinal, PrecipitationKind::ordinal);

    /** The kind that falls at a temperature when the type leaves the choice to the climate; never {@link #NONE}. */
    public static PrecipitationKind fromTemperature(float temperature) {
        if (temperature < SNOW_BELOW) {
            return SNOW;
        }
        if (temperature >= RAIN_FROM) {
            return RAIN;
        }
        return SLEET;
    }

    /**
     * Resolves a type's mode at a temperature: {@link PrecipitationMode#AUTO} defers to
     * {@link #fromTemperature(float)}, {@link PrecipitationMode#NONE} gives {@link #NONE} and every fixed mode gives
     * its own kind regardless of temperature.
     */
    public static PrecipitationKind resolve(PrecipitationMode mode, float temperature) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case AUTO -> fromTemperature(temperature);
            case RAIN -> RAIN;
            case SNOW -> SNOW;
            case SLEET -> SLEET;
            case NONE -> NONE;
        };
    }

    /** True for every kind except {@link #NONE}. */
    public boolean isPrecipitating() {
        return this != NONE;
    }

    /** True for {@link #SNOW} and {@link #SLEET}, the kinds that fall at least partly frozen. */
    public boolean isFrozen() {
        return this == SNOW || this == SLEET;
    }

    /**
     * Returns the kind with the given ordinal.
     *
     * @throws IllegalArgumentException when the ordinal is out of range
     */
    public static PrecipitationKind byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            throw new IllegalArgumentException("Unknown precipitation kind ordinal " + ordinal);
        }
        return VALUES[ordinal];
    }
}
