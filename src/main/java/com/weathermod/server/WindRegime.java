package com.weathermod.server;

import com.mojang.serialization.Codec;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;

/**
 * A wind regime: the band of sustained speeds the wind random walk stays inside for the next ten minutes or so,
 * with how likely the regime is to be picked and how strong its gusts may get relative to the sustained speed.
 */
public enum WindRegime implements StringRepresentable {
    CALM("calm", 0.0F, 3.0F, 25, 1.3F),
    BREEZE("breeze", 2.0F, 7.0F, 45, 1.5F),
    WINDY("windy", 5.0F, 11.0F, 22, 1.7F),
    GALE("gale", 9.0F, 15.0F, 8, 1.9F);

    private static final WindRegime[] VALUES = values();
    private static final int TOTAL_WEIGHT = sumWeights();

    /** Serializes a regime as its lower-case name, for example {@code "breeze"}. */
    public static final Codec<WindRegime> CODEC = StringRepresentable.fromEnum(WindRegime::values);

    private final String serializedName;
    private final float minSpeed;
    private final float maxSpeed;
    private final int weight;
    private final float maxGustFactor;

    WindRegime(String serializedName, float minSpeed, float maxSpeed, int weight, float maxGustFactor) {
        this.serializedName = serializedName;
        this.minSpeed = minSpeed;
        this.maxSpeed = maxSpeed;
        this.weight = weight;
        this.maxGustFactor = maxGustFactor;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** Slowest sustained speed the random walk targets in this regime, in blocks per second. */
    public float minSpeed() {
        return minSpeed;
    }

    /** Fastest sustained speed the random walk targets in this regime, in blocks per second. */
    public float maxSpeed() {
        return maxSpeed;
    }

    /** Middle of the regime's speed band, in blocks per second. */
    public float midSpeed() {
        return (minSpeed + maxSpeed) * 0.5F;
    }

    /** Relative chance of this regime being picked when a new one starts; the weights of all regimes sum to 100. */
    public int weight() {
        return weight;
    }

    /** Largest multiplier a gust may apply to the sustained speed while this regime holds. */
    public float maxGustFactor() {
        return maxGustFactor;
    }

    /** Picks a regime at random, each with a probability proportional to its {@link #weight()}. */
    public static WindRegime pick(RandomSource random) {
        int roll = random.nextInt(TOTAL_WEIGHT);
        for (WindRegime regime : VALUES) {
            roll -= regime.weight;
            if (roll < 0) {
                return regime;
            }
        }
        return VALUES[VALUES.length - 1];
    }

    /** The regime whose speed band centre is closest to the given sustained speed; ties go to the calmer regime. */
    public static WindRegime forSpeed(float speed) {
        WindRegime best = VALUES[0];
        float bestDistance = Math.abs(speed - best.midSpeed());
        for (int i = 1; i < VALUES.length; i++) {
            float distance = Math.abs(speed - VALUES[i].midSpeed());
            if (distance < bestDistance) {
                best = VALUES[i];
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Looks a regime up by its serialized name; empty when the name is unknown. */
    public static Optional<WindRegime> byName(String name) {
        for (WindRegime regime : VALUES) {
            if (regime.serializedName.equals(name)) {
                return Optional.of(regime);
            }
        }
        return Optional.empty();
    }

    private static int sumWeights() {
        int total = 0;
        for (WindRegime regime : values()) {
            total += regime.weight;
        }
        return total;
    }
}
