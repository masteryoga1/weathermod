package com.weathermod.gametest;

import com.weathermod.config.ServerConfig;
import com.weathermod.server.StormInstance;
import com.weathermod.server.impact.ProtectedActions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.NoteBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Lightning beyond the forced strike: the strikes a thunderstorm makes by itself, the filter that keeps vanilla's
 * own storm lightning out while the mod drives the thunder, and a strike inside a weather deflector.
 */
final class LightningTests {
    private static final int THUNDER_RAMP_TICKS = 130;
    /** How long the strikes of the thunderstorm are counted, in ticks. */
    private static final int WATCH_TICKS = 900;
    /** How long no strike may come once the lightning frequency is zero, in ticks. */
    private static final int SILENCE_TICKS = 400;
    /** A thunderstorm at its peak strikes every 8 to 14 seconds: three to five times in the watch, six at most. */
    private static final int FEWEST_STRIKES = 3;
    private static final int MOST_STRIKES = 6;
    /** The radius the thunderstorm is given, so that every point it can aim at lies inside the scene. */
    private static final float SMALL_RADIUS_BLOCKS = 12.0F;

    private LightningTests() {
    }

    /**
     * A thunderstorm at its peak over a player strikes by itself every eight to fourteen seconds, every bolt inside
     * its radius, and stops at once when the lightning frequency is set to zero. The storm is given a radius of
     * twelve blocks: bolts strike only in chunks that tick their entities, and around the scene few do.
     */
    static void thunderstormStrikesInsideItself(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        Vec3 centre = bed.centre();
        bed.player(centre);
        List<Vec3> strikes = new ArrayList<>();
        bed.listen(EntityJoinLevelEvent.class, event -> {
            if (event.getLevel() == bed.level && event.getEntity() instanceof LightningBolt bolt) {
                strikes.add(bolt.position());
            }
        });
        StormInstance storm = bed.spawnStorm("thunderstorm", centre.x, centre.z, 1.0F);
        storm.steer(0.0F, 0.0F);
        storm.retype(storm.type(), SMALL_RADIUS_BLOCKS, storm.interestRadius());
        int[] atSilence = {-1};
        bed.after(WATCH_TICKS, () -> {
            bed.expect(strikes.size() >= FEWEST_STRIKES && strikes.size() <= MOST_STRIKES,
                "The thunderstorm struck " + strikes.size() + " times in " + WATCH_TICKS + " ticks, expected "
                    + FEWEST_STRIKES + " to " + MOST_STRIKES);
            for (Vec3 strike : strikes) {
                double distance = Math.hypot(strike.x - storm.x(), strike.z - storm.z());
                bed.expect(distance <= storm.radius() + 1.0,
                    "A bolt struck " + distance + " blocks from the middle of a storm of radius " + storm.radius());
            }
            bed.config(ServerConfig.lightningFrequency, 0.0D);
            atSilence[0] = strikes.size();
        });
        bed.after(WATCH_TICKS + SILENCE_TICKS, () -> {
            bed.expect(strikes.size() == atSilence[0],
                (strikes.size() - atSilence[0]) + " bolt(s) struck with the lightning frequency at zero");
            bed.succeed();
        });
    }

    /**
     * While a thunderstorm of the mod makes vanilla thunder, a bolt added the way vanilla's own storm adds it,
     * without a cause while the level ticks its chunks, is dropped. A bolt with a player as its cause, as a
     * Channeling trident makes it, stays, and so does a bolt without a cause that is added at any other time:
     * outside the level's tick, as a typed command adds it, at the start of the tick, and in a scheduled block
     * tick, where a command block runs its command. A skeleton trap horse that a command block summons stays as
     * well. With {@code suppressVanillaLightning} off vanilla's bolt stays too.
     */
    static void vanillaStormLightningIsDropped(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.lightningFrequency, 0.0D);
        Vec3 centre = bed.centre();
        ServerPlayer player = bed.player(centre);
        // What is run when the level's next tick begins, before its scheduled ticks and before its chunks tick.
        Deque<Runnable> atTickStart = new ArrayDeque<>();
        bed.listen(LevelTickEvent.Pre.class, event -> {
            if (event.getLevel() == bed.level) {
                drain(atTickStart);
            }
        });
        // What is run by the note block's event. The level handles block events right after it has ticked its
        // chunks and before it ticks its entities: inside the window in which vanilla's own storm bolts join.
        BlockPos note = bed.mid(0, 0, 5);
        bed.set(note, Blocks.NOTE_BLOCK);
        Deque<Runnable> whileChunksTick = new ArrayDeque<>();
        bed.listen(NoteBlockEvent.Play.class, event -> {
            if (event.getLevel() == bed.level && event.getPos().equals(note)) {
                drain(whileChunksTick);
            }
        });
        // Two command blocks beside a block that will power them. A powered command block runs its command in a
        // scheduled block tick, which the level handles before it ticks its chunks. The bolt is summoned in the
        // air, where it lights nothing, and the horse cannot spring its trap. A server with command blocks
        // switched off never runs them, so there this part is left out.
        boolean commandBlocks = bed.level.getServer().isCommandBlockEnabled();
        BlockPos power = bed.mid(-5, 0, 0);
        BlockPos summonedBolt = bed.mid(-5, 4, -5);
        BlockPos summonedHorse = bed.mid(5, 0, 0);
        if (commandBlocks) {
            commandBlock(bed, power.north(), String.format(Locale.ROOT, "summon minecraft:lightning_bolt %d %d %d",
                summonedBolt.getX(), summonedBolt.getY(), summonedBolt.getZ()));
            commandBlock(bed, power.south(), String.format(Locale.ROOT,
                "summon minecraft:skeleton_horse %d %d %d {SkeletonTrap:1b,NoAI:1b}",
                summonedHorse.getX(), summonedHorse.getY(), summonedHorse.getZ()));
        }
        boolean[] joined = new boolean[4];
        bed.after(3, () -> bed.spawnStorm("thunderstorm", centre.x, centre.z, 1.0F).steer(0.0F, 0.0F));
        bed.after(6 + THUNDER_RAMP_TICKS, () -> {
            bed.expect(bed.level.isThundering(), "Vanilla does not report thunder under the thunderstorm");
            bed.expect(addBolt(bed, centre, null),
                "A bolt added outside the chunk tick, as a command adds it, was dropped");
            atTickStart.add(() -> joined[3] = addBolt(bed, centre, null));
            whileChunksTick.add(() -> joined[0] = addBolt(bed, centre, null));
            whileChunksTick.add(() -> joined[1] = addBolt(bed, centre, player));
            bed.level.blockEvent(note, Blocks.NOTE_BLOCK, 0, 0);
            if (commandBlocks) {
                bed.set(power, Blocks.REDSTONE_BLOCK);
            }
        });
        bed.after(9 + THUNDER_RAMP_TICKS, () -> {
            // The summoned horse is taken out before anything is checked, so a failed check leaves none behind.
            List<LightningBolt> bolts =
                bed.level.getEntitiesOfClass(LightningBolt.class, new AABB(summonedBolt).inflate(0.5));
            List<SkeletonHorse> horses =
                bed.level.getEntitiesOfClass(SkeletonHorse.class, new AABB(summonedHorse).inflate(0.5));
            horses.forEach(SkeletonHorse::discard);
            bed.expect(atTickStart.isEmpty(), "The level did not begin a tick");
            bed.expect(whileChunksTick.isEmpty(), "The level did not handle the note block's event");
            bed.expect(joined[3], "A bolt added at the start of the level's tick, before its chunks tick, was dropped");
            bed.expect(!joined[0], "A bolt of vanilla's own storm joined the level under the mod's thunder");
            bed.expect(joined[1], "A bolt caused by a player, as a Channeling trident makes it, was dropped");
            if (commandBlocks) {
                bed.expect(bolts.size() == 1,
                    "Expected the bolt a command block summoned, found " + bolts.size() + " there");
                bed.expect(horses.size() == 1,
                    "Expected the skeleton trap horse a command block summoned, found " + horses.size() + " there");
            }
            bed.config(ServerConfig.suppressVanillaLightning, false);
            whileChunksTick.add(() -> joined[2] = addBolt(bed, centre, null));
            bed.level.blockEvent(note, Blocks.NOTE_BLOCK, 0, 0);
        });
        bed.after(12 + THUNDER_RAMP_TICKS, () -> {
            bed.expect(whileChunksTick.isEmpty(), "The level did not handle the note block's event");
            bed.expect(joined[2], "Vanilla's own bolt was dropped although suppressVanillaLightning is off");
            bed.succeed();
        });
    }

    /**
     * A strike drawn by a lightning rod that stands inside a weather deflector's circle shows its bolt on the rod
     * and does nothing else: the rod stays dark, the oxidised copper under it stays oxidised and the creeper
     * beside it is not charged.
     */
    static void deflectorKeepsLightningOffRodAndCopper(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        bed.config(ServerConfig.deflectorRadius, 8);
        BlockPos copper = bed.mid(0, 0, 0);
        BlockPos rod = copper.above();
        bed.set(copper, Blocks.OXIDIZED_COPPER);
        bed.set(rod, Blocks.LIGHTNING_ROD);
        bed.deflector(bed.mid(4, 0, 0));
        Creeper creeper = bed.spawn(EntityType.CREEPER, bed.mid(1, 0, 0));
        BlockPos aim = bed.mid(-3, 0, -3);
        bed.after(5, () -> {
            bed.expect(ProtectedActions.deflected(bed.level, rod.getX() + 0.5, rod.getZ() + 0.5),
                "The lightning rod does not stand inside the deflector's circle");
            bed.command(String.format(Locale.ROOT, "weathermod storm strike %d %d %d",
                aim.getX(), aim.getY(), aim.getZ()));
            List<LightningBolt> bolts =
                bed.level.getEntitiesOfClass(LightningBolt.class, new AABB(rod.above()).inflate(0.5));
            bed.expect(bolts.size() == 1, "Expected the bolt on the lightning rod, found " + bolts.size() + " there");
        });
        bed.after(9, () -> {
            BlockState rodState = bed.level.getBlockState(rod);
            bed.expect(rodState.is(Blocks.LIGHTNING_ROD) && !rodState.getValue(LightningRodBlock.POWERED),
                "The lightning rod inside the deflector is " + rodState);
            bed.expect(bed.level.getBlockState(copper).is(Blocks.OXIDIZED_COPPER),
                "The copper under the rod inside the deflector is " + bed.level.getBlockState(copper));
            bed.expect(!creeper.isPowered(), "The creeper inside the deflector was charged");
            bed.succeed();
        });
    }

    /** Runs everything that waits in the queue and leaves it empty. */
    private static void drain(Deque<Runnable> queue) {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    /** Places a command block that runs the command once when it is powered. */
    private static void commandBlock(TestBed bed, BlockPos pos, String command) {
        bed.set(pos, Blocks.COMMAND_BLOCK);
        if (!(bed.level.getBlockEntity(pos) instanceof CommandBlockEntity commandBlock)) {
            throw new GameTestAssertException("No command block stands at " + pos.toShortString());
        }
        commandBlock.getCommandBlock().setCommand(command);
    }

    /** Adds a bolt that only shows and reports whether the level took it. */
    private static boolean addBolt(TestBed bed, Vec3 where, @Nullable ServerPlayer cause) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(bed.level);
        if (bolt == null) {
            throw new GameTestAssertException("Could not create a lightning bolt");
        }
        bolt.moveTo(where);
        bolt.setVisualOnly(true);
        bolt.setCause(cause);
        return bed.level.addFreshEntity(bolt);
    }
}
