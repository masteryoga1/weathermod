package com.weathermod.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the storm snapshot and every payload through their stream codecs. Needs no Minecraft bootstrap:
 * only primitive codecs, resource locations and an empty registry access are involved.
 */
class StormSnapshotCodecTest {
    private static final float EPSILON = 1.0E-6F;

    private static StormSnapshot sampleSnapshot(int id) {
        return new StormSnapshot(id, ResourceLocation.fromNamespaceAndPath("weathermod", "rain"),
            123.5D, 160.0D, -77.25D, 215.5F, 3.25F, 0.8F, 96.0F, 512.0F, 600, 2400);
    }

    private static RegistryFriendlyByteBuf registryBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    @Test
    void snapshotRoundTripsThroughItsCodec() {
        StormSnapshot snapshot = sampleSnapshot(42);
        ByteBuf buffer = Unpooled.buffer();

        StormSnapshot.STREAM_CODEC.encode(buffer, snapshot);
        StormSnapshot decoded = StormSnapshot.STREAM_CODEC.decode(buffer);

        assertEquals(snapshot, decoded);
        assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
    }

    @Test
    void flatConstructorAndAccessorsMatchNestedRecords() {
        StormSnapshot snapshot = sampleSnapshot(7);

        assertEquals(7, snapshot.id());
        assertEquals(ResourceLocation.fromNamespaceAndPath("weathermod", "rain"), snapshot.typeId());
        assertEquals(123.5D, snapshot.x());
        assertEquals(160.0D, snapshot.y());
        assertEquals(-77.25D, snapshot.z());
        assertEquals(215.5F, snapshot.headingDegrees(), EPSILON);
        assertEquals(3.25F, snapshot.speed(), EPSILON);
        assertEquals(0.8F, snapshot.intensity(), EPSILON);
        assertEquals(96.0F, snapshot.radius(), EPSILON);
        assertEquals(512.0F, snapshot.interestRadius(), EPSILON);
        assertEquals(600, snapshot.ageTicks());
        assertEquals(2400, snapshot.maxAgeTicks());

        assertEquals(new StormSnapshot.StormIdentity(7, snapshot.typeId()), snapshot.identity());
        assertEquals(new StormSnapshot.StormMotion(123.5D, 160.0D, -77.25D, 215.5F, 3.25F), snapshot.motion());
        assertEquals(new StormSnapshot.StormLife(0.8F, 96.0F, 512.0F, 600, 2400), snapshot.life());
    }

    @Test
    void lifeHelpersClampSensibly() {
        StormSnapshot snapshot = sampleSnapshot(1);
        assertEquals(1800, snapshot.remainingTicks());
        assertEquals(0.25F, snapshot.lifeFraction(), EPSILON);

        StormSnapshot overdue = new StormSnapshot(2, snapshot.typeId(), 0.0D, 160.0D, 0.0D, 0.0F, 0.0F, 0.0F,
            96.0F, 512.0F, 3000, 2400);
        assertEquals(0, overdue.remainingTicks());
        assertEquals(1.0F, overdue.lifeFraction(), EPSILON);
    }

    @Test
    void interestAndRadiusTestsAreHorizontal() {
        StormSnapshot snapshot = new StormSnapshot(3, ResourceLocation.fromNamespaceAndPath("weathermod", "fog"),
            0.0D, 160.0D, 0.0D, 0.0F, 0.0F, 0.5F, 100.0F, 500.0F, 0, 100);

        assertTrue(snapshot.isWithinInterest(300.0D, 400.0D), "exactly on the interest radius counts as inside");
        assertFalse(snapshot.isWithinInterest(300.0D, 400.1D));
        assertTrue(snapshot.isWithinRadius(60.0D, 80.0D));
        assertFalse(snapshot.isWithinRadius(60.0D, 80.1D));
        assertEquals(500.0D, snapshot.distanceTo(300.0D, 400.0D), 1.0E-9D);
    }

    @Test
    void nullComponentsAreRejected() {
        StormSnapshot snapshot = sampleSnapshot(1);
        assertThrows(NullPointerException.class, () -> new StormSnapshot(null, snapshot.motion(), snapshot.life()));
        assertThrows(NullPointerException.class, () -> new StormSnapshot.StormIdentity(1, null));
        assertThrows(NullPointerException.class, () -> new StormDeltaPayload(null));
    }

    @Test
    void stormListPayloadRoundTripsAndIsImmutable() {
        List<StormSnapshot> storms = new ArrayList<>();
        storms.add(sampleSnapshot(1));
        storms.add(sampleSnapshot(2));
        StormListPayload payload = new StormListPayload(storms);
        storms.clear();
        assertEquals(2, payload.storms().size(), "the payload keeps its own copy of the list");
        assertFalse(payload.isEmpty());

        RegistryFriendlyByteBuf buffer = registryBuffer();
        StormListPayload.STREAM_CODEC.encode(buffer, payload);
        StormListPayload decoded = StormListPayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(0, buffer.readableBytes());
        assertSame(StormListPayload.TYPE, decoded.type());
    }

    @Test
    void emptyStormListRoundTrips() {
        StormListPayload payload = new StormListPayload(List.of());
        RegistryFriendlyByteBuf buffer = registryBuffer();

        StormListPayload.STREAM_CODEC.encode(buffer, payload);
        StormListPayload decoded = StormListPayload.STREAM_CODEC.decode(buffer);

        assertTrue(decoded.isEmpty());
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void stormDeltaPayloadRoundTrips() {
        StormDeltaPayload payload = new StormDeltaPayload(sampleSnapshot(9));
        RegistryFriendlyByteBuf buffer = registryBuffer();

        StormDeltaPayload.STREAM_CODEC.encode(buffer, payload);
        StormDeltaPayload decoded = StormDeltaPayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(9, decoded.id());
        assertEquals(0, buffer.readableBytes());
        assertSame(StormDeltaPayload.TYPE, decoded.type());
    }

    @Test
    void stormRemovedPayloadRoundTrips() {
        StormRemovedPayload payload = new StormRemovedPayload(123456);
        RegistryFriendlyByteBuf buffer = registryBuffer();

        StormRemovedPayload.STREAM_CODEC.encode(buffer, payload);
        StormRemovedPayload decoded = StormRemovedPayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(0, buffer.readableBytes());
        assertSame(StormRemovedPayload.TYPE, decoded.type());
    }

    @Test
    void windUpdatePayloadRoundTrips() {
        WindUpdatePayload payload = new WindUpdatePayload(275.5F, 6.125F);
        RegistryFriendlyByteBuf buffer = registryBuffer();

        WindUpdatePayload.STREAM_CODEC.encode(buffer, payload);
        WindUpdatePayload decoded = WindUpdatePayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(0, buffer.readableBytes());
        assertSame(WindUpdatePayload.TYPE, decoded.type());
    }

    @Test
    void payloadTypeIdsAreDistinctAndNamespaced() {
        List<ResourceLocation> ids = List.of(StormListPayload.TYPE.id(), StormDeltaPayload.TYPE.id(),
            StormRemovedPayload.TYPE.id(), WindUpdatePayload.TYPE.id());
        assertEquals(ids.size(), ids.stream().distinct().count());
        for (ResourceLocation id : ids) {
            assertEquals("weathermod", id.getNamespace());
        }
    }

    @Test
    void detailRoundTripsThroughTheSnapshotCodec() {
        StormSnapshot snapshot = sampleSnapshot(42).withDetail(new StormSnapshot.StormDetail(12, 0.75F));
        ByteBuf buffer = Unpooled.buffer();

        StormSnapshot.STREAM_CODEC.encode(buffer, snapshot);
        StormSnapshot decoded = StormSnapshot.STREAM_CODEC.decode(buffer);

        assertEquals(snapshot, decoded);
        assertEquals(12, decoded.parentId());
        assertEquals(0.75F, decoded.hail(), EPSILON);
        assertEquals(new StormSnapshot.StormDetail(12, 0.75F), decoded.detail());
        assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
    }

    @Test
    void detailCodecWritesAVarIntAndAFloat() {
        ByteBuf none = Unpooled.buffer();
        StormSnapshot.StormDetail.STREAM_CODEC.encode(none, StormSnapshot.StormDetail.NONE);
        assertEquals(5, none.readableBytes(), "a one-byte parent id and a four-byte hail strength");
        assertEquals(StormSnapshot.StormDetail.NONE, StormSnapshot.StormDetail.STREAM_CODEC.decode(none));
        assertEquals(0, none.readableBytes());

        StormSnapshot.StormDetail child = new StormSnapshot.StormDetail(300, 0.5F);
        ByteBuf buffer = Unpooled.buffer();
        StormSnapshot.StormDetail.STREAM_CODEC.encode(buffer, child);
        assertEquals(6, buffer.readableBytes(), "a parent id of 300 takes two bytes as a var int");
        assertEquals(child, StormSnapshot.StormDetail.STREAM_CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes());

        ByteBuf plain = Unpooled.buffer();
        ByteBuf detailed = Unpooled.buffer();
        StormSnapshot.STREAM_CODEC.encode(plain, sampleSnapshot(1));
        StormSnapshot.STREAM_CODEC.encode(detailed, sampleSnapshot(1).withDetail(child));
        assertEquals(plain.readableBytes() + 1, detailed.readableBytes(),
            "the detail is the last thing on the wire and only the longer parent id adds a byte");
    }

    @Test
    void flatAndThreePartConstructorsCarryNoDetail() {
        StormSnapshot flat = sampleSnapshot(7);
        assertSame(StormSnapshot.StormDetail.NONE, flat.detail(), "the twelve-argument constructor has no detail");
        assertEquals(0, flat.parentId());
        assertEquals(0.0F, flat.hail(), EPSILON);

        StormSnapshot nested = new StormSnapshot(flat.identity(), flat.motion(), flat.life());
        assertSame(StormSnapshot.StormDetail.NONE, nested.detail(), "the three-part constructor has no detail");
        assertEquals(flat, nested);

        assertEquals(0, StormSnapshot.StormDetail.NONE.parentId());
        assertEquals(0.0F, StormSnapshot.StormDetail.NONE.hail(), EPSILON);
        assertEquals(new StormSnapshot.StormDetail(0, 0.0F), StormSnapshot.StormDetail.NONE);
    }

    @Test
    void withDetailReplacesOnlyTheDetail() {
        StormSnapshot plain = sampleSnapshot(7);
        StormSnapshot.StormDetail detail = new StormSnapshot.StormDetail(3, 0.4F);

        StormSnapshot detailed = plain.withDetail(detail);

        assertSame(plain.identity(), detailed.identity());
        assertSame(plain.motion(), detailed.motion());
        assertSame(plain.life(), detailed.life());
        assertSame(detail, detailed.detail());
        assertEquals(3, detailed.parentId());
        assertEquals(0.4F, detailed.hail(), EPSILON);
        assertEquals(0, plain.parentId(), "the original snapshot is left alone");
        assertEquals(new StormSnapshot(plain.identity(), plain.motion(), plain.life(), detail), detailed);
        assertFalse(plain.equals(detailed), "two snapshots that differ only in their detail are different");
    }

    @Test
    void nullDetailIsRejected() {
        StormSnapshot snapshot = sampleSnapshot(1);
        assertThrows(NullPointerException.class,
            () -> new StormSnapshot(snapshot.identity(), snapshot.motion(), snapshot.life(), null));
        assertThrows(NullPointerException.class, () -> snapshot.withDetail(null));
    }

    @Test
    void stormPayloadsCarryTheDetail() {
        StormSnapshot child = sampleSnapshot(9).withDetail(new StormSnapshot.StormDetail(4, 0.0F));
        StormSnapshot hailing = sampleSnapshot(4).withDetail(new StormSnapshot.StormDetail(0, 0.625F));

        RegistryFriendlyByteBuf deltaBuffer = registryBuffer();
        StormDeltaPayload.STREAM_CODEC.encode(deltaBuffer, new StormDeltaPayload(child));
        StormDeltaPayload delta = StormDeltaPayload.STREAM_CODEC.decode(deltaBuffer);
        assertEquals(4, delta.storm().parentId());
        assertEquals(0, deltaBuffer.readableBytes());

        RegistryFriendlyByteBuf listBuffer = registryBuffer();
        StormListPayload.STREAM_CODEC.encode(listBuffer, new StormListPayload(List.of(child, hailing)));
        StormListPayload list = StormListPayload.STREAM_CODEC.decode(listBuffer);
        assertEquals(List.of(child, hailing), list.storms());
        assertEquals(0.625F, list.storms().get(1).hail(), EPSILON);
        assertEquals(0, listBuffer.readableBytes());
    }

    @Test
    void lightningStrikePayloadRoundTrips() {
        LightningStrikePayload payload = new LightningStrikePayload(-1234.5D, 71.0625D, 987.25D, 0.85F);
        RegistryFriendlyByteBuf buffer = registryBuffer();

        LightningStrikePayload.STREAM_CODEC.encode(buffer, payload);
        assertEquals(28, buffer.readableBytes(), "three doubles and a float");
        LightningStrikePayload decoded = LightningStrikePayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(-1234.5D, decoded.x());
        assertEquals(71.0625D, decoded.y());
        assertEquals(987.25D, decoded.z());
        assertEquals(0.85F, decoded.power(), EPSILON);
        assertEquals(0, buffer.readableBytes());
        assertSame(LightningStrikePayload.TYPE, decoded.type());
        assertEquals(1024.0D, LightningStrikePayload.RANGE_BLOCKS);
    }

    @Test
    void downburstPayloadRoundTrips() {
        DownburstPayload payload = new DownburstPayload(300.5D, -42.75D, 24.0F, 20.0F, 100);
        RegistryFriendlyByteBuf buffer = registryBuffer();

        DownburstPayload.STREAM_CODEC.encode(buffer, payload);
        assertEquals(25, buffer.readableBytes(), "two doubles, two floats and a one-byte duration");
        DownburstPayload decoded = DownburstPayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(300.5D, decoded.x());
        assertEquals(-42.75D, decoded.z());
        assertEquals(24.0F, decoded.radius(), EPSILON);
        assertEquals(20.0F, decoded.speed(), EPSILON);
        assertEquals(100, decoded.durationTicks());
        assertEquals(0, buffer.readableBytes());
        assertSame(DownburstPayload.TYPE, decoded.type());
        assertEquals(96.0D, DownburstPayload.NOTIFY_MARGIN_BLOCKS);
    }

    @Test
    void newPayloadTypeIdsAreDistinctFromEachOtherAndFromTheOldOnes() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("weathermod", "funnel_spine"),
            FunnelSpinePayload.TYPE.id());
        assertEquals(ResourceLocation.fromNamespaceAndPath("weathermod", "lightning_strike"),
            LightningStrikePayload.TYPE.id());
        assertEquals(ResourceLocation.fromNamespaceAndPath("weathermod", "downburst"), DownburstPayload.TYPE.id());

        List<ResourceLocation> ids = List.of(StormListPayload.TYPE.id(), StormDeltaPayload.TYPE.id(),
            StormRemovedPayload.TYPE.id(), WindUpdatePayload.TYPE.id(), ClimateUpdatePayload.TYPE.id(),
            FunnelSpinePayload.TYPE.id(), LightningStrikePayload.TYPE.id(), DownburstPayload.TYPE.id());
        assertEquals(ids.size(), ids.stream().distinct().count());
    }
}
