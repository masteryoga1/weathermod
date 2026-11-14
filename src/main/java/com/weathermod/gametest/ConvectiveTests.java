package com.weathermod.gametest;

import com.weathermod.config.DestructionMode;
import com.weathermod.config.LightningFire;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.HailCore;
import com.weathermod.core.WeatherDamageTypes;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.family.ConvectiveState;
import com.weathermod.server.family.VortexMath;
import com.weathermod.server.family.VortexState;
import com.weathermod.server.impact.HailSystem;
import com.weathermod.server.impact.LightningStrikes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Thunderstorms and their kin in the running server: the vanilla weather flags, a forced lightning strike under
 * every combination of destruction mode and fire setting that matters, hail on a sheltered and an exposed mob,
 * and a supercell that puts down a tornado.
 */
final class ConvectiveTests {
    /** Ticks vanilla takes to bring its rain and thunder levels up far enough for {@code Level.isThundering}. */
    private static final int THUNDER_RAMP_TICKS = 130;
    private static final int HAIL_HOLD_TICKS = 100;
    private static final int FORCED_BURST_TICKS = 100_000;
    /** An orphaned tornado enters its fade-out at once; no fade-out of a young EF0 is longer than this. */
    private static final int ORPHAN_FADE_TICKS = 400;
    private static final int DOWNBURST_OUTER_BLOCKS = 12;
    private static final int DOWNBURST_INNER_BLOCKS = 3;

    private ConvectiveTests() {
    }

    /**
     * A rain storm over a player turns the vanilla rain flag on and leaves the thunder flag off, a thunderstorm
     * turns both on and keeps them on until vanilla itself reports thunder, and both go off with the storm.
     */
    static void thunderstormSetsVanillaFlags(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        bed.player(centre);
        LevelData flags = bed.level.getLevelData();
        bed.after(3, () -> {
            bed.expect(!flags.isRaining() && !flags.isThundering(), "Vanilla weather is not clear without a storm");
            bed.spawnStorm("rain", centre.x, centre.z, 1.0F);
        });
        bed.after(6, () -> {
            bed.expect(flags.isRaining(), "A rain storm over a player did not turn the vanilla rain flag on");
            bed.expect(!flags.isThundering(), "A rain storm turned the vanilla thunder flag on");
            WeatherManager.clear(bed.level);
        });
        bed.after(9, () -> {
            bed.expect(!flags.isRaining(), "The vanilla rain flag stayed on after the rain storm was removed");
            bed.spawnStorm("thunderstorm", centre.x, centre.z, 1.0F);
        });
        bed.after(12, () -> {
            bed.expect(flags.isRaining(), "A thunderstorm did not turn the vanilla rain flag on");
            bed.expect(flags.isThundering(), "A thunderstorm did not turn the vanilla thunder flag on");
        });
        bed.after(12 + THUNDER_RAMP_TICKS, () -> {
            bed.expect(flags.isRaining() && flags.isThundering(), "The vanilla flags did not stay on");
            bed.expect(bed.level.isThundering(), "Vanilla does not report thunder under a thunderstorm");
            WeatherManager.clear(bed.level);
        });
        bed.after(15 + THUNDER_RAMP_TICKS, () -> {
            bed.expect(!flags.isRaining() && !flags.isThundering(),
                "The vanilla flags stayed on after the thunderstorm was removed");
            bed.succeed();
        });
    }

    /**
     * Strikes the middle of the scene with {@code /weathermod storm strike} next to a cow. There is always exactly
     * one bolt standing on the target; whether it lights a fire and whether it hurts the cow by the bolt's five
     * points follows the destruction mode and the lightning fire setting. A fire is placed as the weather actor,
     * with the place event fired.
     */
    static void lightningStrike(GameTestHelper helper, DestructionMode mode, LightningFire fire, boolean expectFire,
                                boolean expectHurt) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.destructionMode, mode);
        bed.config(ServerConfig.lightningFire, fire);
        BlockPos target = bed.mid(0, 0, 0);
        Cow cow = bed.spawn(EntityType.COW, bed.mid(2, 0, 0));
        List<BlockPos> placed = new ArrayList<>();
        bed.listen(BlockEvent.EntityPlaceEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getEntity())) {
                placed.add(event.getPos().immutable());
            }
        });
        bed.after(5, () -> {
            float before = cow.getHealth();
            bed.command(String.format(Locale.ROOT, "weathermod storm strike %d %d %d",
                target.getX(), target.getY(), target.getZ()));
            List<LightningBolt> bolts =
                bed.level.getEntitiesOfClass(LightningBolt.class, new AABB(target).inflate(1.0));
            bed.expect(bolts.size() == 1, "Expected one bolt on the target, found " + bolts.size());
            LightningBolt bolt = bolts.get(0);
            double offX = bolt.getX() - (target.getX() + 0.5);
            double offZ = bolt.getZ() - (target.getZ() + 0.5);
            bed.expect(Math.abs(offX) < 0.01 && Math.abs(offZ) < 0.01 && Math.abs(bolt.getY() - target.getY()) < 0.1,
                "The bolt stands at " + bolt.position() + ", not on " + target);
            boolean lit = bed.level.getBlockState(target).getBlock() instanceof BaseFireBlock;
            bed.expect(lit == expectFire, "Fire on the target: " + lit + ", expected " + expectFire
                + " with " + mode + " and " + fire);
            bed.expect(placed.contains(target) == expectFire,
                "Place events for the weather actor at the target: " + placed + ", fire expected: " + expectFire);
            float lost = before - cow.getHealth();
            float expected = expectHurt ? LightningStrikes.LIGHTNING_DAMAGE : 0.0F;
            bed.expect(Math.abs(lost - expected) < 0.001F, "The cow lost " + lost + " health, expected " + expected
                + " with " + mode);
            bed.succeed();
        });
    }

    /**
     * Parks a hailstorm so that its hail core lies on the scene and forces a hail burst. A pig under the open sky
     * is hurt by hail, a pig under a roof never is, whether the roof is bedrock or glass, which lets the sky light
     * through. Without {@code hailKillsMobs} the exposed pig is taken down to the health floor of one heart and
     * stays there; with it the pig dies.
     */
    static void hail(GameTestHelper helper, boolean killsMobs) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        bed.config(ServerConfig.hailKillsMobs, killsMobs);
        Vec3 centre = bed.centre();
        BlockPos open = bed.mid(-3, 0, 0);
        BlockPos roofed = bed.mid(3, 0, 0);
        BlockPos glazed = bed.mid(0, 0, 3);
        pen(bed, open, false);
        pen(bed, roofed, true);
        pen(bed, glazed, false);
        bed.set(glazed.above(), Blocks.GLASS);
        Pig exposed = bed.spawn(EntityType.PIG, open);
        Pig sheltered = bed.spawn(EntityType.PIG, roofed);
        Pig underGlass = bed.spawn(EntityType.PIG, glazed);
        float full = sheltered.getMaxHealth();
        bed.player(centre);
        boolean[] hailing = {false};
        int[] stormId = {-1};
        int[] heldSince = {-1};

        // The light under the new roof settles within a few ticks; the storm comes after that.
        bed.after(20, () -> {
            StormInstance storm = bed.spawnStorm("hailstorm", centre.x, centre.z, 1.0F);
            storm.steer(0.0F, 0.0F);
            storm.setPosition(centre.x - HailCore.centreX(0.0, 0.0F, storm.radius()),
                centre.z - HailCore.centreZ(0.0, 0.0F, storm.radius()));
            storm.familyState(ConvectiveState.class, ConvectiveState::load).hailBurstTicks = FORCED_BURST_TICKS;
            stormId[0] = storm.id();
        });
        bed.after(23, () -> {
            StormInstance running = bed.data.find(stormId[0]).orElseThrow();
            float strength = HailCore.strengthAt(running.x(), running.z(), running.headingDegrees(),
                running.radius(), running.hail(), exposed.getX(), exposed.getZ());
            bed.expect(running.hail() > 0.9F && strength > 0.9F,
                "No full hail over the scene: storm hail " + running.hail() + ", at the pig " + strength);
            hailing[0] = true;
        });
        bed.eachTick(() -> {
            bed.expect(sheltered.isAlive() && sheltered.getHealth() == full,
                "The pig under the roof was hurt, health " + sheltered.getHealth());
            bed.expect(underGlass.isAlive() && underGlass.getHealth() == full,
                "The pig under the glass roof was hurt, health " + underGlass.getHealth());
            if (!killsMobs) {
                bed.expect(exposed.isAlive() && exposed.getHealth() >= HailSystem.HAIL_MOB_HEALTH_FLOOR,
                    "Hail took the exposed pig below the health floor: " + exposed.getHealth());
            }
        });
        bed.succeedWhen(() -> {
            bed.expect(hailing[0], "The hail has not started");
            if (killsMobs) {
                bed.expect(!exposed.isAlive(), "The exposed pig still lives with health " + exposed.getHealth());
                return;
            }
            bed.expect(exposed.getHealth() == HailSystem.HAIL_MOB_HEALTH_FLOOR,
                "The exposed pig has health " + exposed.getHealth() + ", not the floor");
            if (heldSince[0] < 0) {
                DamageSource last = exposed.getLastDamageSource();
                bed.expect(last != null && last.is(WeatherDamageTypes.HAIL),
                    "The exposed pig was last hurt by " + (last == null ? "nothing" : last.type().msgId()));
                heldSince[0] = (int) helper.getTick();
            }
            bed.expect(helper.getTick() - heldSince[0] >= HAIL_HOLD_TICKS, "Holding at the health floor");
        });
    }

    /**
     * A supercell old and strong enough, with a player in sight of its flank, rolls for a tornado at once. With
     * the tornado frequency turned up the roll succeeds: one EF0 child appears on the flank with a touchdown delay
     * of twenty to thirty seconds, hangs aloft first and stands on the ground once the delay has passed.
     */
    static void supercellPutsDownTornado(GameTestHelper helper, DestructionMode mode) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.destructionMode, mode);
        bed.config(ServerConfig.tornadoFrequency, 10.0D);
        bed.config(ServerConfig.maxTornadoTier, 0);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        Vec3 centre = bed.centre();
        bed.player(centre);
        StormInstance parent = bed.spawnStorm("supercell", centre.x, centre.z, 1.0F);
        parent.steer(0.0F, 0.0F);
        double[] flank = new double[2];
        VortexMath.tornadoAnchor(0.0, 0.0, 0.0F, parent.radius(), flank);
        parent.setPosition(centre.x - flank[0], centre.z - flank[1]);
        int[] childId = {-1};
        int[] delay = {0};
        bed.after(5, () -> {
            List<StormInstance> children = new ArrayList<>();
            for (StormInstance storm : bed.data.storms()) {
                if (storm.parentId() == parent.id() && storm.id() != parent.id()) {
                    children.add(storm);
                }
            }
            bed.expect(children.size() == 1, "Expected one child of the supercell, found " + children);
            StormInstance child = children.get(0);
            bed.expect(child.type().equals(WeatherTypes.tornado(0)), "The child is a " + child.typeId());
            VortexState state = child.familyState(VortexState.class, VortexState::load);
            bed.expect(state.touchdownDelayTicks >= VortexMath.TOUCHDOWN_DELAY_MIN_TICKS
                && state.touchdownDelayTicks <= VortexMath.TOUCHDOWN_DELAY_MAX_TICKS,
                "Touchdown delay " + state.touchdownDelayTicks);
            bed.expect(state.targetTier == 0, "Target tier " + state.targetTier + " with maxTornadoTier 0");
            bed.expect(child.distanceTo(centre.x, centre.z) < 8.0,
                "The child is " + child.distanceTo(centre.x, centre.z) + " blocks from the supercell's flank");
            ActiveVortex vortex = bed.data.runtime().vortex(child.id());
            bed.expect(vortex != null && !vortex.isGrounded(), "The new funnel is missing or already on the ground");
            ConvectiveState convective = parent.familyState(ConvectiveState.class, ConvectiveState::load);
            bed.expect(convective.tornadoesSpawned == 1, "The parent counts " + convective.tornadoesSpawned);
            childId[0] = child.id();
            delay[0] = state.touchdownDelayTicks;
        });
        bed.eachTick(() -> {
            if (childId[0] < 0) {
                return;
            }
            StormInstance child = bed.data.find(childId[0]).orElse(null);
            ActiveVortex vortex = bed.data.runtime().vortex(childId[0]);
            if (child != null && vortex != null && vortex.isGrounded()) {
                bed.expect(child.ageTicks() >= delay[0],
                    "The funnel touched down at age " + child.ageTicks() + ", before its delay of " + delay[0]);
            }
        });
        long[] orphanedAt = {-1};
        bed.succeedWhen(() -> {
            bed.expect(childId[0] >= 0, "No child yet");
            if (orphanedAt[0] < 0) {
                bed.expect(bed.data.find(childId[0]).isPresent(), "The child tornado is gone before touching down");
                ActiveVortex vortex = bed.data.runtime().vortex(childId[0]);
                bed.expect(vortex != null && vortex.actsOnWorld(), "The child tornado has not touched down");
                bed.expect(WeatherManager.remove(bed.level, parent.id()), "The supercell was already gone");
                orphanedAt[0] = helper.getTick();
            }
            long orphaned = helper.getTick() - orphanedAt[0];
            bed.expect(bed.data.find(childId[0]).isEmpty(),
                "The tornado is still there " + orphaned + " ticks after its supercell was removed");
            bed.expect(orphaned <= ORPHAN_FADE_TICKS,
                "The orphaned tornado took " + orphaned + " ticks to end, longer than its fade-out");
        });
    }

    /** With the tornado frequency at zero a supercell that is due for its roll gets no tornado. */
    static void supercellWithoutTornadoFrequencyGetsNoChild(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.tornadoFrequency, 0.0D);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        Vec3 centre = bed.centre();
        bed.player(centre);
        StormInstance parent = bed.spawnStorm("supercell", centre.x, centre.z, 1.0F);
        parent.steer(0.0F, 0.0F);
        bed.after(100, () -> {
            bed.expect(bed.data.storms().size() == 1,
                "Storms with the tornado frequency at zero: " + bed.data.storms());
            ConvectiveState state = parent.familyState(ConvectiveState.class, ConvectiveState::load);
            bed.expect(state.tornadoesSpawned == 0, "The supercell counts " + state.tornadoesSpawned + " tornadoes");
            bed.succeed();
        });
    }

    /**
     * A forced strike next to a lightning rod on oxidised copper lands on the rod. With full destruction the rod
     * is powered and the copper cleaned; with entities-only or visual-only neither happens. A creeper beside the
     * rod is charged unless the mode is visual-only.
     */
    static void lightningOnRodAndCopper(GameTestHelper helper, DestructionMode mode, boolean expectBlocks,
                                        boolean expectCharged) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.destructionMode, mode);
        BlockPos copper = bed.mid(0, 0, 0);
        BlockPos rod = copper.above();
        bed.set(copper, Blocks.OXIDIZED_COPPER);
        bed.set(rod, Blocks.LIGHTNING_ROD);
        Creeper creeper = bed.spawn(EntityType.CREEPER, bed.mid(1, 0, 0));
        BlockPos aim = bed.mid(3, 0, 3);
        bed.after(5, () -> {
            bed.command(String.format(Locale.ROOT, "weathermod storm strike %d %d %d",
                aim.getX(), aim.getY(), aim.getZ()));
            List<LightningBolt> bolts =
                bed.level.getEntitiesOfClass(LightningBolt.class, new AABB(rod.above()).inflate(0.5));
            bed.expect(bolts.size() == 1, "Expected the bolt on the lightning rod, found " + bolts.size() + " there");
        });
        bed.after(9, () -> {
            BlockState rodState = bed.level.getBlockState(rod);
            boolean powered = rodState.is(Blocks.LIGHTNING_ROD) && rodState.getValue(LightningRodBlock.POWERED);
            bed.expect(rodState.is(Blocks.LIGHTNING_ROD) && powered == expectBlocks,
                "The lightning rod is " + rodState + " with " + mode);
            bed.expect(bed.level.getBlockState(copper).is(expectBlocks ? Blocks.COPPER_BLOCK : Blocks.OXIDIZED_COPPER),
                "The copper under the rod is " + bed.level.getBlockState(copper) + " with " + mode);
            bed.expect(creeper.isPowered() == expectCharged,
                "The creeper is charged: " + creeper.isPowered() + ", expected " + expectCharged + " with " + mode);
            bed.succeed();
        });
    }

    /**
     * A downburst started by command blows outward from its middle: a cow at half its radius, where the outflow is
     * at its strongest, is pushed away from the middle, and a cow near the middle, where the air only comes down,
     * stays where it is.
     */
    static void downburstPushesOutward(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        Cow outer = bed.spawn(EntityType.COW, bed.mid(DOWNBURST_OUTER_BLOCKS, 0, 0));
        Cow inner = bed.spawn(EntityType.COW, bed.mid(0, 0, DOWNBURST_INNER_BLOCKS));
        Vec3 outerStart = outer.position();
        Vec3 innerStart = inner.position();
        bed.after(5, () -> bed.command("weathermod storm downburst"));
        bed.succeedWhen(() -> {
            bed.expect(helper.getTick() >= 150, "Waiting for the downburst to end");
            double pushed = outer.getX() - outerStart.x;
            bed.expect(pushed > 1.5, "The downburst pushed the cow at half its radius " + pushed
                + " blocks outward from " + centre);
            double drift = Math.abs(outer.getZ() - outerStart.z);
            bed.expect(drift < 0.5, "The downburst pushed the cow " + drift + " blocks sideways");
            double innerMoved = inner.position().subtract(innerStart).horizontalDistance();
            bed.expect(innerMoved < 0.5, "The downburst moved the cow near its middle " + innerMoved + " blocks");
        });
    }

    /**
     * A gale moves a cow under the open sky downwind and leaves a cow under a roof where it stands. The wind acts
     * only around players, so one stands in the scene.
     */
    static void windPushesOnlyInTheOpen(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.player(bed.centre());
        BlockPos roofedCell = bed.mid(-5, 0, 3);
        for (BlockPos pos : BlockPos.betweenClosed(roofedCell.offset(-1, 2, -1), roofedCell.offset(1, 2, 1))) {
            bed.set(pos, Blocks.BEDROCK);
        }
        Cow open = bed.spawn(EntityType.COW, bed.mid(-5, 0, -3));
        Cow roofed = bed.spawn(EntityType.COW, roofedCell);
        Vec3 openStart = open.position();
        Vec3 roofedStart = roofed.position();
        // The light under the new roof settles within a few ticks; the wind comes after that. It blows east.
        bed.after(20, () -> bed.command("weathermod wind set 90 16"));
        bed.eachTick(() -> bed.expect(roofed.position().distanceTo(roofedStart) < 0.05,
            "The cow under the roof was moved to " + roofed.position()));
        bed.succeedWhen(() -> {
            bed.expect(helper.getTick() >= 150, "Watching the cow under the roof");
            double downwind = open.getX() - openStart.x;
            bed.expect(downwind > 1.0, "The gale moved the cow in the open " + downwind + " blocks downwind");
        });
    }

    /**
     * Hail over a field of short grass breaks plants when {@code hailBlockDamage} is on, each announced by a break
     * event of the weather actor, and breaks none when it is off.
     */
    static void hailOnPlants(GameTestHelper helper, boolean blockDamage) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        bed.config(ServerConfig.hailBlockDamage, blockDamage);
        List<BlockPos> plants = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bed.at(1, 0, 1), bed.at(bed.size() - 2, 0, bed.size() - 2))) {
            bed.set(pos, Blocks.DIRT);
            bed.set(pos.above(), Blocks.SHORT_GRASS);
            plants.add(pos.above().immutable());
        }
        List<BlockPos> announced = new ArrayList<>();
        bed.listen(BlockEvent.BreakEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getPlayer())) {
                announced.add(event.getPos().immutable());
            }
        });
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("hailstorm", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        storm.setPosition(centre.x - HailCore.centreX(0.0, 0.0F, storm.radius()),
            centre.z - HailCore.centreZ(0.0, 0.0F, storm.radius()));
        storm.familyState(ConvectiveState.class, ConvectiveState::load).hailBurstTicks = FORCED_BURST_TICKS;
        bed.after(5, () -> bed.expect(storm.hail() > 0.9F, "No hail over the field: " + storm.hail()));
        bed.succeedWhen(() -> {
            List<BlockPos> broken = new ArrayList<>();
            for (BlockPos pos : plants) {
                if (!bed.level.getBlockState(pos).is(Blocks.SHORT_GRASS)) {
                    broken.add(pos);
                }
            }
            for (BlockPos pos : broken) {
                bed.expect(announced.contains(pos), "Hail broke the plant at " + pos.toShortString()
                    + " without a break event of the weather actor");
            }
            if (blockDamage) {
                bed.expect(broken.size() >= 3, "Hail has broken " + broken.size() + " of " + plants.size() + " plants");
            } else {
                bed.expect(broken.isEmpty(), "Hail broke " + broken.size() + " plants with hailBlockDamage off");
                bed.expect(helper.getTick() >= 400, "Watching the plants");
            }
        });
    }

    /**
     * Hail over a glass roof leaves it whole by default and breaks panes of it when {@code hailBreaksGlass} is on,
     * each announced by a break event of the weather actor.
     */
    static void hailOnGlass(GameTestHelper helper, boolean breaksGlass) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        bed.config(ServerConfig.hailBreaksGlass, breaksGlass);
        List<BlockPos> roof = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bed.at(1, 3, 1), bed.at(bed.size() - 2, 3, bed.size() - 2))) {
            bed.set(pos, Blocks.GLASS);
            roof.add(pos.immutable());
        }
        List<BlockPos> announced = new ArrayList<>();
        bed.listen(BlockEvent.BreakEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getPlayer())) {
                announced.add(event.getPos().immutable());
            }
        });
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("hailstorm", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        storm.setPosition(centre.x - HailCore.centreX(0.0, 0.0F, storm.radius()),
            centre.z - HailCore.centreZ(0.0, 0.0F, storm.radius()));
        storm.familyState(ConvectiveState.class, ConvectiveState::load).hailBurstTicks = FORCED_BURST_TICKS;
        bed.after(5, () -> bed.expect(storm.hail() > 0.9F, "No hail over the roof: " + storm.hail()));
        bed.succeedWhen(() -> {
            List<BlockPos> broken = new ArrayList<>();
            for (BlockPos pos : roof) {
                if (!bed.level.getBlockState(pos).is(Blocks.GLASS)) {
                    broken.add(pos);
                }
            }
            for (BlockPos pos : broken) {
                bed.expect(announced.contains(pos), "Hail broke the glass at " + pos.toShortString()
                    + " without a break event of the weather actor");
            }
            if (breaksGlass) {
                bed.expect(broken.size() >= 3, "Hail has broken " + broken.size() + " of " + roof.size() + " panes");
            } else {
                bed.expect(broken.isEmpty(), "Hail broke " + broken.size() + " panes with hailBreaksGlass off");
                bed.expect(helper.getTick() >= 400, "Watching the roof");
            }
        });
    }

    /** Walls a one-block cell in with bedrock two blocks high, with or without a roof. */
    private static void pen(TestBed bed, BlockPos cell, boolean roof) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            bed.set(cell.relative(side), Blocks.BEDROCK);
            bed.set(cell.relative(side).above(), Blocks.BEDROCK);
        }
        if (roof) {
            bed.set(cell.above(), Blocks.BEDROCK);
        }
    }
}
