package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** Checks the temperature thresholds and mode resolution that decide what falls from a storm. */
class PrecipitationKindTest {
    @Test
    void thresholdsMatchTheDesign() {
        assertEquals(0.10F, PrecipitationKind.SNOW_BELOW, 1.0E-6F);
        assertEquals(0.20F, PrecipitationKind.RAIN_FROM, 1.0E-6F);
        assertTrue(PrecipitationKind.SNOW_BELOW < PrecipitationKind.RAIN_FROM);
    }

    @Test
    void coldTemperaturesSnow() {
        assertSame(PrecipitationKind.SNOW, PrecipitationKind.fromTemperature(-1.0F));
        assertSame(PrecipitationKind.SNOW, PrecipitationKind.fromTemperature(-0.5F), "frozen peaks");
        assertSame(PrecipitationKind.SNOW, PrecipitationKind.fromTemperature(0.0F));
        assertSame(PrecipitationKind.SNOW, PrecipitationKind.fromTemperature(Math.nextDown(PrecipitationKind.SNOW_BELOW)));
    }

    @Test
    void snowThresholdIsExclusiveAndRainThresholdInclusive() {
        assertSame(PrecipitationKind.SLEET, PrecipitationKind.fromTemperature(PrecipitationKind.SNOW_BELOW),
            "exactly SNOW_BELOW is no longer snow");
        assertSame(PrecipitationKind.SLEET, PrecipitationKind.fromTemperature(0.15F));
        assertSame(PrecipitationKind.SLEET, PrecipitationKind.fromTemperature(Math.nextDown(PrecipitationKind.RAIN_FROM)));
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(PrecipitationKind.RAIN_FROM),
            "exactly RAIN_FROM is already rain");
    }

    @Test
    void warmTemperaturesRain() {
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(0.25F), "taiga");
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(0.8F), "plains");
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(2.0F), "desert");
        assertSame(PrecipitationKind.RAIN, PrecipitationKind.fromTemperature(Float.POSITIVE_INFINITY));
    }

    @Test
    void temperatureAloneNeverGivesNone() {
        for (float temperature = -1.5F; temperature <= 2.5F; temperature += 0.01F) {
            assertNotSame(PrecipitationKind.NONE, PrecipitationKind.fromTemperature(temperature),
                "temperature " + temperature);
        }
    }

    @Test
    void autoModeFollowsTheTemperature() {
        for (float temperature = -1.0F; temperature <= 2.0F; temperature += 0.05F) {
            assertSame(PrecipitationKind.fromTemperature(temperature),
                PrecipitationKind.resolve(PrecipitationMode.AUTO, temperature), "temperature " + temperature);
        }
    }

    @Test
    void fixedModesIgnoreTheTemperature() {
        float[] temperatures = {-1.0F, 0.0F, 0.15F, 0.8F, 2.0F};
        for (float temperature : temperatures) {
            assertSame(PrecipitationKind.RAIN, PrecipitationKind.resolve(PrecipitationMode.RAIN, temperature));
            assertSame(PrecipitationKind.SNOW, PrecipitationKind.resolve(PrecipitationMode.SNOW, temperature));
            assertSame(PrecipitationKind.SLEET, PrecipitationKind.resolve(PrecipitationMode.SLEET, temperature));
            assertSame(PrecipitationKind.NONE, PrecipitationKind.resolve(PrecipitationMode.NONE, temperature));
        }
    }

    @Test
    void everyModeResolvesToSomething() {
        for (PrecipitationMode mode : PrecipitationMode.values()) {
            PrecipitationKind kind = PrecipitationKind.resolve(mode, 0.8F);
            assertEquals(mode == PrecipitationMode.NONE, kind == PrecipitationKind.NONE, "mode " + mode);
            assertEquals(mode.isFixed(), mode != PrecipitationMode.AUTO && mode != PrecipitationMode.NONE);
        }
        assertThrows(NullPointerException.class, () -> PrecipitationKind.resolve(null, 0.8F));
    }

    @Test
    void helpersDescribeTheKinds() {
        assertFalse(PrecipitationKind.NONE.isPrecipitating());
        assertTrue(PrecipitationKind.RAIN.isPrecipitating());
        assertTrue(PrecipitationKind.SNOW.isPrecipitating());
        assertTrue(PrecipitationKind.SLEET.isPrecipitating());
        assertFalse(PrecipitationKind.NONE.isFrozen());
        assertFalse(PrecipitationKind.RAIN.isFrozen());
        assertTrue(PrecipitationKind.SNOW.isFrozen());
        assertTrue(PrecipitationKind.SLEET.isFrozen());
    }

    @Test
    void ordinalsAreStableForTheWire() {
        assertEquals(0, PrecipitationKind.NONE.ordinal());
        assertEquals(1, PrecipitationKind.RAIN.ordinal());
        assertEquals(2, PrecipitationKind.SNOW.ordinal());
        assertEquals(3, PrecipitationKind.SLEET.ordinal());
        for (PrecipitationKind kind : PrecipitationKind.values()) {
            assertSame(kind, PrecipitationKind.byOrdinal(kind.ordinal()));
        }
        assertThrows(IllegalArgumentException.class, () -> PrecipitationKind.byOrdinal(-1));
        assertThrows(IllegalArgumentException.class, () -> PrecipitationKind.byOrdinal(4));
    }

    @Test
    void streamCodecWritesOneVarIntPerKind() {
        for (PrecipitationKind kind : PrecipitationKind.values()) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                PrecipitationKind.STREAM_CODEC.encode(buffer, kind);
                assertEquals(1, buffer.readableBytes(), "a small ordinal is a single VarInt byte");
                assertEquals(kind.ordinal(), buffer.getByte(0));
                assertSame(kind, PrecipitationKind.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes(), "every byte is consumed");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void streamCodecRejectsUnknownOrdinals() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(7);
            assertThrows(IllegalArgumentException.class, () -> PrecipitationKind.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
