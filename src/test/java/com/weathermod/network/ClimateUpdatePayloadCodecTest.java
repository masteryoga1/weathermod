package com.weathermod.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.PrecipitationKind;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the climate update and the three-field wind update through their stream codecs. Needs no Minecraft
 * bootstrap: only primitive codecs, the precipitation kind codec and an empty registry access are involved.
 */
class ClimateUpdatePayloadCodecTest {
    private static final float EPSILON = 1.0E-6F;

    private static RegistryFriendlyByteBuf registryBuffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    private static ClimateUpdatePayload roundTrip(ClimateUpdatePayload payload) {
        RegistryFriendlyByteBuf buffer = registryBuffer();
        ClimateUpdatePayload.STREAM_CODEC.encode(buffer, payload);
        ClimateUpdatePayload decoded = ClimateUpdatePayload.STREAM_CODEC.decode(buffer);
        assertEquals(0, buffer.readableBytes(), "the codec must consume exactly what it wrote");
        return decoded;
    }

    @Test
    void climateUpdateRoundTripsThroughItsCodec() {
        ClimateUpdatePayload payload = new ClimateUpdatePayload(0.8F, 0.4F, PrecipitationKind.RAIN, "summer", 0.42F);

        ClimateUpdatePayload decoded = roundTrip(payload);

        assertEquals(payload, decoded);
        assertEquals(0.8F, decoded.temperature(), EPSILON);
        assertEquals(0.4F, decoded.humidity(), EPSILON);
        assertEquals(PrecipitationKind.RAIN, decoded.kind());
        assertEquals("summer", decoded.seasonName());
        assertEquals(0.42F, decoded.seasonProgress(), EPSILON);
        assertTrue(decoded.hasSeason());
        assertSame(ClimateUpdatePayload.TYPE, decoded.type());
    }

    @Test
    void everyPrecipitationKindRoundTrips() {
        for (PrecipitationKind kind : PrecipitationKind.values()) {
            ClimateUpdatePayload payload = new ClimateUpdatePayload(-0.5F, 1.0F, kind, "mid_winter", 0.875F);
            assertEquals(payload, roundTrip(payload), "kind " + kind);
        }
    }

    @Test
    void extremeValuesAndLongSeasonNamesRoundTrip() {
        ClimateUpdatePayload nether = new ClimateUpdatePayload(2.5F, 0.0F, PrecipitationKind.NONE,
            ClimateUpdatePayload.NO_SEASON, 0.0F);
        ClimateUpdatePayload decoded = roundTrip(nether);
        assertEquals(nether, decoded);
        assertFalse(decoded.hasSeason(), "the no-season name reports no season");

        ClimateUpdatePayload tropical = new ClimateUpdatePayload(-1.0F, 1.0F, PrecipitationKind.SLEET,
            "early_wet_with_a_rather_long_name", 0.999F);
        assertEquals(tropical, roundTrip(tropical));

        ClimateUpdatePayload unnamed = new ClimateUpdatePayload(0.0F, 0.5F, PrecipitationKind.SNOW, "", 0.5F);
        ClimateUpdatePayload decodedUnnamed = roundTrip(unnamed);
        assertEquals(unnamed, decodedUnnamed);
        assertTrue(decodedUnnamed.hasSeason(), "only the no-season name means no season");
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> new ClimateUpdatePayload(0.8F, 0.4F, null, "summer", 0.42F));
        assertThrows(NullPointerException.class,
            () -> new ClimateUpdatePayload(0.8F, 0.4F, PrecipitationKind.RAIN, null, 0.42F));
    }

    @Test
    void noSeasonNameMatchesTheServerSideConstant() {
        assertEquals("none", ClimateUpdatePayload.NO_SEASON);
    }

    @Test
    void windUpdateCarriesTheGustAndRoundTrips() {
        WindUpdatePayload payload = new WindUpdatePayload(275.5F, 6.125F, 9.75F);
        RegistryFriendlyByteBuf buffer = registryBuffer();

        WindUpdatePayload.STREAM_CODEC.encode(buffer, payload);
        WindUpdatePayload decoded = WindUpdatePayload.STREAM_CODEC.decode(buffer);

        assertEquals(payload, decoded);
        assertEquals(9.75F, decoded.gustSpeed(), EPSILON);
        assertTrue(decoded.isGusting());
        assertEquals(0, buffer.readableBytes());
        assertSame(WindUpdatePayload.TYPE, decoded.type());
    }

    @Test
    void twoArgumentWindUpdateHasNoGust() {
        WindUpdatePayload payload = new WindUpdatePayload(90.0F, 4.0F);

        assertEquals(4.0F, payload.gustSpeed(), EPSILON);
        assertFalse(payload.isGusting());
        assertEquals(new WindUpdatePayload(90.0F, 4.0F, 4.0F), payload);
    }

    @Test
    void payloadTypeIdsAreDistinctAndNamespaced() {
        List<ResourceLocation> ids = List.of(StormListPayload.TYPE.id(), StormDeltaPayload.TYPE.id(),
            StormRemovedPayload.TYPE.id(), WindUpdatePayload.TYPE.id(), ClimateUpdatePayload.TYPE.id());
        assertEquals(ids.size(), ids.stream().distinct().count());
        for (ResourceLocation id : ids) {
            assertEquals("weathermod", id.getNamespace());
        }
        assertEquals("climate_update", ClimateUpdatePayload.TYPE.id().getPath());
    }
}
