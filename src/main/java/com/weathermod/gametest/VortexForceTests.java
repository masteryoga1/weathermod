package com.weathermod.gametest;

import com.weathermod.config.DestructionMode;
import com.weathermod.config.FallDamageMode;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.FunnelSpine;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.family.VortexState;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Vector3f;

/**
 * What a vortex does to the things around it beyond tearing blocks loose: which tiers carry a mob, what a
 * waterspout does over water, and where thrown blocks may not come down.
 */
final class VortexForceTests {
    private static final int GROUNDED_BY_TICKS = 40;
    private static final int WATCH_TICKS = 300;
    private static final int STORM_ENDS_AT_TICKS = 300;
    private static final int LIFTED_FROM_BLOCKS = 10;
    private static final int DRAGGED_FROM_BLOCKS = 4;
    private static final double DROP_HEIGHT_BLOCKS = 6.0;

    private VortexForceTests() {
    }

    /**
     * A cow beside a parked tornado. An EF1 drags a cow in its core over the ground and never takes it off it, an
     * EF2 draws a cow in from outside its core and carries it more than a block into the air within the time the
     * test allows.
     */
    static void lift(GameTestHelper helper, int tier, boolean expectLift) {
        TestBed bed = TestBed.begin(helper);
        // The scene stays as it is; only the push is under test here.
        bed.config(ServerConfig.destructionMode, DestructionMode.ENTITIES_ONLY);
        // The lift is strongest at the wall of the core and too weak to carry anything in the calm middle, and the
        // foot of the funnel wanders up to half a core radius. The cow that is to be carried therefore starts
        // outside the wall, where the pull draws it in through the wall.
        Cow cow = bed.spawn(EntityType.COW, bed.mid(expectLift ? LIFTED_FROM_BLOCKS : DRAGGED_FROM_BLOCKS, 0, 0));
        Vec3 start = cow.position();
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("tornado_ef" + tier, centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        double[] highest = {0.0};
        double[] furthest = {0.0};
        bed.eachTick(() -> {
            highest[0] = Math.max(highest[0], cow.getY() - start.y);
            furthest[0] = Math.max(furthest[0], Math.hypot(cow.getX() - start.x, cow.getZ() - start.z));
            if (!expectLift) {
                bed.expect(highest[0] < 0.1, "An EF" + tier + " lifted the cow " + highest[0] + " blocks");
            }
        });
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.succeedWhen(() -> {
            if (expectLift) {
                ActiveVortex vortex = bed.data.runtime().vortex(storm.id());
                bed.expect(highest[0] > 1.0, "The EF" + tier + " has lifted the cow " + highest[0] + " blocks after "
                    + helper.getTick() + " ticks; it was moved " + furthest[0] + " blocks, stands at "
                    + cow.position() + (cow.isAlive() ? "" : " dead") + ", the storm at " + storm.x() + ", "
                    + storm.z() + " intensity " + storm.intensity() + ", funnel "
                    + (vortex == null ? "none" : "foot " + vortex.spine().groundY() + " grounded "
                        + vortex.spine().grounded() + " acts " + vortex.actsOnWorld() + " base " + vortex.baseX()
                        + ", " + vortex.baseZ()));
            } else {
                bed.expect(helper.getTick() >= WATCH_TICKS, "Watching the cow");
                bed.expect(furthest[0] > 1.0, "The EF" + tier + " moved the cow only " + furthest[0] + " blocks");
            }
        });
    }

    /**
     * A cow that an EF3 has carried six blocks up is dropped by removing the tornado. What the landing costs it
     * follows {@code tornadoFallDamage}: nothing with {@code NONE}, health with {@code FULL}.
     */
    static void fallAfterVortex(GameTestHelper helper, FallDamageMode mode, boolean expectHurt) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.destructionMode, DestructionMode.ENTITIES_ONLY);
        bed.config(ServerConfig.tornadoFallDamage, mode);
        Cow cow = bed.spawn(EntityType.COW, bed.mid(LIFTED_FROM_BLOCKS, 0, 0));
        double floorY = cow.getY();
        float full = cow.getHealth();
        Vec3 centre = bed.centre();
        // The ride has to reach the drop height every time. In the calm middle of an EF2 the lift is weaker than
        // gravity, so a cow that the pull draws through the wall before it has risen six blocks comes down there
        // and stays on the ground. An EF3 at full strength lifts more than gravity takes everywhere inside its
        // lift radius, in the middle too, wherever the wandering foot of the funnel stands.
        StormInstance storm = bed.spawnStorm("tornado_ef3", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        int id = storm.id();
        boolean[] dropped = {false};
        double[] highest = {0.0};
        bed.eachTick(() -> {
            highest[0] = Math.max(highest[0], cow.getY() - floorY);
            if (!dropped[0] && cow.getY() - floorY >= DROP_HEIGHT_BLOCKS) {
                bed.expect(cow.getHealth() == full, "The ride itself hurt the cow: " + cow.getHealth());
                bed.expect(WeatherManager.remove(bed.level, id), "The tornado was already gone");
                dropped[0] = true;
            }
        });
        bed.succeedWhen(() -> {
            ActiveVortex vortex = bed.data.runtime().vortex(id);
            bed.expect(dropped[0], "The cow has not been carried " + DROP_HEIGHT_BLOCKS + " blocks up, it is "
                + (cow.getY() - floorY) + " blocks above the floor after " + helper.getTick() + " ticks and was "
                + highest[0] + " blocks up at most; it stands at " + cow.position()
                + (cow.isAlive() ? "" : " dead") + (bed.level.isPositionEntityTicking(cow.blockPosition())
                    ? "" : " where entities do not tick") + ", the storm at " + storm.x() + ", " + storm.z()
                + ", funnel " + (vortex == null ? "none" : "foot " + vortex.spine().groundY() + " grounded "
                    + vortex.spine().grounded() + " acts " + vortex.actsOnWorld() + " base " + vortex.baseX()
                    + ", " + vortex.baseZ()));
            bed.expect(cow.onGround() || !cow.isAlive(), "The cow is still falling");
            float lost = cow.isAlive() ? full - cow.getHealth() : full;
            if (expectHurt) {
                bed.expect(lost > 0.0F, "The landing cost the cow nothing with " + mode);
            } else {
                bed.expect(lost == 0.0F, "The landing cost the cow " + lost + " health with " + mode);
            }
        });
    }

    /** A waterspout parked over water keeps its lifetime, where one over land ends within seconds. */
    static void waterspoutStaysOverWater(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        flood(bed);
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("waterspout", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.after(WATCH_TICKS, () -> {
            bed.expect(bed.data.find(storm.id()).isPresent(), "The waterspout over water is gone");
            int offWater = storm.familyState(VortexState.class, VortexState::load).offSurfaceTicks;
            bed.expect(offWater == 0, "The waterspout counts " + offWater + " ticks off the water");
            bed.expect(storm.remainingTicks() > 1000,
                "The waterspout over water has only " + storm.remainingTicks() + " ticks left");
            bed.succeed();
        });
    }

    /** A waterspout throws the pig out of a boat that floats inside its funnel. */
    static void waterspoutEmptiesBoats(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        flood(bed);
        Boat boat = bed.spawn(EntityType.BOAT, bed.mid(1, 1, 0));
        Pig pig = bed.spawn(EntityType.PIG, bed.mid(1, 1, 0));
        bed.expect(pig.startRiding(boat, true), "The pig would not board the boat");
        Vec3 centre = bed.centre();
        StormInstance[] spout = {null};
        bed.after(20, () -> {
            bed.expect(pig.getVehicle() == boat, "The pig left the boat before the waterspout came");
            spout[0] = bed.spawnStorm("waterspout", centre.x, centre.z, 1.0F);
            spout[0].steer(0.0F, 0.0F);
        });
        bed.after(20 + GROUNDED_BY_TICKS, () -> expectOnGround(bed, spout[0]));
        // How many ticks the manned boat spent within the funnel's radius, and where it floated then.
        int[] ticksInRadius = {0};
        double[] lastBoatY = {Double.NaN};
        double[] lastFootY = {Double.NaN};
        bed.eachTick(() -> {
            ActiveVortex vortex = spout[0] == null ? null : bed.data.runtime().vortex(spout[0].id());
            if (vortex == null || !vortex.actsOnWorld() || pig.getVehicle() != boat) {
                return;
            }
            FunnelSpine spine = vortex.spine();
            Vector3f axis = new Vector3f();
            spine.offsetAt(spine.groundY(), axis);
            double fromAxis = Math.hypot(boat.getX() - (vortex.x() + axis.x), boat.getZ() - (vortex.z() + axis.z));
            if (fromAxis <= spine.radiusAt(spine.groundY())) {
                ticksInRadius[0]++;
                lastBoatY[0] = boat.getY();
                lastFootY[0] = spine.groundY();
            }
        });
        bed.succeedWhen(() -> {
            bed.expect(spout[0] != null, "Waiting for the waterspout");
            bed.expect(pig.getVehicle() == null, "The pig still sits in the boat after the boat spent "
                + ticksInRadius[0] + " ticks within the funnel's radius, floating at y " + lastBoatY[0]
                + " with the funnel's foot at y " + lastFootY[0]);
        });
    }

    /** Covers the floor of the scene with one layer of water. */
    private static void flood(TestBed bed) {
        int last = bed.size() - 1;
        for (BlockPos pos : BlockPos.betweenClosed(bed.at(0, 0, 0), bed.at(last, 0, last))) {
            bed.set(pos, Blocks.WATER);
        }
    }

    /**
     * With every placement by the weather refused, as a claim would refuse it, the blocks an EF3 throws never come
     * down as blocks: nothing in the scene turns into anything but air, and all of them are gone in the end.
     */
    static void cancelledPlaceEventKeepsFlyingBlocksOut(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.flyingBlockLandChance, 1.0D);
        bed.config(ServerConfig.rippedBlockDropChance, 0.0D);
        for (BlockPos pos : BlockPos.betweenClosed(bed.mid(-5, 0, -5), bed.mid(5, 0, 5))) {
            bed.set(pos, Blocks.OAK_PLANKS);
        }
        Map<BlockPos, BlockState> before = bed.snapshot();
        int[] refused = {0};
        bed.listen(BlockEvent.EntityPlaceEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getEntity())) {
                event.setCanceled(true);
                refused[0]++;
            }
        });
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("tornado_ef3", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        int id = storm.id();
        boolean[] removed = {false};
        bed.eachTick(() -> {
            if (helper.getTick() % 20 == 0) {
                expectOnlyAir(bed, before);
            }
        });
        bed.after(STORM_ENDS_AT_TICKS, () -> {
            bed.expect(WeatherManager.remove(bed.level, id), "The tornado was already gone");
            removed[0] = true;
        });
        bed.succeedWhen(() -> {
            bed.expect(removed[0], "The tornado is still up");
            int flying = bed.flyingBlocks().size();
            bed.expect(flying == 0, flying + " flying block(s) are still in the air");
            bed.expect(refused[0] > 0, "No thrown block tried to land, so nothing was refused");
            expectOnlyAir(bed, before);
        });
    }

    private static void expectOnlyAir(TestBed bed, Map<BlockPos, BlockState> before) {
        for (BlockPos pos : bed.changedSince(before)) {
            BlockState state = bed.level.getBlockState(pos);
            bed.expect(state.isAir(), "A " + state + " was put down at " + pos.toShortString()
                + " although every placement by the weather was refused");
        }
    }

    private static void expectOnGround(TestBed bed, StormInstance storm) {
        ActiveVortex vortex = bed.data.runtime().vortex(storm.id());
        bed.expect(vortex != null && vortex.actsOnWorld(), "The vortex does not stand on the ground");
    }
}
