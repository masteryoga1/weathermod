package com.weathermod.gametest;

import com.weathermod.block.TornadoSirenBlock;
import com.weathermod.block.WeatherBlocks;
import com.weathermod.config.DestructionMode;
import com.weathermod.config.LightningFire;
import com.weathermod.config.ServerConfig;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.impact.LightningStrikes;
import com.weathermod.server.impact.ProtectedActions;
import com.weathermod.server.impact.TemporaryFires;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The mod's two blocks and its temporary fires in the running server. */
final class BlockTests {
    private static final int SMALL_DEFLECTOR_RADIUS = 8;
    private static final int SMALL_SIREN_RANGE = 16;
    /** How far a parked EF3 at full strength is kept from a deflector's circle: one and a half core radii. */
    private static final double EF3_MARGIN_BLOCKS = 18.0;
    private static final int SIREN_CHECK_TICKS = 20;
    private static final int SIREN_HOLD_TICKS = 200;

    private BlockTests() {
    }

    /**
     * A tornado spawned next to a weather deflector and steered straight at it is pushed out of the deflector's
     * circle on its first tick and never comes closer than the circle plus its own margin. Wool inside the circle
     * stays while wool outside it, in the tornado's reach, is ripped. A forced lightning strike lights a fire
     * outside the circle and none inside. The deflector's position survives a save and load of the dimension
     * data and leaves the index when the block is broken.
     */
    static void deflectorKeepsTornadoOut(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.deflectorRadius, SMALL_DEFLECTOR_RADIUS);
        bed.config(ServerConfig.flyingBlockLandChance, 0.0D);
        bed.config(ServerConfig.lightningFire, LightningFire.ALWAYS);
        int row = bed.size() / 2;
        BlockPos deflector = bed.at(8, 0, row);
        bed.deflector(deflector);
        bed.expect(bed.data.deflectors().contains(deflector), "Placing a deflector did not add it to the index");
        Vec3 hub = Vec3.atBottomCenterOf(deflector);

        List<BlockPos> inside = new ArrayList<>();
        for (int x = 10; x <= 15; x++) {
            inside.add(bed.at(x, 0, row));
        }
        List<BlockPos> outside = new ArrayList<>();
        for (int x = 19; x <= 22; x++) {
            outside.add(bed.at(x, 0, row));
        }
        inside.forEach(pos -> bed.set(pos, Blocks.WHITE_WOOL));
        outside.forEach(pos -> bed.set(pos, Blocks.WHITE_WOOL));

        bed.expect(ProtectedActions.deflected(bed.level, hub.x + 7.5, hub.z),
            "A point 7.5 blocks from the deflector is not protected");
        bed.expect(!ProtectedActions.deflected(bed.level, hub.x + 8.5, hub.z),
            "A point 8.5 blocks from the deflector is protected");
        BlockPos struckInside = bed.at(8, 0, row - 4);
        BlockPos struckOutside = bed.at(8, 0, row + 11);
        bed.expect(LightningStrikes.strikeAt(bed.level, struckInside.getX() + 0.5, struckInside.getZ() + 0.5, 0.0F),
            "The strike inside the circle did not happen");
        bed.expect(LightningStrikes.strikeAt(bed.level, struckOutside.getX() + 0.5, struckOutside.getZ() + 0.5, 0.0F),
            "The strike outside the circle did not happen");
        bed.expect(!(bed.level.getBlockState(struckInside).getBlock() instanceof BaseFireBlock),
            "Lightning lit a fire inside the deflector's circle");
        bed.expect(bed.level.getBlockState(struckOutside).getBlock() instanceof BaseFireBlock,
            "Lightning lit no fire outside the deflector's circle");
        bed.set(struckOutside, Blocks.AIR);

        HolderLookup.Provider registries = bed.level.registryAccess();
        DimensionWeatherData loaded =
            DimensionWeatherData.load(bed.data.save(new CompoundTag(), registries), registries);
        bed.expect(loaded.deflectors().size() == 1 && loaded.deflectors().contains(deflector),
            "The deflector did not survive a save and load: " + loaded.deflectors().positions());

        StormInstance storm = bed.spawnStorm("tornado_ef3", hub.x + 3.0, hub.z, 1.0F);
        storm.steer(270.0F, 2.0F);
        double keepOut = SMALL_DEFLECTOR_RADIUS + EF3_MARGIN_BLOCKS;
        bed.eachTick(() -> {
            if (helper.getTick() < 3 || bed.data.find(storm.id()).isEmpty()) {
                return;
            }
            double distance = storm.distanceTo(hub.x, hub.z);
            bed.expect(distance >= keepOut - 0.01,
                "The tornado is " + distance + " blocks from the deflector, inside " + keepOut);
            for (BlockPos pos : inside) {
                bed.expect(bed.level.getBlockState(pos).is(Blocks.WHITE_WOOL),
                    "Wool inside the deflector's circle is gone at " + pos.toShortString());
            }
        });
        bed.succeedWhen(() -> {
            bed.expect(helper.getTick() >= 300, "Watching the deflector's circle");
            for (BlockPos pos : outside) {
                bed.expect(bed.level.getBlockState(pos).isAir(),
                    "Wool outside the deflector's circle still stands at " + pos.toShortString());
            }
            WeatherManager.clear(bed.level);
            bed.set(deflector, Blocks.AIR);
            bed.expect(!bed.data.deflectors().contains(deflector), "Breaking the deflector left it in the index");
        });
    }

    /**
     * An unsteered tornado that the wind carries straight at a weather deflector never enters the deflector's
     * circle and still gets past it: it slides around and ends up downwind of the deflector.
     */
    static void unsteeredTornadoSlidesAroundDeflector(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.deflectorRadius, SMALL_DEFLECTOR_RADIUS);
        // The scene stays as it is; only the storm's path is under test here.
        bed.config(ServerConfig.destructionMode, DestructionMode.ENTITIES_ONLY);
        BlockPos deflector = bed.mid(0, 0, 0);
        bed.deflector(deflector);
        Vec3 hub = Vec3.atBottomCenterOf(deflector);
        double keepOut = SMALL_DEFLECTOR_RADIUS + EF3_MARGIN_BLOCKS;
        // The wind blows east at nine blocks a second, which carries an EF3 at 2.7.
        bed.data.wind().set(90.0F, 9.0F);
        StormInstance storm = bed.spawnStorm("tornado_ef3", hub.x - keepOut - 20.0, hub.z, 1.0F);
        int id = storm.id();
        double[] closest = {Double.MAX_VALUE};
        bed.eachTick(() -> {
            if (bed.data.find(id).isEmpty()) {
                return;
            }
            double distance = storm.distanceTo(hub.x, hub.z);
            closest[0] = Math.min(closest[0], distance);
            bed.expect(distance >= keepOut - 0.01,
                "The tornado is " + distance + " blocks from the deflector, inside " + keepOut);
        });
        bed.succeedWhen(() -> {
            bed.expect(bed.data.find(id).isPresent(), "The tornado ended before it got past the deflector");
            bed.expect(closest[0] < keepOut + 3.0, "The tornado never came near the deflector: " + closest[0]);
            bed.expect(storm.x() > hub.x + keepOut + 5.0, "The tornado is not past the deflector after "
                + helper.getTick() + " ticks: it is " + (storm.x() - hub.x) + " blocks east and "
                + (storm.z() - hub.z) + " blocks south of it, heading " + storm.headingDegrees());
        });
    }

    /**
     * A tornado siren is silent while a tornado is out of range, starts within a second of the tornado's edge
     * coming within the configured range, keeps wailing through its hold time after the tornado is gone and then
     * stops.
     */
    static void sirenFollowsTornado(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.sirenRange, SMALL_SIREN_RANGE);
        BlockPos siren = bed.mid(0, 0, 0);
        bed.set(siren, WeatherBlocks.TORNADO_SIREN.get());
        Vec3 at = Vec3.atBottomCenterOf(siren);
        StormInstance storm = bed.spawnStorm("tornado_ef1", at.x + SMALL_SIREN_RANGE + 76.0, at.z, 1.0F);
        double reach = SMALL_SIREN_RANGE + storm.radius();
        bed.expect(storm.distanceTo(at.x, at.z) > reach + 20.0, "The tornado starts in range of the siren");
        // West at eight blocks a second: in range after about five seconds.
        storm.steer(270.0F, 8.0F);
        int id = storm.id();
        long[] inRangeAt = {-1};
        long[] onAt = {-1};
        bed.eachTick(() -> {
            long tick = helper.getTick();
            boolean active = bed.level.getBlockState(siren).getValue(TornadoSirenBlock.ACTIVE);
            if (onAt[0] < 0) {
                double distance = storm.distanceTo(at.x, at.z);
                if (distance <= reach && inRangeAt[0] < 0) {
                    inRangeAt[0] = tick;
                }
                if (inRangeAt[0] < 0) {
                    bed.expect(!active,
                        "The siren wails with the tornado " + distance + " blocks away, range " + reach);
                } else if (active) {
                    onAt[0] = tick;
                    bed.expect(WeatherManager.remove(bed.level, id), "The tornado was already gone");
                } else {
                    bed.expect(tick - inRangeAt[0] <= SIREN_CHECK_TICKS + 2,
                        "The siren is still silent " + (tick - inRangeAt[0]) + " ticks after the tornado came in");
                }
                return;
            }
            long since = tick - onAt[0];
            if (since <= SIREN_HOLD_TICKS - SIREN_CHECK_TICKS - 2) {
                bed.expect(active,
                    "The siren stopped " + since + " ticks after the tornado left, before its hold ended");
            } else if (since >= SIREN_HOLD_TICKS + SIREN_CHECK_TICKS + 2) {
                bed.expect(!active, "The siren still wails " + since + " ticks after the tornado left");
                bed.succeed();
            }
        });
    }

    /**
     * A landspout on top of a siren does not start it, since only rated tornadoes do. A redstone signal starts it
     * at once and its end stops it at once.
     */
    static void sirenIgnoresSmallVortexAndObeysRedstone(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        BlockPos siren = bed.mid(0, 0, 0);
        BlockPos power = bed.mid(1, 0, 0);
        bed.set(siren, WeatherBlocks.TORNADO_SIREN.get());
        Vec3 at = Vec3.atBottomCenterOf(siren);
        bed.spawnStorm("landspout", at.x, at.z, 1.0F).steer(0.0F, 0.0F);
        bed.eachTick(() -> {
            if (helper.getTick() <= 60) {
                bed.expect(!bed.level.getBlockState(siren).getValue(TornadoSirenBlock.ACTIVE),
                    "A landspout started the siren");
            }
        });
        bed.after(61, () -> bed.set(power, Blocks.REDSTONE_BLOCK));
        bed.after(64, () -> {
            BlockState state = bed.level.getBlockState(siren);
            bed.expect(state.getValue(TornadoSirenBlock.POWERED) && state.getValue(TornadoSirenBlock.ACTIVE),
                "A redstone block next to the siren did not start it: " + state);
            bed.set(power, Blocks.AIR);
        });
        bed.after(67, () -> {
            BlockState state = bed.level.getBlockState(siren);
            bed.expect(!state.getValue(TornadoSirenBlock.POWERED) && !state.getValue(TornadoSirenBlock.ACTIVE),
                "The siren kept going after the redstone block was removed: " + state);
            bed.succeed();
        });
    }

    /**
     * A fire on netherrack never goes out by itself. Listed as a temporary fire it burns for its lifetime and is
     * then removed, and the list is empty again.
     */
    static void temporaryFireBurnsOut(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        BlockPos base = bed.mid(0, 0, 0);
        BlockPos above = base.above();
        bed.set(base, Blocks.NETHERRACK);
        bed.expect(bed.data.temporaryFires().add(above), "The temporary fire list refused the position");
        bed.expect(ProtectedActions.place(bed.level, above, BaseFireBlock.getState(bed.level, above)),
            "The fire could not be placed");
        long start = helper.getTick();
        bed.eachTick(() -> {
            long age = helper.getTick() - start;
            boolean burning = bed.level.getBlockState(above).getBlock() instanceof BaseFireBlock;
            if (age < TemporaryFires.LIFE_TICKS - 5) {
                bed.expect(burning, "The temporary fire went out after " + age + " ticks");
            } else if (age > TemporaryFires.LIFE_TICKS + 5) {
                bed.expect(!burning, "The temporary fire still burns after " + age + " ticks");
                bed.expect(bed.data.temporaryFires().isEmpty(), "The temporary fire is still listed");
                bed.expect(bed.level.getBlockState(base).is(Blocks.NETHERRACK), "The netherrack is gone");
                bed.succeed();
            }
        });
    }
}
