package com.weathermod.gametest;

import com.weathermod.config.DestructionMode;
import com.weathermod.config.FallDamageMode;
import com.weathermod.config.LightningFire;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;

/**
 * The mod's game tests: the weather simulation exercised in a running server, where the unit tests cannot reach.
 *
 * <h2>Running the suite</h2>
 *
 * <p>{@code ./gradlew runGameTestServer} starts a headless server on a flat world, runs every test once and exits
 * with the number of failed tests as its exit code, so the task fails when a test does. The server writes its log
 * to {@code run/logs/latest.log}; a failed test is reported there with its name, the reason and the position of
 * its scene. The world under {@code run/world} is kept between runs and may be deleted at any time. In the
 * development client the same tests can be run one by one with {@code /test run <name>} or all together with
 * {@code /test runall}. The continuous integration workflow runs the suite after the build.
 *
 * <p>The tests are registered by {@link GameTestRegistration}, and only when the {@code weathermod} namespace is
 * among the enabled game test namespaces, which the Gradle run configurations arrange. A normal game never loads
 * this class.
 *
 * <h2>How the tests are built</h2>
 *
 * <p>Every test is a {@link TestFunction} made by {@link #tests()}. Each has a batch of its own, so the tests run
 * one after another: storms belong to the dimension, not to a scene, and two tests running side by side would
 * see each other's weather. A test starts by calling {@link TestBed#begin}, which wipes the weather earlier tests
 * left behind and, when the test ends, undoes every config change, removes every event listener, stand-in player
 * and tracked entity and empties the scene. Scenes are built in code on one of two bare templates,
 * {@code weathermod:platform} and {@code weathermod:arena}, which {@code tools/gametest/generate_structures.py}
 * writes. Both are open to the sky.
 *
 * <p>The game test server runs with the fire tick, mob spawning and weather cycle rules off and without random
 * ticks, and the tests leave the game rules alone. What needs the fire tick rule on, a fire whirl lighting blocks
 * and lightning starting fires by chance, is therefore not covered here.
 *
 * <p>A test passes only if the mod logged no warning or error while it ran, see {@link LogWatch}.
 */
public final class WeatherGameTests {
    private static final int SHORT_TICKS = 100;
    private static final int SMOKE_TICKS = 400;
    private static final int MEDIUM_TICKS = 600;
    private static final int LONG_TICKS = 1500;
    private static final int VERY_LONG_TICKS = 3000;

    private WeatherGameTests() {
    }

    /** Builds every game test of the mod. NeoForge calls this once while it registers game tests. */
    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        List<TestFunction> tests = new ArrayList<>();
        add(tests, "startup_logged_nothing_about_the_mod", TestBed.PLATFORM, SHORT_TICKS,
            SmokeTests::startupLoggedNothingAboutTheMod);
        add(tests, "log_watch_sees_mod_warnings", TestBed.PLATFORM, SHORT_TICKS,
            SmokeTests::logWatchSeesModWarnings);
        add(tests, "registry_holds_the_shipped_types", TestBed.PLATFORM, SHORT_TICKS,
            SmokeTests::registryHoldsTheShippedTypes);
        for (String type : SmokeTests.SHIPPED_TYPES) {
            add(tests, "spawn_and_tick_" + type, TestBed.PLATFORM, SMOKE_TICKS,
                helper -> SmokeTests.spawnAndTick(helper, type));
        }

        add(tests, "thunderstorm_sets_vanilla_flags", TestBed.PLATFORM, MEDIUM_TICKS,
            ConvectiveTests::thunderstormSetsVanillaFlags);
        add(tests, "lightning_full_always_lights_fire_and_hurts", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningStrike(helper, DestructionMode.FULL, LightningFire.ALWAYS, true, true));
        add(tests, "lightning_full_off_hurts_without_fire", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningStrike(helper, DestructionMode.FULL, LightningFire.OFF, false, true));
        add(tests, "lightning_full_normal_forced_strike_lights_no_fire", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningStrike(helper, DestructionMode.FULL, LightningFire.NORMAL, false, true));
        add(tests, "lightning_entities_only_hurts_without_fire", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningStrike(helper, DestructionMode.ENTITIES_ONLY, LightningFire.ALWAYS,
                false, true));
        add(tests, "lightning_visual_only_is_only_shown", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningStrike(helper, DestructionMode.VISUAL_ONLY, LightningFire.ALWAYS,
                false, false));
        add(tests, "hail_respects_roof_and_health_floor", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hail(helper, false));
        add(tests, "hail_kills_mobs_when_allowed", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hail(helper, true));
        add(tests, "supercell_puts_down_tornado", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.supercellPutsDownTornado(helper, DestructionMode.FULL));
        add(tests, "supercell_puts_down_tornado_when_visual_only", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.supercellPutsDownTornado(helper, DestructionMode.VISUAL_ONLY));
        add(tests, "supercell_without_tornado_frequency_gets_no_child", TestBed.PLATFORM, MEDIUM_TICKS,
            ConvectiveTests::supercellWithoutTornadoFrequencyGetsNoChild);
        add(tests, "lightning_full_powers_rod_and_cleans_copper", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningOnRodAndCopper(helper, DestructionMode.FULL, true, true));
        add(tests, "lightning_entities_only_leaves_rod_and_copper", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningOnRodAndCopper(helper, DestructionMode.ENTITIES_ONLY, false, true));
        add(tests, "lightning_visual_only_leaves_rod_copper_and_creeper", TestBed.PLATFORM, SHORT_TICKS,
            helper -> ConvectiveTests.lightningOnRodAndCopper(helper, DestructionMode.VISUAL_ONLY, false, false));
        add(tests, "downburst_pushes_outward", TestBed.ARENA, MEDIUM_TICKS,
            ConvectiveTests::downburstPushesOutward);
        add(tests, "wind_pushes_only_in_the_open", TestBed.PLATFORM, MEDIUM_TICKS,
            ConvectiveTests::windPushesOnlyInTheOpen);
        add(tests, "hail_breaks_plants", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hailOnPlants(helper, true));
        add(tests, "hail_spares_plants_without_block_damage", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hailOnPlants(helper, false));

        add(tests, "hail_spares_glass_by_default", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hailOnGlass(helper, false));
        add(tests, "hail_breaks_glass_when_allowed", TestBed.PLATFORM, LONG_TICKS,
            helper -> ConvectiveTests.hailOnGlass(helper, true));

        add(tests, "thunderstorm_strikes_inside_itself", TestBed.ARENA, LONG_TICKS,
            LightningTests::thunderstormStrikesInsideItself);
        add(tests, "vanilla_storm_lightning_is_dropped_under_mod_thunder", TestBed.PLATFORM, MEDIUM_TICKS,
            LightningTests::vanillaStormLightningIsDropped);
        add(tests, "deflector_keeps_lightning_off_rod_and_copper", TestBed.PLATFORM, SHORT_TICKS,
            LightningTests::deflectorKeepsLightningOffRodAndCopper);

        add(tests, "ef3_rips_what_its_tier_allows", TestBed.ARENA, LONG_TICKS,
            TornadoTests::ef3RipsWhatItsTierAllows);
        add(tests, "entities_only_changes_no_block", TestBed.ARENA, MEDIUM_TICKS,
            helper -> TornadoTests.modeChangesNoBlock(helper, DestructionMode.ENTITIES_ONLY));
        add(tests, "visual_only_changes_nothing", TestBed.ARENA, MEDIUM_TICKS,
            helper -> TornadoTests.modeChangesNoBlock(helper, DestructionMode.VISUAL_ONLY));
        add(tests, "flying_blocks_stay_within_the_cap", TestBed.ARENA, LONG_TICKS,
            TornadoTests::flyingBlocksStayWithinTheCap);
        add(tests, "vortex_moves_mobs_but_not_the_immune", TestBed.ARENA, MEDIUM_TICKS,
            TornadoTests::vortexMovesMobsButNotTheImmune);
        add(tests, "push_mobs_off_leaves_mobs_alone", TestBed.ARENA, MEDIUM_TICKS,
            TornadoTests::pushMobsOffLeavesMobsAlone);
        add(tests, "fire_whirl_burns_entities", TestBed.PLATFORM, MEDIUM_TICKS,
            helper -> TornadoTests.fireWhirlBurnsEntities(helper, false));
        add(tests, "fire_whirl_burns_entities_on_soul_sand", TestBed.PLATFORM, MEDIUM_TICKS,
            helper -> TornadoTests.fireWhirlBurnsEntities(helper, true));
        add(tests, "tornado_grows_to_its_target_tier", TestBed.ARENA, VERY_LONG_TICKS,
            TornadoTests::tornadoGrowsToItsTargetTier);
        add(tests, "waterspout_ends_over_land", TestBed.PLATFORM, MEDIUM_TICKS + SMOKE_TICKS,
            TornadoTests::waterspoutEndsOverLand);
        add(tests, "ef5_digs_no_deeper_than_six", TestBed.ARENA, LONG_TICKS, TornadoTests::ef5DigsNoDeeperThanSix);

        add(tests, "ef1_never_lifts_a_mob", TestBed.ARENA, MEDIUM_TICKS,
            helper -> VortexForceTests.lift(helper, 1, false));
        add(tests, "ef2_lifts_a_mob_within_five_seconds", TestBed.ARENA, SHORT_TICKS,
            helper -> VortexForceTests.lift(helper, 2, true));
        add(tests, "fall_after_a_vortex_is_harmless_with_none", TestBed.ARENA, MEDIUM_TICKS,
            helper -> VortexForceTests.fallAfterVortex(helper, FallDamageMode.NONE, false));
        add(tests, "fall_after_a_vortex_hurts_with_full", TestBed.ARENA, MEDIUM_TICKS,
            helper -> VortexForceTests.fallAfterVortex(helper, FallDamageMode.FULL, true));
        add(tests, "waterspout_stays_over_water", TestBed.ARENA, MEDIUM_TICKS,
            VortexForceTests::waterspoutStaysOverWater);
        add(tests, "waterspout_empties_boats", TestBed.ARENA, MEDIUM_TICKS,
            VortexForceTests::waterspoutEmptiesBoats);
        add(tests, "cancelled_place_event_keeps_flying_blocks_out", TestBed.ARENA, LONG_TICKS,
            VortexForceTests::cancelledPlaceEventKeepsFlyingBlocksOut);

        add(tests, "deflector_keeps_tornado_out", TestBed.ARENA, LONG_TICKS,
            BlockTests::deflectorKeepsTornadoOut);
        add(tests, "unsteered_tornado_slides_around_deflector", TestBed.PLATFORM, VERY_LONG_TICKS,
            BlockTests::unsteeredTornadoSlidesAroundDeflector);
        add(tests, "siren_follows_tornado", TestBed.PLATFORM, LONG_TICKS, BlockTests::sirenFollowsTornado);
        add(tests, "siren_ignores_small_vortex_and_obeys_redstone", TestBed.PLATFORM, MEDIUM_TICKS,
            BlockTests::sirenIgnoresSmallVortexAndObeysRedstone);
        add(tests, "temporary_fire_burns_out", TestBed.PLATFORM, MEDIUM_TICKS, BlockTests::temporaryFireBurnsOut);

        add(tests, "storm_state_survives_save_and_load", TestBed.PLATFORM, MEDIUM_TICKS,
            PersistenceTests::stormStateSurvivesSaveAndLoad);
        add(tests, "datapack_content_is_loaded", TestBed.PLATFORM, SHORT_TICKS,
            PersistenceTests::datapackContentIsLoaded);

        add(tests, "operator_commands_work", TestBed.PLATFORM, SHORT_TICKS, CommandTests::operatorCommandsWork);
        add(tests, "director_spawns_natural_storms", TestBed.PLATFORM, VERY_LONG_TICKS,
            CommandTests::directorSpawnsNaturalStorms);
        return tests;
    }

    /** A required test in a batch of its own, on a template open to the sky, tried once. */
    private static void add(List<TestFunction> tests, String name, String template, int maxTicks,
                            Consumer<GameTestHelper> body) {
        tests.add(new TestFunction(name, name, template, Rotation.NONE, maxTicks, 0L, true, false, 1, 1, true, body));
    }
}
