package com.weathermod.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.FunnelSpine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the funnel spine keyframe through its stream codec and pins its size and its decode limit. Needs no
 * Minecraft bootstrap: only primitive codecs and JOML vectors are involved.
 */
class FunnelSpinePayloadCodecTest {
    /** Count byte, 24 points of three floats, four floats and the grounded flag. */
    private static final int SPINE_BYTES = 1 + FunnelSpine.POINTS * 12 + 4 * 4 + 1;

    /** A leaning, swaying spine with the given number of points and values that are not round in binary. */
    private static FunnelSpine spine(int points) {
        List<Vector3f> list = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            float t = i / (float) (points - 1);
            list.add(new Vector3f(1.7F * (1.0F - t) * (1.0F - t) + 5.3F * t, 63.4F + 97.1F * t,
                (float) Math.sin(Math.PI * t) * 2.9F - 0.6F * t));
        }
        return new FunnelSpine(list, 9.6F, 24.3F, 0.83F, 0.37F, false);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    /** Writes a payload by hand, without the codec's size check, with every point at (i, 64 + i, -i). */
    private static ByteBuf handWritten(int points) {
        ByteBuf buffer = Unpooled.buffer();
        VarInt.write(buffer, 7);
        VarInt.write(buffer, 10);
        VarInt.write(buffer, points);
        for (int i = 0; i < points; i++) {
            buffer.writeFloat(i);
            buffer.writeFloat(64.0F + i);
            buffer.writeFloat(-i);
        }
        buffer.writeFloat(3.0F);
        buffer.writeFloat(10.0F);
        buffer.writeFloat(1.0F);
        buffer.writeFloat(1.0F);
        buffer.writeBoolean(true);
        return buffer;
    }

    @Test
    void aFullSpineRoundTripsByteExactly() {
        FunnelSpinePayload payload = new FunnelSpinePayload(7, 10, spine(FunnelSpine.POINTS));
        ByteBuf buffer = Unpooled.buffer();

        FunnelSpinePayload.STREAM_CODEC.encode(buffer, payload);
        byte[] written = bytes(buffer);
        FunnelSpinePayload decoded = FunnelSpinePayload.STREAM_CODEC.decode(buffer);

        assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
        assertEquals(payload, decoded);
        assertEquals(7, decoded.stormId());
        assertEquals(10, decoded.intervalTicks());
        assertEquals(FunnelSpine.POINTS, decoded.spine().points().size());
        assertEquals(payload.spine().points(), decoded.spine().points(), "every float survives bit for bit");
        assertEquals(0.37F, decoded.spine().descent());
        assertFalse(decoded.spine().grounded());
        assertSame(FunnelSpinePayload.TYPE, decoded.type());

        ByteBuf again = Unpooled.buffer();
        FunnelSpinePayload.STREAM_CODEC.encode(again, decoded);
        assertArrayEquals(written, bytes(again), "encoding what was decoded gives the same bytes");
    }

    @Test
    void aKeyframeIsAboutThreeHundredBytes() {
        ByteBuf spineOnly = Unpooled.buffer();
        FunnelSpine.STREAM_CODEC.encode(spineOnly, spine(FunnelSpine.POINTS));
        assertEquals(306, SPINE_BYTES);
        assertEquals(SPINE_BYTES, spineOnly.readableBytes());

        ByteBuf small = Unpooled.buffer();
        FunnelSpinePayload.STREAM_CODEC.encode(small, new FunnelSpinePayload(7, 10, spine(FunnelSpine.POINTS)));
        assertEquals(SPINE_BYTES + 2, small.readableBytes(), "a one-byte storm id and a one-byte interval");

        ByteBuf large = Unpooled.buffer();
        FunnelSpinePayload.STREAM_CODEC.encode(large, new FunnelSpinePayload(20000, 100, spine(FunnelSpine.POINTS)));
        assertEquals(SPINE_BYTES + 4, large.readableBytes(), "a storm id of 20000 takes three bytes as a var int");
        assertTrue(large.readableBytes() < 325, "the payload stays inside the budgeted keyframe size");
    }

    @Test
    void theSpineCodecAloneRoundTrips() {
        FunnelSpine grounded = new FunnelSpine(spine(FunnelSpine.POINTS).points(), 12.0F, 30.0F, 1.0F, 1.0F, true);
        ByteBuf buffer = Unpooled.buffer();

        FunnelSpine.STREAM_CODEC.encode(buffer, grounded);
        FunnelSpine decoded = FunnelSpine.STREAM_CODEC.decode(buffer);

        assertEquals(grounded, decoded);
        assertTrue(decoded.grounded());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void thePayloadCodecWorksOnARegistryBuffer() {
        FunnelSpinePayload payload = new FunnelSpinePayload(123456, 5, spine(FunnelSpine.POINTS));
        RegistryFriendlyByteBuf buffer =
            new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);

        FunnelSpinePayload.STREAM_CODEC.encode(buffer, payload);
        FunnelSpinePayload decoded = FunnelSpinePayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void theShortestAndTheLongestSpineRoundTrip() {
        for (int points : new int[] {2, FunnelSpine.MAX_POINTS}) {
            FunnelSpinePayload payload = new FunnelSpinePayload(1, 10, spine(points));
            ByteBuf buffer = Unpooled.buffer();

            FunnelSpinePayload.STREAM_CODEC.encode(buffer, payload);
            FunnelSpinePayload decoded = FunnelSpinePayload.STREAM_CODEC.decode(buffer);

            assertEquals(payload, decoded, points + " points");
            assertEquals(0, buffer.readableBytes());
        }
    }

    @Test
    void aHandWrittenPayloadWithinTheLimitDecodes() {
        FunnelSpinePayload decoded = FunnelSpinePayload.STREAM_CODEC.decode(handWritten(FunnelSpine.MAX_POINTS));

        assertEquals(7, decoded.stormId());
        assertEquals(10, decoded.intervalTicks());
        assertEquals(FunnelSpine.MAX_POINTS, decoded.spine().points().size());
        assertEquals(new Vector3f(31.0F, 95.0F, -31.0F), decoded.spine().points().get(31));
        assertEquals(64.0F, decoded.spine().groundY());
        assertTrue(decoded.spine().grounded());
    }

    @Test
    void thirtyThreePointsFailToDecode() {
        ByteBuf buffer = handWritten(FunnelSpine.MAX_POINTS + 1);

        assertThrows(DecoderException.class, () -> FunnelSpinePayload.STREAM_CODEC.decode(buffer));
    }

    @Test
    void thirtyThreePointsFailToEncode() {
        FunnelSpinePayload payload = new FunnelSpinePayload(7, 10, spine(FunnelSpine.MAX_POINTS + 1));

        assertThrows(EncoderException.class,
            () -> FunnelSpinePayload.STREAM_CODEC.encode(Unpooled.buffer(), payload));
    }

    @Test
    void fewerThanTwoPointsFailToDecode() {
        assertThrows(IllegalArgumentException.class, () -> FunnelSpinePayload.STREAM_CODEC.decode(handWritten(1)));
        assertThrows(IllegalArgumentException.class, () -> FunnelSpinePayload.STREAM_CODEC.decode(handWritten(0)));
    }

    @Test
    void theSpineIsRequiredAndTheTypeIsNamespaced() {
        assertThrows(NullPointerException.class, () -> new FunnelSpinePayload(1, 10, null));
        assertEquals(ResourceLocation.fromNamespaceAndPath("weathermod", "funnel_spine"), FunnelSpinePayload.TYPE.id());
    }
}
