package com.weathermod.client;

import com.weathermod.core.Headings;
import com.weathermod.network.StormSnapshot;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side mirror of one storm. It keeps the last two positions so renderers can interpolate between ticks,
 * predicts movement along the storm's heading between server updates and ages the storm locally; every server
 * snapshot overwrites the prediction.
 */
public final class ClientStorm {
    private final int id;
    private ResourceLocation typeId;
    private double prevX;
    private double prevY;
    private double prevZ;
    private double x;
    private double y;
    private double z;
    private float headingDegrees;
    private float speed;
    private float prevIntensity;
    private float intensity;
    private float radius;
    private float interestRadius;
    private int ageTicks;
    private int maxAgeTicks;
    private int ticksSinceUpdate;

    /** Creates a storm from its first snapshot; previous and current state start out equal. */
    public ClientStorm(StormSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.id = snapshot.id();
        copyFrom(snapshot);
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
        this.prevIntensity = intensity;
    }

    /**
     * Replaces the current state with an authoritative snapshot. The state before the update becomes the
     * interpolation origin, so a correction is smoothed over one tick instead of snapping.
     *
     * @throws IllegalArgumentException if the snapshot describes a different storm id
     */
    public void update(StormSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.id() != id) {
            throw new IllegalArgumentException("Snapshot for storm " + snapshot.id() + " applied to storm " + id);
        }
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
        this.prevIntensity = intensity;
        copyFrom(snapshot);
    }

    /**
     * Advances the storm by one client tick: remembers the current position as the interpolation origin, moves
     * along the heading at the known speed, ages the storm and counts another tick without a server update.
     */
    public void tick() {
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
        this.prevIntensity = intensity;
        double blocksThisTick = speed / SharedConstants.TICKS_PER_SECOND;
        this.x += Headings.directionX(headingDegrees) * blocksThisTick;
        this.z += Headings.directionZ(headingDegrees) * blocksThisTick;
        this.ageTicks++;
        this.ticksSinceUpdate++;
    }

    /** Storm id, unique within the dimension the client is in. */
    public int id() {
        return id;
    }

    /** Id of the weather type in the {@code weathermod:weather_type} registry. */
    public ResourceLocation typeId() {
        return typeId;
    }

    /** Centre x at the end of the last tick. */
    public double x() {
        return x;
    }

    /** Cloud base height at the end of the last tick. */
    public double y() {
        return y;
    }

    /** Centre z at the end of the last tick. */
    public double z() {
        return z;
    }

    /** Centre x interpolated between the previous and the current tick. */
    public double getX(float partialTick) {
        return lerp(partialTick, prevX, x);
    }

    /** Cloud base height interpolated between the previous and the current tick. */
    public double getY(float partialTick) {
        return lerp(partialTick, prevY, y);
    }

    /** Centre z interpolated between the previous and the current tick. */
    public double getZ(float partialTick) {
        return lerp(partialTick, prevZ, z);
    }

    /** Compass heading the storm moves toward, in degrees; 0 is north (negative z), 90 is east (positive x). */
    public float headingDegrees() {
        return headingDegrees;
    }

    /** Movement speed in blocks per second. */
    public float speed() {
        return speed;
    }

    /** Current intensity in {@code [0, 1]}. */
    public float intensity() {
        return intensity;
    }

    /** Intensity interpolated between the previous and the current tick. */
    public float getIntensity(float partialTick) {
        return (float) lerp(partialTick, prevIntensity, intensity);
    }

    /** Radius of the storm's effects in blocks. */
    public float radius() {
        return radius;
    }

    /** Players farther than this from the centre never receive the storm. */
    public float interestRadius() {
        return interestRadius;
    }

    /** Ticks lived so far, including ticks aged locally since the last server update. */
    public int ageTicks() {
        return ageTicks;
    }

    /** Ticks after which the server will remove the storm. */
    public int maxAgeTicks() {
        return maxAgeTicks;
    }

    /** Age in whole real seconds. */
    public int ageSeconds() {
        return ageTicks / SharedConstants.TICKS_PER_SECOND;
    }

    /** Maximum age in whole real seconds. */
    public int maxAgeSeconds() {
        return maxAgeTicks / SharedConstants.TICKS_PER_SECOND;
    }

    /** Ticks left before the storm expires, never negative. */
    public int remainingTicks() {
        return Math.max(0, maxAgeTicks - ageTicks);
    }

    /** How far through its life the storm is, in {@code [0, 1]}. */
    public float lifeFraction() {
        if (maxAgeTicks <= 0) {
            return 1.0F;
        }
        float fraction = ageTicks / (float) maxAgeTicks;
        return fraction < 0.0F ? 0.0F : Math.min(fraction, 1.0F);
    }

    /**
     * Client ticks since the last snapshot from the server. In-range storms are re-sent every sync interval, so
     * once this passes {@link ClientWeatherState#STALE_TICKS} the storm has left the player's interest radius and
     * {@link ClientWeatherState#tick()} drops it.
     */
    public int ticksSinceUpdate() {
        return ticksSinceUpdate;
    }

    /** Squared horizontal distance from the storm centre to a point. */
    public double distanceToSqr(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz;
    }

    /** Horizontal distance from the storm centre to a point. */
    public double distanceTo(double px, double pz) {
        return Math.sqrt(distanceToSqr(px, pz));
    }

    /** Whether a point is inside the storm's effect radius, measured horizontally. */
    public boolean isWithinRadius(double px, double pz) {
        return distanceToSqr(px, pz) <= (double) radius * radius;
    }

    private void copyFrom(StormSnapshot snapshot) {
        this.typeId = snapshot.typeId();
        this.x = snapshot.x();
        this.y = snapshot.y();
        this.z = snapshot.z();
        this.headingDegrees = snapshot.headingDegrees();
        this.speed = snapshot.speed();
        this.intensity = snapshot.intensity();
        this.radius = snapshot.radius();
        this.interestRadius = snapshot.interestRadius();
        this.ageTicks = snapshot.ageTicks();
        this.maxAgeTicks = snapshot.maxAgeTicks();
        this.ticksSinceUpdate = 0;
    }

    private static double lerp(float delta, double start, double end) {
        return start + (end - start) * delta;
    }

    @Override
    public String toString() {
        return "ClientStorm[id=" + id + ", type=" + typeId + ", x=" + x + ", z=" + z + ", intensity=" + intensity
            + ", age=" + ageTicks + "/" + maxAgeTicks + "]";
    }
}
