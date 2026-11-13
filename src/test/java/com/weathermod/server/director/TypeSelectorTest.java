package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Either;
import com.weathermod.core.PrecipitationMode;
import com.weathermod.core.Trigger;
import com.weathermod.core.TriggerContext;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.server.climate.ClimateSample;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.Test;

/**
 * Checks the weighted pick and the eligibility rules a natural spawn chooses its type with: place and climate,
 * the type's trigger, the severe-weather gate and the weight a cold front adds. Needs no Minecraft bootstrap: the
 * biome is a tag-only holder and the dimension a bare registry key.
 */
class TypeSelectorTest {
    private static final ResourceKey<Level> OVERWORLD =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> THE_END =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_end"));
    private static final TagKey<Biome> SNOWY =
        TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("weathermod", "snowy"));
    private static final TagKey<Biome> HUMID =
        TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("weathermod", "humid"));
    /** The four families that have a system: what the director hands in as the supported families. */
    private static final Set<WeatherFamily> SUPPORTED = EnumSet.of(WeatherFamily.PRECIPITATION,
        WeatherFamily.CONVECTIVE, WeatherFamily.VORTEX, WeatherFamily.OBSCURANT);
    private static final ClimateSample PLAINS = new ClimateSample(0.8F, 0.4F);
    private static final TriggerContext CALM_DAY = new TriggerContext(4.0F, true, "");

    /** A biome holder that knows only which tags it carries; the selector never reads the biome itself. */
    private static final class TaggedBiome implements Holder<Biome> {
        private final Set<TagKey<Biome>> tags;

        TaggedBiome(Set<TagKey<Biome>> tags) {
            this.tags = tags;
        }

        @Override
        public Biome value() {
            throw new UnsupportedOperationException("the selector must not read the biome itself");
        }

        @Override
        public boolean isBound() {
            return true;
        }

        @Override
        public boolean is(ResourceLocation location) {
            return false;
        }

        @Override
        public boolean is(ResourceKey<Biome> resourceKey) {
            return false;
        }

        @Override
        public boolean is(Predicate<ResourceKey<Biome>> predicate) {
            return false;
        }

        @Override
        public boolean is(TagKey<Biome> tagKey) {
            return tags.contains(tagKey);
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean is(Holder<Biome> holder) {
            return holder == this;
        }

        @Override
        public Stream<TagKey<Biome>> tags() {
            return tags.stream();
        }

        @Override
        public Either<ResourceKey<Biome>, Biome> unwrap() {
            throw new UnsupportedOperationException("the selector must not unwrap the biome");
        }

        @Override
        public Optional<ResourceKey<Biome>> unwrapKey() {
            return Optional.empty();
        }

        @Override
        public Kind kind() {
            return Kind.DIRECT;
        }

        @Override
        public boolean canSerializeIn(HolderOwner<Biome> owner) {
            return false;
        }
    }

    private static WeatherType type(WeatherFamily family, int weight, boolean enabled, WeatherType.Climate climate,
                                    Optional<TagKey<Biome>> biomes, List<ResourceLocation> dimensions) {
        return new WeatherType(family, WeatherType.DEFAULT_INTENSITY, climate, WeatherType.DEFAULT_DURATION_SECONDS,
            WeatherType.DEFAULT_RADIUS, WeatherType.DEFAULT_INTEREST_RADIUS, weight, WeatherType.Visuals.NONE,
            PrecipitationMode.AUTO, biomes, dimensions, enabled);
    }

    /** A convective type that fits every place and climate, so only its trigger and its severity decide. */
    private static WeatherType triggered(Trigger trigger, boolean severe, int weight) {
        return new WeatherType(WeatherFamily.CONVECTIVE, WeatherType.DEFAULT_INTENSITY, WeatherType.Climate.ANY,
            WeatherType.DEFAULT_DURATION_SECONDS, WeatherType.DEFAULT_RADIUS, WeatherType.DEFAULT_INTEREST_RADIUS,
            weight, WeatherType.Visuals.NONE, PrecipitationMode.AUTO, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS, true, severe, Optional.empty(), Optional.empty(), trigger);
    }

    /** How often the first of two types wins a seeded draw of 10000 when each is weighted through its trigger. */
    private static double shareOfFirst(WeatherType first, WeatherType second, TriggerContext context, long seed) {
        List<TypeSelector.Candidate<String>> candidates = List.of(
            candidate("first", first.trigger().weight(first.weight(), context)),
            candidate("second", second.trigger().weight(second.weight(), context)));
        RandomSource random = RandomSource.create(seed);
        int rounds = 10_000;
        int picked = 0;
        for (int i = 0; i < rounds; i++) {
            if (TypeSelector.pick(candidates, random).orElseThrow().equals("first")) {
                picked++;
            }
        }
        return picked / (double) rounds;
    }

    private static WeatherType rain() {
        return type(WeatherFamily.PRECIPITATION, 10, true, WeatherType.Climate.ANY, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS);
    }

    private static <T> TypeSelector.Candidate<T> candidate(T value, int weight) {
        return new TypeSelector.Candidate<>(value, weight);
    }

    @Test
    void candidateRejectsANullValue() {
        assertThrows(NullPointerException.class, () -> new TypeSelector.Candidate<String>(null, 1));
    }

    @Test
    void pickOfNothingOrOfOnlyWeightlessCandidatesIsEmpty() {
        RandomSource random = RandomSource.create(1L);
        assertEquals(Optional.empty(), TypeSelector.pick(List.of(), random));
        assertEquals(Optional.empty(), TypeSelector.pick(List.of(candidate("a", 0), candidate("b", -5)), random));
        assertEquals(0L, TypeSelector.totalWeight(List.of(candidate("a", 0), candidate("b", -5))));
        assertEquals(RandomSource.create(1L).nextInt(), random.nextInt(),
            "an empty pick leaves the random source untouched");
    }

    @Test
    void pickOfASingleWeightedCandidateAlwaysReturnsIt() {
        RandomSource random = RandomSource.create(2L);
        List<TypeSelector.Candidate<String>> candidates = List.of(candidate("zero", 0), candidate("only", 7));
        for (int i = 0; i < 100; i++) {
            assertEquals(Optional.of("only"), TypeSelector.pick(candidates, random));
        }
        assertEquals(7L, TypeSelector.totalWeight(candidates));
    }

    @Test
    void pickFollowsTheWeightsAndSkipsWeightlessCandidates() {
        RandomSource random = RandomSource.create(3L);
        List<TypeSelector.Candidate<String>> candidates = List.of(
            candidate("light", 1), candidate("never", 0), candidate("heavy", 3));
        int light = 0;
        int heavy = 0;
        int rounds = 20_000;
        for (int i = 0; i < rounds; i++) {
            String picked = TypeSelector.pick(candidates, random).orElseThrow();
            if (picked.equals("light")) {
                light++;
            } else if (picked.equals("heavy")) {
                heavy++;
            } else {
                throw new AssertionError("picked a weightless candidate: " + picked);
            }
        }
        assertEquals(rounds, light + heavy);
        assertEquals(0.25D, light / (double) rounds, 0.02D, "weight 1 of 4 is picked a quarter of the time");
        assertEquals(0.75D, heavy / (double) rounds, 0.02D, "weight 3 of 4 is picked three quarters of the time");
    }

    @Test
    void pickIsDeterministicForASeedAndCoversTheWholeRange() {
        List<TypeSelector.Candidate<Integer>> candidates = List.of(candidate(0, 2), candidate(1, 2), candidate(2, 2));
        assertEquals(TypeSelector.pick(candidates, RandomSource.create(9L)),
            TypeSelector.pick(candidates, RandomSource.create(9L)));

        RandomSource random = RandomSource.create(10L);
        boolean[] seen = new boolean[3];
        for (int i = 0; i < 300; i++) {
            seen[TypeSelector.pick(candidates, random).orElseThrow()] = true;
        }
        assertTrue(seen[0] && seen[1] && seen[2], "every weighted candidate is reachable, including the last");
    }

    @Test
    void pickHandlesWeightsThatOverflowAnInt() {
        List<TypeSelector.Candidate<String>> candidates = List.of(
            candidate("first", Integer.MAX_VALUE), candidate("second", Integer.MAX_VALUE));
        assertEquals(2L * Integer.MAX_VALUE, TypeSelector.totalWeight(candidates));

        RandomSource random = RandomSource.create(11L);
        boolean sawFirst = false;
        boolean sawSecond = false;
        for (int i = 0; i < 200; i++) {
            String picked = TypeSelector.pick(candidates, random).orElseThrow();
            sawFirst |= picked.equals("first");
            sawSecond |= picked.equals("second");
        }
        assertTrue(sawFirst && sawSecond, "both halves of a long total are reachable");
    }

    @Test
    void eligibleAcceptsATypeThatFitsEverywhere() {
        assertTrue(TypeSelector.eligible(rain(), OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
        assertTrue(TypeSelector.eligible(rain(), OVERWORLD, new TaggedBiome(Set.of(SNOWY, HUMID)), PLAINS, SUPPORTED),
            "a type without a biome tag accepts any biome");
    }

    @Test
    void eligibleRejectsDisabledOrWeightlessTypes() {
        WeatherType disabled = type(WeatherFamily.PRECIPITATION, 10, false, WeatherType.Climate.ANY,
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);
        WeatherType weightless = type(WeatherFamily.PRECIPITATION, 0, true, WeatherType.Climate.ANY,
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);

        assertFalse(TypeSelector.eligible(disabled, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
        assertFalse(TypeSelector.eligible(weightless, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED),
            "command-only types never spawn naturally");
    }

    @Test
    void eligibleRespectsTheDimensionList() {
        assertFalse(TypeSelector.eligible(rain(), THE_END, new TaggedBiome(Set.of()), PLAINS, SUPPORTED),
            "the default dimensions are the Overworld and the Nether");

        WeatherType endOnly = type(WeatherFamily.PRECIPITATION, 10, true, WeatherType.Climate.ANY,
            Optional.empty(), List.of(THE_END.location()));
        assertTrue(TypeSelector.eligible(endOnly, THE_END, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
        assertFalse(TypeSelector.eligible(endOnly, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
    }

    @Test
    void eligibleRespectsTheBiomeTag() {
        WeatherType snowOnly = type(WeatherFamily.PRECIPITATION, 10, true, WeatherType.Climate.ANY,
            Optional.of(SNOWY), WeatherType.DEFAULT_DIMENSIONS);

        assertTrue(TypeSelector.eligible(snowOnly, OVERWORLD, new TaggedBiome(Set.of(SNOWY)), PLAINS, SUPPORTED));
        assertTrue(
            TypeSelector.eligible(snowOnly, OVERWORLD, new TaggedBiome(Set.of(SNOWY, HUMID)), PLAINS, SUPPORTED));
        assertFalse(TypeSelector.eligible(snowOnly, OVERWORLD, new TaggedBiome(Set.of(HUMID)), PLAINS, SUPPORTED));
        assertFalse(TypeSelector.eligible(snowOnly, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
    }

    @Test
    void eligibleRespectsTheClimateWindow() {
        WeatherType cold = type(WeatherFamily.PRECIPITATION, 10, true, new WeatherType.Climate(-1.0F, 0.3F, 0.0F, 1.0F),
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);
        WeatherType humid = type(WeatherFamily.PRECIPITATION, 10, true,
            new WeatherType.Climate(-1.0F, 2.0F, 0.6F, 1.0F), Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);
        TaggedBiome anywhere = new TaggedBiome(Set.of());

        assertTrue(TypeSelector.eligible(cold, OVERWORLD, anywhere, new ClimateSample(0.05F, 0.5F), SUPPORTED));
        assertTrue(TypeSelector.eligible(cold, OVERWORLD, anywhere, new ClimateSample(0.3F, 0.5F), SUPPORTED),
            "the window is closed at both ends");
        assertFalse(TypeSelector.eligible(cold, OVERWORLD, anywhere, PLAINS, SUPPORTED));
        assertFalse(TypeSelector.eligible(humid, OVERWORLD, anywhere, PLAINS, SUPPORTED));
        assertTrue(TypeSelector.eligible(humid, OVERWORLD, anywhere, new ClimateSample(0.8F, 0.9F), SUPPORTED));
    }

    @Test
    void eligibleRejectsFamiliesWithoutASystem() {
        WeatherType cyclone = type(WeatherFamily.TROPICAL_CYCLONE, 10, true, WeatherType.Climate.ANY,
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);
        WeatherType fog = type(WeatherFamily.OBSCURANT, 10, true, WeatherType.Climate.ANY, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS);

        assertFalse(TypeSelector.eligible(cyclone, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
        assertTrue(TypeSelector.eligible(fog, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, SUPPORTED));
        assertTrue(TypeSelector.eligible(cyclone, OVERWORLD, new TaggedBiome(Set.of()), PLAINS,
            EnumSet.allOf(WeatherFamily.class)), "the same type passes once its family has a system");
        assertFalse(TypeSelector.eligible(fog, OVERWORLD, new TaggedBiome(Set.of()), PLAINS, Set.of()));
    }

    @Test
    void theFourFamiliesWithASystemAreEligible() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        assertEquals(4, SUPPORTED.size());
        for (WeatherFamily family : WeatherFamily.values()) {
            WeatherType type = type(family, 10, true, WeatherType.Climate.ANY, Optional.empty(),
                WeatherType.DEFAULT_DIMENSIONS);
            boolean supported = family == WeatherFamily.PRECIPITATION || family == WeatherFamily.CONVECTIVE
                || family == WeatherFamily.VORTEX || family == WeatherFamily.OBSCURANT;
            assertEquals(supported, TypeSelector.eligible(type, OVERWORLD, anywhere, PLAINS, SUPPORTED),
                "family " + family);
            assertEquals(supported, TypeSelector.eligible(type, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY,
                true), "family " + family + " with a trigger context");
        }
    }

    @Test
    void eligibleWithContextKeepsTheFiveArgumentRules() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        WeatherType disabled = type(WeatherFamily.PRECIPITATION, 10, false, WeatherType.Climate.ANY,
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);
        WeatherType snowOnly = type(WeatherFamily.PRECIPITATION, 10, true, WeatherType.Climate.ANY,
            Optional.of(SNOWY), WeatherType.DEFAULT_DIMENSIONS);
        WeatherType cold = type(WeatherFamily.PRECIPITATION, 10, true, new WeatherType.Climate(-1.0F, 0.3F, 0.0F, 1.0F),
            Optional.empty(), WeatherType.DEFAULT_DIMENSIONS);

        assertTrue(TypeSelector.eligible(rain(), OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, false),
            "plain rain has no trigger and is not severe, so the cooldown does not hold it back");
        assertFalse(TypeSelector.eligible(disabled, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertFalse(TypeSelector.eligible(rain(), THE_END, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertFalse(TypeSelector.eligible(snowOnly, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertFalse(TypeSelector.eligible(cold, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertFalse(TypeSelector.eligible(rain(), OVERWORLD, anywhere, PLAINS, Set.of(), CALM_DAY, true));
    }

    @Test
    void eligibleAsksTheTriggerForTheWind() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        WeatherType windy = triggered(new Trigger(6.0F, 12.0F, Trigger.TimeOfDay.ANY, "", 1), false, 10);

        assertFalse(TypeSelector.eligible(windy, OVERWORLD, anywhere, PLAINS, SUPPORTED,
            new TriggerContext(5.9F, true, ""), true), "the wind is too slow");
        assertTrue(TypeSelector.eligible(windy, OVERWORLD, anywhere, PLAINS, SUPPORTED,
            new TriggerContext(6.0F, true, ""), true), "the window is closed at its lower end");
        assertTrue(TypeSelector.eligible(windy, OVERWORLD, anywhere, PLAINS, SUPPORTED,
            new TriggerContext(12.0F, false, ""), true), "and at its upper end");
        assertFalse(TypeSelector.eligible(windy, OVERWORLD, anywhere, PLAINS, SUPPORTED,
            new TriggerContext(12.1F, true, ""), true), "the wind is too fast");
        assertTrue(TypeSelector.eligible(windy, OVERWORLD, anywhere, PLAINS, SUPPORTED),
            "the five-argument test asks no trigger");
    }

    @Test
    void eligibleAsksTheTriggerForTheTimeOfDay() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        WeatherType nightly = triggered(new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.NIGHT, "", 1), false, 10);
        WeatherType daily = triggered(new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.DAY, "", 1), false, 10);
        TriggerContext night = new TriggerContext(4.0F, false, "");

        assertFalse(TypeSelector.eligible(nightly, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertTrue(TypeSelector.eligible(nightly, OVERWORLD, anywhere, PLAINS, SUPPORTED, night, true));
        assertTrue(TypeSelector.eligible(daily, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertFalse(TypeSelector.eligible(daily, OVERWORLD, anywhere, PLAINS, SUPPORTED, night, true));
    }

    @Test
    void eligibleAsksTheTriggerForTheFront() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        WeatherType squallLine = triggered(new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, Trigger.FRONT_COLD, 1),
            false, 10);
        TriggerContext coldFront = new TriggerContext(4.0F, true, Trigger.FRONT_COLD);
        TriggerContext warmFront = new TriggerContext(4.0F, true, Trigger.FRONT_WARM);

        assertTrue(TypeSelector.eligible(squallLine, OVERWORLD, anywhere, PLAINS, SUPPORTED, coldFront, true));
        assertFalse(TypeSelector.eligible(squallLine, OVERWORLD, anywhere, PLAINS, SUPPORTED, warmFront, true),
            "a squall line needs the cold front, not just any front");
        assertFalse(TypeSelector.eligible(squallLine, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true),
            "and never forms in even air");
        assertTrue(TypeSelector.eligible(triggered(Trigger.ANY, false, 10), OVERWORLD, anywhere, PLAINS, SUPPORTED,
            coldFront, true), "a type that names no front forms along one as anywhere else");
    }

    @Test
    void eligibleHoldsSevereTypesBackDuringTheCooldown() {
        TaggedBiome anywhere = new TaggedBiome(Set.of());
        WeatherType supercell = triggered(Trigger.ANY, true, 10);
        WeatherType shower = triggered(Trigger.ANY, false, 10);

        assertFalse(TypeSelector.eligible(supercell, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, false),
            "a severe type waits for the cooldown");
        assertTrue(TypeSelector.eligible(supercell, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, true));
        assertTrue(TypeSelector.eligible(shower, OVERWORLD, anywhere, PLAINS, SUPPORTED, CALM_DAY, false),
            "a type that is not severe never does");
        assertTrue(TypeSelector.eligible(supercell, OVERWORLD, anywhere, PLAINS, SUPPORTED),
            "the five-argument test allows severe types");
    }

    @Test
    void eligibleWithContextNeedsEveryArgument() {
        TaggedBiome biome = new TaggedBiome(Set.of());
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(rain(), OVERWORLD, biome, PLAINS, SUPPORTED, null, true));
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(null, OVERWORLD, biome, PLAINS, SUPPORTED, CALM_DAY, true));
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(rain(), OVERWORLD, null, PLAINS, SUPPORTED, CALM_DAY, true));
    }

    @Test
    void frontWeightTriplesATypeAlongAColdFront() {
        WeatherType supercell = triggered(new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, "", 3), false, 10);
        WeatherType shower = triggered(Trigger.ANY, false, 10);
        TriggerContext coldFront = new TriggerContext(4.0F, true, Trigger.FRONT_COLD);
        TriggerContext warmFront = new TriggerContext(4.0F, true, Trigger.FRONT_WARM);

        assertEquals(30, supercell.trigger().weight(supercell.weight(), coldFront));
        assertEquals(10, supercell.trigger().weight(supercell.weight(), CALM_DAY));
        assertEquals(10, shower.trigger().weight(shower.weight(), coldFront));

        assertEquals(0.75D, shareOfFirst(supercell, shower, coldFront, 21L), 0.02D,
            "weight 30 against 10: picked three times as often along a cold front");
        assertEquals(0.5D, shareOfFirst(supercell, shower, CALM_DAY, 21L), 0.02D,
            "away from a front the two types are picked equally often");
        assertEquals(0.5D, shareOfFirst(supercell, shower, warmFront, 21L), 0.02D,
            "a warm front multiplies nothing");
    }

    @Test
    void eligibleNeedsEveryArgument() {
        TaggedBiome biome = new TaggedBiome(Set.of());
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(null, OVERWORLD, biome, PLAINS, SUPPORTED));
        assertThrows(NullPointerException.class, () -> TypeSelector.eligible(rain(), null, biome, PLAINS, SUPPORTED));
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(rain(), OVERWORLD, null, PLAINS, SUPPORTED));
        assertThrows(NullPointerException.class,
            () -> TypeSelector.eligible(rain(), OVERWORLD, biome, null, SUPPORTED));
        assertThrows(NullPointerException.class, () -> TypeSelector.eligible(rain(), OVERWORLD, biome, PLAINS, null));
    }

    @Test
    void candidatesCarryTheirValueAndWeight() {
        Map<String, Integer> weights = Map.of("rain", 10, "snow", 4);
        TypeSelector.Candidate<String> rain = candidate("rain", weights.get("rain"));
        assertSame("rain", rain.value());
        assertEquals(10, rain.weight());
        assertEquals(14L, TypeSelector.totalWeight(List.of(rain, candidate("snow", weights.get("snow")))));
    }
}
