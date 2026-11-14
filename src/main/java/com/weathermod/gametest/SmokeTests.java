package com.weathermod.gametest;

import com.mojang.logging.LogUtils;
import com.weathermod.WeatherMod;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The broad checks: every weather type the mod ships can be spawned with the command an operator uses and then
 * simulated for ten seconds next to a player without an exception or a warning from the mod, the registry holds
 * exactly those types, and loading the mod and its datapack logged nothing about the mod.
 */
final class SmokeTests {
    /** The paths of the weather types the mod ships, in the order of {@code WeatherTypes}. */
    static final List<String> SHIPPED_TYPES = List.of(
        "placeholder", "overcast", "drizzle", "rain", "downpour", "snow", "sleet", "fog", "thunderstorm",
        "heat_haze", "severe_thunderstorm", "hailstorm", "squall_line", "derecho", "supercell", "tornado_ef0",
        "tornado_ef1", "tornado_ef2", "tornado_ef3", "tornado_ef4", "tornado_ef5", "landspout", "waterspout",
        "gustnado", "dust_devil", "fire_whirl");

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RUN_TICKS = 200;
    private static final int FUNNEL_TICKS = 20;

    private SmokeTests() {
    }

    /**
     * Spawns one storm of the type over a player in the middle of the scene and lets it run. A type with a vortex
     * must have published its funnel after a second, while the storm is still there.
     */
    static void spawnAndTick(GameTestHelper helper, String typePath) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        bed.player(centre);
        int ticksAtStart = bed.data.tickCounter();
        StormInstance storm = bed.spawnStorm(typePath, centre.x, centre.z, 1.0F);
        ResourceLocation wanted = ResourceLocation.fromNamespaceAndPath(WeatherMod.MOD_ID, typePath);
        bed.expect(storm.typeId().equals(wanted), "The command spawned " + storm.typeId() + " instead of " + wanted);
        Optional<WeatherType> type = WeatherManager.type(bed.level, storm);
        bed.expect(type.isPresent(), "The type of the spawned storm does not resolve: " + storm.typeId());
        boolean hasVortex = type.get().vortex().isPresent();
        int id = storm.id();
        bed.after(FUNNEL_TICKS, () -> {
            if (hasVortex && bed.data.find(id).isPresent()) {
                bed.expect(bed.data.runtime().vortex(id) != null, "No funnel was published for " + wanted);
            }
        });
        bed.after(RUN_TICKS, () -> {
            int simulated = bed.data.tickCounter() - ticksAtStart;
            bed.expect(simulated >= RUN_TICKS - 1, "The simulation ran only " + simulated + " ticks");
            bed.succeed();
        });
    }

    /** The weather type registry of the running server holds the shipped types and nothing else. */
    static void registryHoldsTheShippedTypes(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Set<String> found = new TreeSet<>();
        for (ResourceLocation id : WeatherRegistries.registry(bed.level.registryAccess()).keySet()) {
            found.add(id.toString());
        }
        Set<String> shipped = new TreeSet<>();
        for (String path : SHIPPED_TYPES) {
            shipped.add(WeatherMod.MOD_ID + ":" + path);
        }
        bed.expect(found.equals(shipped), "The registry holds " + found + ", the mod ships " + shipped);
        bed.succeed();
    }

    /**
     * The log watch the other tests rely on sees a warning from one of the mod's loggers. The warning this test
     * writes for that is the one line of its kind a clean run leaves in the server log.
     */
    static void logWatchSeesModWarnings(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.expect(LogWatch.isActive(), "The log watch is not attached, so the log cannot be checked");
        int mark = LogWatch.mark();
        LOGGER.warn("Game test probe: this warning is expected and checks that the log watch is listening");
        List<String> lines = LogWatch.since(mark);
        bed.expect(lines.size() == 1 && lines.get(0).contains("Game test probe"),
            "The log watch missed a warning of the mod: " + lines);
        // The probe is a warning of the mod, so this test cannot also ask for a clean log.
        helper.succeed();
    }

    /**
     * Nothing about the mod was logged as a warning or an error between the registration of the tests, which comes
     * before the datapacks load, and the first test. A broken tag, recipe, loot table or weather type shows here.
     */
    static void startupLoggedNothingAboutTheMod(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.expect(LogWatch.isActive(), "The log watch is not attached, so the log cannot be checked");
        List<String> lines = TestBed.startupLog();
        bed.expect(lines.isEmpty(), lines.size() + " warning(s) or error(s) while loading, first: "
            + (lines.isEmpty() ? "" : lines.get(0)));
        bed.succeed();
    }
}
