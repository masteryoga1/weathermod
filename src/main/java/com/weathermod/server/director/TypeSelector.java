package com.weathermod.server.director;

import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.server.climate.ClimateSample;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Pure helpers that decide which weather type a natural spawn becomes: an eligibility test against the place and
 * climate, and a weighted pick among the types that passed it.
 */
public final class TypeSelector {
    private TypeSelector() {
    }

    /**
     * One entry of a weighted pick. Entries with a weight of zero or less are never picked.
     *
     * @param value  the thing to pick
     * @param weight its relative chance
     */
    public record Candidate<T>(T value, int weight) {
        public Candidate {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The sum of the positive weights, as a long so a large list cannot overflow. */
    public static <T> long totalWeight(List<Candidate<T>> candidates) {
        long total = 0L;
        for (Candidate<T> candidate : candidates) {
            if (candidate.weight() > 0) {
                total += candidate.weight();
            }
        }
        return total;
    }

    /**
     * Picks one candidate with probability proportional to its weight. Candidates with no positive weight are
     * skipped; when none has one the result is empty and the random source is left untouched.
     */
    public static <T> Optional<T> pick(List<Candidate<T>> candidates, RandomSource random) {
        long total = totalWeight(candidates);
        if (total <= 0L) {
            return Optional.empty();
        }
        long roll = total <= Integer.MAX_VALUE
            ? random.nextInt((int) total)
            : Math.floorMod(random.nextLong(), total);
        for (Candidate<T> candidate : candidates) {
            if (candidate.weight() <= 0) {
                continue;
            }
            roll -= candidate.weight();
            if (roll < 0L) {
                return Optional.of(candidate.value());
            }
        }
        return Optional.empty();
    }

    /**
     * True when a type may form naturally here: it spawns naturally at all, allows the dimension and the biome, its
     * climate window contains the sampled temperature and humidity, and its family has a system to simulate it.
     */
    public static boolean eligible(WeatherType type, ResourceKey<Level> dimension, Holder<Biome> biome,
                                   ClimateSample climate, Set<WeatherFamily> supportedFamilies) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(biome, "biome");
        Objects.requireNonNull(climate, "climate");
        Objects.requireNonNull(supportedFamilies, "supportedFamilies");
        return type.spawnsNaturally()
            && supportedFamilies.contains(type.family())
            && type.allowsDimension(dimension)
            && type.allowsClimate(climate.temperature(), climate.humidity())
            && type.allowsBiome(biome);
    }
}
