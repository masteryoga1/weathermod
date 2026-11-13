package com.weathermod.gametest;

import com.weathermod.config.ServerConfig;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.director.StormDirector;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** The operator commands and the storm director in the running server. */
final class CommandTests {
    private CommandTests() {
    }

    /**
     * Every subcommand of {@code /weathermod} runs without an error and does what it reports: storms are spawned,
     * listed, intensified, steered, released, retyped, removed and cleared, the wind is set, the director is
     * paused and resumed and spawns a storm for a player, and the read-only commands answer.
     */
    static void operatorCommandsWork(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        BlockPos middle = bed.mid(0, 0, 0);
        ServerPlayer player = bed.player(centre);

        StormInstance rain = bed.spawnStorm("rain", centre.x, centre.z, 0.8F);
        int id = rain.id();
        bed.expect(Math.abs(rain.peakIntensity() - 0.8F) < 1.0E-4F, "Spawned at peak " + rain.peakIntensity());
        bed.expect(Math.abs(rain.intensity() - 0.8F) < 1.0E-4F,
            "A storm spawned by command is not at its peak at once: " + rain.intensity());
        bed.command("weathermod storm list");
        bed.command("weathermod storm intensify " + id + " 0.5");
        bed.expect(Math.abs(rain.peakIntensity() - 0.5F) < 1.0E-4F, "Intensified to " + rain.peakIntensity());
        bed.command("weathermod storm steer " + id + " 90 2");
        bed.expect(rain.isSteered() && rain.headingDegrees() == 90.0F && rain.speed() == 2.0F,
            "Steered to heading " + rain.headingDegrees() + " at " + rain.speed());
        bed.command("weathermod storm release " + id);
        bed.expect(!rain.isSteered(), "The storm is still steered after release");

        StormInstance tornado = bed.spawnStorm("tornado_ef3", centre.x + 400.0, centre.z, 1.0F);
        bed.command("weathermod storm tier " + tornado.id() + " 1");
        bed.expect(tornado.type().equals(WeatherTypes.tornado(1)), "The tier command left a " + tornado.typeId());
        bed.expect(bed.commandFails("weathermod storm tier " + id + " 1"), "A rain storm took a tornado tier");
        bed.expect(bed.commandFails("weathermod storm remove 999999"), "Removing an unknown storm succeeded");

        bed.command("weathermod wind set 180 5");
        bed.expect(bed.data.wind().directionDegrees() == 180.0F && bed.data.wind().speed() == 5.0F,
            "The wind is " + bed.data.wind());
        bed.command("weathermod climate");
        bed.command(String.format(Locale.ROOT, "weathermod climate %d %d %d",
            middle.getX(), middle.getY(), middle.getZ()));
        bed.command("weathermod director status");
        bed.command("weathermod director resume");
        bed.expect(!bed.data.director().paused, "The director is still paused after resume");
        bed.command("weathermod director pause");
        bed.expect(bed.data.director().paused, "The director is not paused after pause");
        bed.command("weathermod info");
        bed.command("weathermod storm downburst");
        bed.expect(bed.data.runtime().downbursts().size() == 1,
            "Downbursts after the command: " + bed.data.runtime().downbursts().size());
        bed.command("weathermod storm strike");

        int natural = bed.commandAs(player, "weathermod director spawn weathermod:rain");
        StormInstance spawned = bed.data.find(natural).orElse(null);
        bed.expect(spawned != null && spawned.isNatural() && spawned.type().equals(WeatherTypes.RAIN),
            "The director's spawn command made " + spawned);

        bed.command("weathermod storm remove " + id);
        bed.expect(bed.data.find(id).isEmpty(), "The removed storm is still there");
        int cleared = bed.command("weathermod storm clear");
        bed.expect(cleared == 2 && bed.data.storms().isEmpty(),
            "Clear removed " + cleared + " storms and left " + bed.data.storms().size());
        bed.after(5, bed::succeed);
    }

    /**
     * With the director running, a high storm frequency and a short spawn interval, a natural storm forms within
     * reach of a player: it is marked natural, of a type the director may pick, and no further from the player
     * than the director plans.
     */
    static void directorSpawnsNaturalStorms(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.stormFrequency, 10.0D);
        bed.config(ServerConfig.naturalSpawnIntervalSeconds, 10);
        Vec3 centre = bed.centre();
        bed.player(centre);
        // The test bed paused the director and puts its former state back when the test ends.
        bed.data.director().paused = false;
        bed.data.director().ticksUntilAttempt = StormDirector.INTERVAL_TICKS;
        bed.succeedWhen(() -> {
            List<StormInstance> storms = bed.data.storms();
            bed.expect(!storms.isEmpty(), "The director has spawned no storm after " + helper.getTick() + " ticks");
            for (StormInstance storm : storms) {
                bed.expect(storm.isNatural() || storm.isChild(), "A storm that is not natural appeared: " + storm);
                bed.expect(WeatherManager.type(bed.level, storm).isPresent(), "Unknown type: " + storm.typeId());
            }
            StormInstance first = storms.get(0);
            double distance = first.distanceTo(centre.x, centre.z);
            bed.expect(distance <= StormDirector.PLANNING_INTEREST_RADIUS + first.radius(),
                "The natural storm formed " + distance + " blocks from the only player");
        });
    }
}
