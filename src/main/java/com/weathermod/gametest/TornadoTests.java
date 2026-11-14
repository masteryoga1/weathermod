package com.weathermod.gametest;

import com.weathermod.block.WeatherBlocks;
import com.weathermod.config.DestructionMode;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.family.VortexMath;
import com.weathermod.server.family.VortexState;
import com.weathermod.server.impact.ForceRules;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Tornadoes and small vortices in the running server. Every scene stands on the bedrock floor of the arena
 * template with an EF3 tornado parked in its middle: the funnel's base wanders at most six blocks from there and
 * rips within fifteen blocks of the base, so everything within nine blocks of the middle is always in reach.
 */
final class TornadoTests {
    /** Flying blocks an EF3 may keep with {@code maxFlyingBlocksPerStorm} at 8: its share of three quarters. */
    private static final int EF3_FLYING_CAP_OF_EIGHT = 6;
    private static final int GROUNDED_BY_TICKS = 40;
    private static final String EF3 = "tornado_ef3";
    private static final int DIG_MOUND_BLOCKS = 10;
    private static final int EF5_MAX_DIG_BLOCKS = 6;
    private static final int GROWTH_LIFE_TICKS = 2100;
    private static final int SETTLE_TICKS = 20;

    private TornadoTests() {
    }

    /**
     * An EF3 over a small scene removes the blocks its tier tag or their softness allows, scours grass, and
     * leaves alone what is too hard, tagged for a higher tier, tagged never to be ripped or holds a block entity.
     * A listener cancels every break event inside a box of planks, and that box stays whole. Every block that
     * changes was announced by a break event of the weather actor first.
     */
    static void ef3RipsWhatItsTierAllows(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        // Thrown blocks shatter instead of landing, so a block that is gone stays gone.
        bed.config(ServerConfig.flyingBlockLandChance, 0.0D);

        Map<BlockPos, Block> rippable = new LinkedHashMap<>();
        rippable.put(bed.mid(-4, 0, 0), Blocks.OAK_PLANKS);
        rippable.put(bed.mid(4, 0, 0), Blocks.WHITE_WOOL);
        rippable.put(bed.mid(0, 0, -4), Blocks.GLASS);
        rippable.put(bed.mid(0, 0, 4), Blocks.OAK_LOG);
        rippable.put(bed.mid(-5, 0, -5), Blocks.COBBLESTONE);
        rippable.put(bed.mid(5, 0, 5), Blocks.NETHERRACK);
        rippable.put(bed.mid(-2, 0, 2), Blocks.OAK_PLANKS);
        rippable.put(bed.mid(-2, 1, 2), Blocks.OAK_PLANKS);
        rippable.put(bed.mid(-2, 2, 2), Blocks.OAK_PLANKS);
        Map<BlockPos, Block> lasting = new LinkedHashMap<>();
        lasting.put(bed.mid(-6, 0, 3), Blocks.OBSIDIAN);
        lasting.put(bed.mid(6, 0, -3), Blocks.STONE_BRICKS);
        lasting.put(bed.mid(3, 0, 6), Blocks.STONE);
        lasting.put(bed.mid(-3, 0, -6), Blocks.IRON_BLOCK);
        lasting.put(bed.mid(-3, 0, 6), Blocks.LODESTONE);
        lasting.put(bed.mid(-7, 0, 0), Blocks.ENCHANTING_TABLE);
        lasting.put(bed.mid(0, 0, 7), Blocks.JUKEBOX);
        BlockPos siren = bed.mid(7, 0, 0);
        lasting.put(siren, WeatherBlocks.TORNADO_SIREN.get());
        rippable.forEach(bed::set);
        lasting.forEach(bed::set);
        BlockPos grass = bed.mid(2, 0, 2);
        bed.set(grass, Blocks.GRASS_BLOCK);
        Set<BlockPos> guarded = new HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(bed.mid(4, 0, -6), bed.mid(5, 1, -5))) {
            guarded.add(pos.immutable());
            bed.set(pos, Blocks.OAK_PLANKS);
        }

        Set<BlockPos> announced = new HashSet<>();
        int[] cancelled = {0};
        bed.listen(BlockEvent.BreakEvent.class, event -> {
            if (!TestBed.isWeatherActor(event.getPlayer())) {
                return;
            }
            BlockPos pos = event.getPos().immutable();
            if (guarded.contains(pos)) {
                event.setCanceled(true);
                cancelled[0]++;
            } else {
                announced.add(pos);
            }
        });
        Map<BlockPos, BlockState> before = bed.snapshot();
        Vec3 centre = bed.centre();
        bed.spawnStorm(EF3, centre.x, centre.z, 1.0F).steer(0.0F, 0.0F);

        bed.eachTick(() -> {
            lasting.forEach((pos, block) -> bed.expect(bed.level.getBlockState(pos).is(block),
                "The tornado changed the " + block.getName().getString() + " at " + pos.toShortString() + " into "
                    + bed.level.getBlockState(pos)));
            for (BlockPos pos : guarded) {
                bed.expect(bed.level.getBlockState(pos).is(Blocks.OAK_PLANKS),
                    "A block whose break event was cancelled is gone: " + pos.toShortString());
            }
            if (helper.getTick() % 10 == 0) {
                for (BlockPos pos : bed.changedSince(before)) {
                    bed.expect(pos.equals(siren) || announced.contains(pos),
                        "The block at " + pos.toShortString() + " changed without a break event of the weather actor");
                }
            }
        });
        bed.succeedWhen(() -> {
            rippable.forEach((pos, block) -> bed.expect(bed.level.getBlockState(pos).isAir(),
                "The " + block.getName().getString() + " at " + pos.toShortString() + " still stands"));
            bed.expect(!bed.level.getBlockState(grass).is(Blocks.GRASS_BLOCK), "The grass block was not scoured");
            bed.expect(cancelled[0] > 0, "No break event reached the guarded box");
        });
    }

    /**
     * With entities-only or visual-only destruction a tornado over rippable blocks changes no block, fires no
     * break or place event and throws nothing. It still stands on the ground. Entities-only moves a pig in reach,
     * visual-only leaves it where it stands.
     */
    static void modeChangesNoBlock(GameTestHelper helper, DestructionMode mode) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.destructionMode, mode);
        for (BlockPos pos : BlockPos.betweenClosed(bed.mid(-2, 0, -2), bed.mid(2, 0, 2))) {
            bed.set(pos, Blocks.OAK_PLANKS);
        }
        bed.set(bed.mid(-5, 0, 0), Blocks.WHITE_WOOL);
        bed.set(bed.mid(0, 0, -5), Blocks.GLASS);
        bed.set(bed.mid(0, 0, 5), Blocks.OAK_LOG);
        bed.set(bed.mid(4, 0, 4), Blocks.GRASS_BLOCK);
        Pig pig = bed.spawn(EntityType.PIG, bed.mid(8, 0, 0));
        Vec3 start = pig.position();
        int[] events = {0};
        bed.listen(BlockEvent.BreakEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getPlayer())) {
                events[0]++;
            }
        });
        bed.listen(BlockEvent.EntityPlaceEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getEntity())) {
                events[0]++;
            }
        });
        Map<BlockPos, BlockState> before = bed.snapshot();
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm(EF3, centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        Travel travel = new Travel(pig, start);

        bed.eachTick(() -> {
            bed.expect(bed.flyingBlocks().isEmpty(), "A flying block exists with " + mode);
            travel.observe();
        });
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.after(300, () -> {
            List<BlockPos> changed = bed.changedSince(before);
            bed.expect(changed.isEmpty(), changed.size() + " block(s) changed with " + mode + ", first at "
                + (changed.isEmpty() ? "" : changed.get(0).toShortString()));
            bed.expect(events[0] == 0, events[0] + " break or place event(s) were fired with " + mode);
            if (mode.affectsEntities()) {
                bed.expect(travel.stamped && travel.furthest > 2.0,
                    "The pig was not moved: stamped " + travel.stamped + ", moved " + travel.furthest);
            } else {
                bed.expect(pig.isAlive() && !travel.stamped && travel.furthest < 0.05,
                    "The pig was touched with " + mode + ": stamped " + travel.stamped + ", moved "
                        + travel.furthest);
            }
            bed.succeed();
        });
    }

    /**
     * A tornado over a field of planks throws blocks, never keeps more in the air than its share of
     * {@code maxFlyingBlocksPerStorm}, and the runtime's count matches the entities in the level. Once the tornado
     * is removed every flying block comes down: none is left in the air and at least one was placed where it
     * landed, as the weather actor with the place event fired.
     */
    static void flyingBlocksStayWithinTheCap(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.maxFlyingBlocksPerStorm, 8);
        bed.config(ServerConfig.flyingBlockLandChance, 1.0D);
        bed.config(ServerConfig.rippedBlockDropChance, 0.0D);
        for (BlockPos pos : BlockPos.betweenClosed(bed.mid(-5, 0, -5), bed.mid(5, 0, 5))) {
            bed.set(pos, Blocks.OAK_PLANKS);
        }
        int[] landed = {0};
        bed.listen(BlockEvent.EntityPlaceEvent.class, event -> {
            if (TestBed.isWeatherActor(event.getEntity())) {
                landed[0]++;
            }
        });
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm(EF3, centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        int id = storm.id();
        int[] most = {0};
        boolean[] removed = {false};

        bed.eachTick(() -> {
            int flying = bed.flyingBlocks().size();
            most[0] = Math.max(most[0], flying);
            bed.expect(flying <= EF3_FLYING_CAP_OF_EIGHT,
                flying + " flying blocks in the air, the cap is " + EF3_FLYING_CAP_OF_EIGHT);
            if (!removed[0]) {
                int counted = bed.data.runtime().flyingBlocks(id);
                bed.expect(counted == flying,
                    "The runtime counts " + counted + " flying blocks, the level holds " + flying);
            }
        });
        bed.after(300, () -> {
            bed.expect(most[0] > 0, "No block was thrown in 300 ticks");
            bed.expect(WeatherManager.remove(bed.level, id), "The tornado was already gone");
            removed[0] = true;
        });
        bed.succeedWhen(() -> {
            bed.expect(removed[0], "The tornado is still up");
            int flying = bed.flyingBlocks().size();
            bed.expect(flying == 0, flying + " flying block(s) are still in the air");
            bed.expect(bed.data.runtime().flyingBlocksTotal() == 0,
                "The runtime still counts " + bed.data.runtime().flyingBlocksTotal() + " flying block(s)");
            bed.expect(landed[0] > 0, "No thrown block was placed where it landed");
        });
    }

    /**
     * A cow eight blocks from a tornado is reached by its field and carried off. An end crystal at the same
     * distance, whose type is tagged wind immune, is neither moved nor given any motion nor stamped.
     */
    static void vortexMovesMobsButNotTheImmune(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Cow cow = bed.spawn(EntityType.COW, bed.mid(8, 0, 0));
        EndCrystal crystal = bed.spawn(EntityType.END_CRYSTAL, bed.mid(-8, 0, 0));
        Vec3 crystalStart = crystal.position();
        Travel travel = new Travel(cow, cow.position());
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm(EF3, centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);

        bed.eachTick(() -> {
            travel.observe();
            bed.expect(crystal.isAlive(), "The end crystal is gone");
            bed.expect(crystal.position().distanceTo(crystalStart) < 1.0E-6
                && crystal.getDeltaMovement().lengthSqr() == 0.0,
                "The wind-immune end crystal was pushed: at " + crystal.position() + " moving "
                    + crystal.getDeltaMovement());
            bed.expect(!crystal.getPersistentData().contains(ForceRules.VORTEX_STAMP_KEY),
                "The wind-immune end crystal carries the vortex stamp");
        });
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.succeedWhen(() -> {
            bed.expect(helper.getTick() >= 100, "Watching the end crystal");
            bed.expect(travel.stamped, "The vortex field never reached the cow");
            bed.expect(travel.furthest > 3.0, "The cow was moved only " + travel.furthest + " blocks");
        });
    }

    /** With {@code pushMobs} off a cow in reach of a grounded tornado stays where it is and is not stamped. */
    static void pushMobsOffLeavesMobsAlone(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.pushMobs, false);
        Cow cow = bed.spawn(EntityType.COW, bed.mid(8, 0, 0));
        Travel travel = new Travel(cow, cow.position());
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm(EF3, centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        bed.eachTick(travel::observe);
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.after(150, () -> {
            bed.expect(cow.isAlive() && !travel.stamped && travel.furthest < 0.05,
                "The cow was touched with pushMobs off: stamped " + travel.stamped + ", moved " + travel.furthest);
            bed.succeed();
        });
    }

    /**
     * A fire whirl sets a cow in its funnel on fire, on a hard floor and on soul sand, into which a cow sinks an
     * eighth of a block. The game test server runs with the fire tick rule off, and while it is off the whirl
     * lights no block.
     */
    static void fireWhirlBurnsEntities(GameTestHelper helper, boolean onSoulSand) {
        TestBed bed = TestBed.begin(helper);
        if (onSoulSand) {
            int last = bed.size() - 1;
            for (BlockPos pos : BlockPos.betweenClosed(bed.at(0, 0, 0), bed.at(last, 0, last))) {
                bed.set(pos, Blocks.SOUL_SAND);
            }
        }
        Cow cow = bed.spawn(EntityType.COW, bed.mid(0, onSoulSand ? 1 : 0, 0));
        Map<BlockPos, BlockState> before = bed.snapshot();
        Vec3 centre = bed.centre();
        boolean fireTicks = bed.level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK);
        StormInstance[] whirl = {null};
        boolean[] burned = {false};
        // The whirl comes once the cow has settled on the floor.
        bed.after(SETTLE_TICKS, () -> {
            bed.expect(cow.onGround() && cow.getRemainingFireTicks() <= 0, "The cow has not settled on the floor");
            whirl[0] = bed.spawnStorm("fire_whirl", centre.x, centre.z, 1.0F);
            whirl[0].steer(0.0F, 0.0F);
        });
        bed.eachTick(() -> {
            burned[0] |= cow.getRemainingFireTicks() > 0;
            if (!fireTicks) {
                for (BlockPos pos : bed.changedSince(before)) {
                    bed.expect(!(bed.level.getBlockState(pos).getBlock() instanceof BaseFireBlock),
                        "The fire whirl lit a fire at " + pos.toShortString() + " with the fire tick rule off");
                }
            }
        });
        bed.succeedWhen(() -> {
            bed.expect(helper.getTick() >= SETTLE_TICKS + 100, "Watching for fires");
            ActiveVortex vortex = bed.data.runtime().vortex(whirl[0].id());
            bed.expect(burned[0], "The cow in the fire whirl never caught fire; it stands at y " + cow.getY()
                + (vortex == null ? "" : " and the funnel's foot is at y " + vortex.spine().groundY()));
        });
    }

    /**
     * A grounded tornado below its target tier grows one tier every twenty seconds and stops at the target, its
     * radius following the type. The tier command then changes the type at once, and the tornado keeps that tier.
     */
    static void tornadoGrowsToItsTargetTier(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        // The scene stays as it is; only the storm's own bookkeeping is under test here.
        bed.config(ServerConfig.destructionMode, DestructionMode.ENTITIES_ONLY);
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("tornado_ef0", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        // An EF0 lives two and a half minutes at most. The longer life must not put the storm back into the ramp
        // of its first tenth so far that the funnel lifts off the ground again.
        storm.setMaxAgeTicks(storm.ageTicks() + GROWTH_LIFE_TICKS);
        bed.expect(storm.intensity() >= VortexMath.GROUNDED_INTENSITY,
            "The tornado is too weak to stand on the ground: " + storm.intensity());
        storm.familyState(VortexState.class, VortexState::load).targetTier = 2;
        int id = storm.id();
        long[] firstSeen = {0, -1, -1, -1, -1, -1};
        long[] commandAt = {-1};
        bed.eachTick(() -> {
            int tier = tierOf(bed, storm);
            if (firstSeen[tier] < 0) {
                firstSeen[tier] = helper.getTick();
            }
            if (commandAt[0] < 0) {
                bed.expect(tier <= 2, "The tornado grew past its target tier to EF" + tier);
            } else {
                bed.expect(tier == 4, "The tornado left the tier the command gave it for EF" + tier);
            }
        });
        bed.succeedWhen(() -> {
            bed.expect(bed.data.find(id).isPresent(), "The tornado is gone");
            bed.expect(firstSeen[2] >= 0, "The tornado has not reached EF2, it is an EF" + tierOf(bed, storm));
            long toFirst = firstSeen[1];
            long toSecond = firstSeen[2] - firstSeen[1];
            bed.expect(Math.abs(toFirst - VortexMath.TIER_STEP_TICKS) <= 5
                && Math.abs(toSecond - VortexMath.TIER_STEP_TICKS) <= 5,
                "The tiers rose after " + toFirst + " and " + toSecond + " ticks, not every "
                    + VortexMath.TIER_STEP_TICKS);
            if (commandAt[0] < 0) {
                bed.expect(helper.getTick() - firstSeen[2] >= VortexMath.TIER_STEP_TICKS + 50, "Holding at EF2");
                float radius = WeatherManager.type(bed.level, storm).orElseThrow().radius();
                bed.expect(storm.radius() == radius, "The radius " + storm.radius() + " is not EF2's " + radius);
                bed.command("weathermod storm tier " + id + " 4");
                bed.expect(tierOf(bed, storm) == 4, "The tier command did not act at once");
                commandAt[0] = helper.getTick();
            }
            bed.expect(helper.getTick() - commandAt[0] >= VortexMath.TIER_STEP_TICKS + 50, "Holding at EF4");
        });
    }

    /** A waterspout over land gives up: it ends within seconds although its lifetime runs for minutes. */
    static void waterspoutEndsOverLand(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("waterspout", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        int id = storm.id();
        bed.expect(storm.remainingTicks() > 1500, "The waterspout has only " + storm.remainingTicks() + " ticks");
        bed.after(GROUNDED_BY_TICKS, () -> expectOnGround(bed, storm));
        bed.succeedWhen(() -> bed.expect(bed.data.find(id).isEmpty(),
            "The waterspout still stands on land after " + helper.getTick() + " ticks"));
    }

    /**
     * An EF5 parked for a minute over ten layers of dirt takes the top off the whole mound, never lowers the dig
     * reference it arrived with and digs nowhere deeper than six blocks below it.
     */
    static void ef5DigsNoDeeperThanSix(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.flyingBlockLandChance, 0.0D);
        bed.config(ServerConfig.rippedBlockDropChance, 0.0D);
        int last = bed.size() - 1;
        for (BlockPos pos : BlockPos.betweenClosed(bed.at(0, 0, 0), bed.at(last, DIG_MOUND_BLOCKS - 1, last))) {
            bed.set(pos, Blocks.DIRT);
        }
        Vec3 centre = bed.centre();
        StormInstance storm = bed.spawnStorm("tornado_ef5", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        // A tornado measures its dig limit from the terrain two base radii ahead of it, which for an EF5 lies far
        // outside the mound: on lower ground, on the wall around another scene or in a chunk that is not loaded.
        // The tornado is therefore given the reference it would have arrived with over ground of the mound's
        // height. Parked, it may raise that reference and must never lower it.
        double moundTop = bed.at(0, DIG_MOUND_BLOCKS, 0).getY();
        VortexState state = storm.familyState(VortexState.class, VortexState::load);
        state.digRefY = moundTop;
        int deepest = DIG_MOUND_BLOCKS - 1 - EF5_MAX_DIG_BLOCKS;
        bed.eachTick(() -> {
            if (helper.getTick() % 20 != 0) {
                return;
            }
            double reference = storm.familyState(VortexState.class, VortexState::load).digRefY;
            bed.expect(reference >= moundTop, "The dig reference of the parked EF5 fell from " + moundTop + " to "
                + reference + " after " + helper.getTick() + " ticks");
            for (BlockPos pos : BlockPos.betweenClosed(bed.at(0, 0, 0), bed.at(last, deepest, last))) {
                bed.expect(!bed.level.getBlockState(pos).isAir(), "The EF5 dug " + (DIG_MOUND_BLOCKS - bed.sceneY(pos))
                    + " blocks deep at " + pos.toShortString() + " after " + helper.getTick() + " ticks");
            }
        });
        bed.after(1200, () -> {
            int topGone = 0;
            int columns = 0;
            for (BlockPos pos : BlockPos.betweenClosed(bed.at(0, DIG_MOUND_BLOCKS - 1, 0),
                bed.at(last, DIG_MOUND_BLOCKS - 1, last))) {
                columns++;
                if (bed.level.getBlockState(pos).isAir()) {
                    topGone++;
                }
            }
            bed.expect(topGone * 2 > columns, "The EF5 took the top block of only " + topGone + " of " + columns
                + " columns in a minute");
            bed.succeed();
        });
    }

    private static int tierOf(TestBed bed, StormInstance storm) {
        for (int tier = 0; tier <= 5; tier++) {
            if (storm.type().equals(WeatherTypes.tornado(tier))) {
                return tier;
            }
        }
        throw new GameTestAssertException("The storm is no tornado any more: " + storm.typeId());
    }

    private static void expectOnGround(TestBed bed, StormInstance storm) {
        ActiveVortex vortex = bed.data.runtime().vortex(storm.id());
        bed.expect(vortex != null && vortex.actsOnWorld(), "The tornado does not stand on the ground");
    }

    /** Follows one entity: how far it has got from where it started and whether a vortex field ever reached it. */
    private static final class Travel {
        private final Entity entity;
        private final Vec3 start;
        private double furthest;
        private boolean stamped;

        Travel(Entity entity, Vec3 start) {
            this.entity = entity;
            this.start = start;
        }

        void observe() {
            furthest = Math.max(furthest, entity.position().distanceTo(start));
            stamped |= entity.getPersistentData().contains(ForceRules.VORTEX_STAMP_KEY);
        }
    }
}
