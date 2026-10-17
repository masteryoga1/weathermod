package com.weathermod.network;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable view of one storm exactly as the server sends it to clients. The twelve values are grouped into three
 * nested records so every stream codec stays within the six-field limit of {@link StreamCodec#composite}; the flat
 * constructor and accessors hide that grouping from callers.
 */
public record StormSnapshot(StormIdentity identity, StormMotion motion, StormLife life) {
    /** Network codec. It only needs a plain {@link ByteBuf}, so it also feeds the payload codecs over registry buffers. */
    public static final StreamCodec<ByteBuf, StormSnapshot> STREAM_CODEC = StreamCodec.composite(
        StormIdentity.STREAM_CODEC, StormSnapshot::identity,
        StormMotion.STREAM_CODEC, StormSnapshot::motion,
        StormLife.STREAM_CODEC, StormSnapshot::life,
        StormSnapshot::new);

    public StormSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(life, "life");
    }

    /**
     * Flat constructor used by the server when it snapshots a storm instance.
     *
     * @param id             storm id, unique within its dimension
     * @param typeId         id of the weather type in the {@code weathermod:weather_type} registry
     * @param x              centre x in blocks
     * @param y              cloud base height in blocks
     * @param z              centre z in blocks
     * @param headingDegrees compass heading the storm moves toward
     * @param speed          movement speed in blocks per second
     * @param intensity      current intensity in {@code [0, 1]}
     * @param radius         radius of the storm's effects in blocks
     * @param interestRadius players farther than this from the centre never receive the storm
     * @param ageTicks       ticks lived so far
     * @param maxAgeTicks    ticks after which the storm expires
     */
    public StormSnapshot(int id, ResourceLocation typeId, double x, double y, double z, float headingDegrees,
                         float speed, float intensity, float radius, float interestRadius, int ageTicks,
                         int maxAgeTicks) {
        this(new StormIdentity(id, typeId),
            new StormMotion(x, y, z, headingDegrees, speed),
            new StormLife(intensity, radius, interestRadius, ageTicks, maxAgeTicks));
    }

    /** Storm id, unique within its dimension. */
    public int id() {
        return identity.id();
    }

    /** Id of the weather type in the {@code weathermod:weather_type} registry. */
    public ResourceLocation typeId() {
        return identity.typeId();
    }

    public double x() {
        return motion.x();
    }

    /** Cloud base height in blocks. */
    public double y() {
        return motion.y();
    }

    public double z() {
        return motion.z();
    }

    /** Compass heading the storm moves toward, in degrees. */
    public float headingDegrees() {
        return motion.headingDegrees();
    }

    /** Movement speed in blocks per second. */
    public float speed() {
        return motion.speed();
    }

    /** Current intensity in {@code [0, 1]}. */
    public float intensity() {
        return life.intensity();
    }

    /** Radius of the storm's effects in blocks. */
    public float radius() {
        return life.radius();
    }

    /** Players farther than this from the centre never receive the storm. */
    public float interestRadius() {
        return life.interestRadius();
    }

    public int ageTicks() {
        return life.ageTicks();
    }

    public int maxAgeTicks() {
        return life.maxAgeTicks();
    }

    /** Ticks left before the storm expires, never negative. */
    public int remainingTicks() {
        return Math.max(0, life.maxAgeTicks() - life.ageTicks());
    }

    /** How far through its life the storm is, in {@code [0, 1]}. */
    public float lifeFraction() {
        if (life.maxAgeTicks() <= 0) {
            return 1.0F;
        }
        float fraction = life.ageTicks() / (float) life.maxAgeTicks();
        return fraction < 0.0F ? 0.0F : Math.min(fraction, 1.0F);
    }

    /** Squared horizontal distance from the storm centre to a point. */
    public double distanceToSqr(double px, double pz) {
        double dx = px - motion.x();
        double dz = pz - motion.z();
        return dx * dx + dz * dz;
    }

    /** Horizontal distance from the storm centre to a point. */
    public double distanceTo(double px, double pz) {
        return Math.sqrt(distanceToSqr(px, pz));
    }

    /** Whether a player at the given horizontal position is inside the storm's interest radius. */
    public boolean isWithinInterest(double px, double pz) {
        double interest = life.interestRadius();
        return distanceToSqr(px, pz) <= interest * interest;
    }

    /** Whether a point is inside the storm's effect radius, measured horizontally. */
    public boolean isWithinRadius(double px, double pz) {
        double radius = life.radius();
        return distanceToSqr(px, pz) <= radius * radius;
    }

    /** Which storm this is: its id and its weather type. */
    public record StormIdentity(int id, ResourceLocation typeId) {
        public static final StreamCodec<ByteBuf, StormIdentity> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StormIdentity::id,
            ResourceLocation.STREAM_CODEC, StormIdentity::typeId,
            StormIdentity::new);

        public StormIdentity {
            Objects.requireNonNull(typeId, "typeId");
        }
    }

    /** Where the storm is and where it is going: centre position, cloud base, heading and speed. */
    public record StormMotion(double x, double y, double z, float headingDegrees, float speed) {
        public static final StreamCodec<ByteBuf, StormMotion> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, StormMotion::x,
            ByteBufCodecs.DOUBLE, StormMotion::y,
            ByteBufCodecs.DOUBLE, StormMotion::z,
            ByteBufCodecs.FLOAT, StormMotion::headingDegrees,
            ByteBufCodecs.FLOAT, StormMotion::speed,
            StormMotion::new);
    }

    /** How strong and how big the storm is and how far through its life: intensity, radii and age. */
    public record StormLife(float intensity, float radius, float interestRadius, int ageTicks, int maxAgeTicks) {
        public static final StreamCodec<ByteBuf, StormLife> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, StormLife::intensity,
            ByteBufCodecs.FLOAT, StormLife::radius,
            ByteBufCodecs.FLOAT, StormLife::interestRadius,
            ByteBufCodecs.VAR_INT, StormLife::ageTicks,
            ByteBufCodecs.VAR_INT, StormLife::maxAgeTicks,
            StormLife::new);
    }
}
