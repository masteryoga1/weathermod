package com.weathermod.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.weathermod.block.WeatherBlocks;
import com.weathermod.config.ServerConfig;
import com.weathermod.entity.FlyingBlockEntity;
import com.weathermod.entity.WeatherEntities;
import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.DownburstPayload;
import com.weathermod.network.FunnelSpinePayload;
import com.weathermod.network.LightningStrikePayload;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WindUpdatePayload;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.impact.ProtectedActions;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What every game test stands on: the level, its weather data and a scene whose coordinates count from the north
 * west corner of the template's floor, with {@code y = 0} the first free layer above it. Starting a test wipes the
 * weather left behind by earlier tests or an earlier run (storms, downbursts, deflector positions, temporary fires,
 * debris), pauses the storm director and calms the wind, so the only weather in the dimension is what the test
 * makes. Everything a test changes through this class is undone when the test ends, whether it passed or failed:
 * config values go back to what they were, event listeners are removed, the stand-in player leaves, tracked
 * entities and placed deflectors are removed, the scene is emptied down to its floor and the director runs again.
 *
 * <p>Config values are changed in memory only and never written to the config file. Game rules are not touched.
 */
final class TestBed {
    /** The small template: 17 blocks square. */
    static final String PLATFORM = "weathermod:platform";
    /** The large template: 43 blocks square, wide enough for everything a parked EF3 tornado reaches. */
    static final String ARENA = "weathermod:arena";

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Relative height of the first free layer: the structure block, then the floor, then this. */
    private static final int FLOOR_OFFSET = 2;
    /** Debris of earlier tests is removed this far around the template. */
    private static final double SWEEP_BLOCKS = 160.0;
    /** The scene is emptied this far above the template when a test ends. */
    private static final int CLEAR_ABOVE_BLOCKS = 16;
    private static final String WATCHER_NAME = "storm-watcher";
    private static final List<ResourceLocation> PAYLOADS = List.of(
        StormListPayload.TYPE.id(), StormDeltaPayload.TYPE.id(), StormRemovedPayload.TYPE.id(),
        WindUpdatePayload.TYPE.id(), ClimateUpdatePayload.TYPE.id(), FunnelSpinePayload.TYPE.id(),
        LightningStrikePayload.TYPE.id(), DownburstPayload.TYPE.id());

    /** How many log lines the watch held when the first test began; negative until then. */
    private static int startupMark = -1;

    /** The helper of the running test. */
    final GameTestHelper helper;
    /** The level the test runs in. */
    final ServerLevel level;
    /** The weather data of that level. */
    final DimensionWeatherData data;

    private final int size;
    private final int logMark;
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private boolean closed;

    private TestBed(GameTestHelper helper) {
        this.helper = helper;
        this.level = helper.getLevel();
        if (!ServerConfig.SPEC.isLoaded()) {
            throw new GameTestAssertException("The server config is not loaded");
        }
        if (!WeatherManager.isEnabled(level)) {
            throw new GameTestAssertException("Weather is not enabled in " + level.dimension().location());
        }
        this.data = WeatherManager.get(level);
        this.size = (int) Math.round(helper.getBounds().getXsize());
        this.logMark = LogWatch.mark();
        if (startupMark < 0) {
            startupMark = this.logMark;
        }
    }

    /** Starts a test: registers the clean-up, wipes leftover weather, pauses the director and calms the wind. */
    static TestBed begin(GameTestHelper helper) {
        TestBed bed = new TestBed(helper);
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo testInfo) {
            }

            @Override
            public void testPassed(GameTestInfo test, GameTestRunner runner) {
                bed.close();
            }

            @Override
            public void testFailed(GameTestInfo test, GameTestRunner runner) {
                bed.close();
            }

            @Override
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {
            }
        });
        bed.sweep();
        boolean paused = bed.data.director().paused;
        bed.data.director().paused = true;
        bed.cleanups.push(() -> bed.data.director().paused = paused);
        bed.data.wind().set(0.0F, 0.0F);
        return bed;
    }

    /** The warnings and errors about the mod that were logged before the first test began. */
    static List<String> startupLog() {
        List<String> lines = LogWatch.since(0);
        return startupMark < 0 ? lines : lines.subList(0, Math.min(startupMark, lines.size()));
    }

    /** Width and depth of the template in blocks. */
    int size() {
        return size;
    }

    /** The block at scene coordinates, {@code y = 0} being the first free layer above the floor. */
    BlockPos at(int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y + FLOOR_OFFSET, z));
    }

    /** The block at an offset from the middle of the scene, {@code y = 0} being the first free layer. */
    BlockPos mid(int dx, int y, int dz) {
        return at(size / 2 + dx, y, size / 2 + dz);
    }

    /** The scene height of a block position: 0 for the first free layer above the floor. */
    int sceneY(BlockPos pos) {
        return pos.getY() - at(0, 0, 0).getY();
    }

    /** The point on the floor in the middle of the scene. */
    Vec3 centre() {
        return Vec3.atBottomCenterOf(mid(0, 0, 0));
    }

    /** Sets a block the way a player placing it would update its neighbours. */
    void set(BlockPos pos, Block block) {
        set(pos, block.defaultBlockState());
    }

    /** Sets a block state the way a player placing it would update its neighbours. */
    void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** Places a weather deflector, which is taken out again when the test ends so its index entry goes too. */
    void deflector(BlockPos pos) {
        set(pos, WeatherBlocks.WEATHER_DEFLECTOR.get());
        cleanups.push(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
    }

    /**
     * Spawns an entity standing on the given block position and removes it when the test ends, wherever it has got
     * to by then. A mob loses its goals, so it moves only when something moves it, and is kept from despawning.
     */
    <E extends Entity> E spawn(EntityType<E> type, BlockPos pos) {
        E entity = type.create(level);
        if (entity == null) {
            throw new GameTestAssertException("Could not create " + EntityType.getKey(type));
        }
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.removeFreeWill();
        }
        expect(level.addFreshEntity(entity), "Could not add " + EntityType.getKey(type) + " to the level");
        cleanups.push(entity::discard);
        return entity;
    }

    /** Changes a server config value in memory until the test ends. */
    <T> void config(ModConfigSpec.ConfigValue<T> value, T wanted) {
        T before = value.get();
        value.set(wanted);
        cleanups.push(() -> value.set(before));
    }

    /** Listens on the game event bus until the test ends. */
    <E extends Event> void listen(Class<E> type, Consumer<E> listener) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, type, listener);
        cleanups.push(() -> NeoForge.EVENT_BUS.unregister(listener));
    }

    /** Whether a player an event names is the actor the mod breaks and places blocks as. */
    static boolean isWeatherActor(@Nullable Entity entity) {
        return entity instanceof Player player && ProtectedActions.PROFILE.equals(player.getGameProfile());
    }

    /**
     * Puts a stand-in player into the level at the given point until the test ends. It is a real server player on
     * a connection that leads nowhere, so the systems that look for players (the vanilla weather bridge, hail, the
     * tornado roll) find one. The connection is told that it understands the mod's payloads, as a client with the
     * mod would have negotiated; without that the first weather packet would be refused. The player never moves.
     */
    ServerPlayer player(Vec3 where) {
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie =
            CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), WATCHER_NAME), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        player.moveTo(where.x, where.y, where.z, 0.0F, 0.0F);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        Set<ResourceLocation> channels = ChannelAttributes.getOrCreateAdHocChannels(connection);
        channels.addAll(PAYLOADS);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        cleanups.push(() -> {
            if (server.getPlayerList().getPlayer(player.getUUID()) != null) {
                server.getPlayerList().remove(player);
            }
        });
        return player;
    }

    /**
     * Runs a command the way an operator would type it, without the slash, from the middle of the scene with
     * permission level two. Fails the test when the command does not succeed.
     *
     * @return the command's result
     */
    int command(String command) {
        List<String> said = new ArrayList<>();
        Outcome outcome = run(console(said), command);
        expect(outcome.succeeded(), "The command /" + command + " failed: " + said);
        return outcome.result();
    }

    /** Runs a command as {@link #command} does and reports whether it was refused. */
    boolean commandFails(String command) {
        return !run(console(new ArrayList<>()), command).succeeded();
    }

    /**
     * Runs a command as the given player with permission level two, for the commands that need a player. Fails
     * the test when the command does not succeed.
     *
     * @return the command's result
     */
    int commandAs(ServerPlayer player, String command) {
        Outcome outcome =
            run(player.createCommandSourceStack().withPermission(Commands.LEVEL_GAMEMASTERS), command);
        expect(outcome.succeeded(), "The command /" + command + " failed for " + player.getScoreboardName());
        return outcome.result();
    }

    private CommandSourceStack console(List<String> said) {
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                said.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        return new CommandSourceStack(sink, centre(), Vec2.ZERO, level, Commands.LEVEL_GAMEMASTERS, "GameTest",
            Component.literal("GameTest"), level.getServer(), null);
    }

    private Outcome run(CommandSourceStack source, String command) {
        boolean[] succeeded = {false};
        int[] result = {0};
        level.getServer().getCommands().performPrefixedCommand(source.withCallback((success, value) -> {
            succeeded[0] = success;
            result[0] = value;
        }), command);
        return new Outcome(succeeded[0], result[0]);
    }

    /** What a command came to: whether it succeeded and, when it did, its result. */
    private record Outcome(boolean succeeded, int result) {
    }

    /**
     * Spawns a storm with {@code /weathermod storm spawn}, the path an operator uses.
     *
     * @param type the path of the weather type in the mod's namespace
     */
    StormInstance spawnStorm(String type, double x, double z, float intensity) {
        int id = command(String.format(Locale.ROOT, "weathermod storm spawn weathermod:%s %.3f %d %.3f %.2f",
            type, x, at(0, 0, 0).getY(), z, intensity));
        return data.find(id).orElseThrow(
            () -> new GameTestAssertException("The spawn command returned " + id + " but no such storm exists"));
    }

    /** Every block state inside the template, floor included, keyed by position. */
    Map<BlockPos, BlockState> snapshot() {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        AABB bounds = helper.getBounds();
        BlockPos from = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos to = BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0, bounds.maxZ - 1.0);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            states.put(pos.immutable(), level.getBlockState(pos));
        }
        return states;
    }

    /** The positions whose block state differs from an earlier {@link #snapshot()}. */
    List<BlockPos> changedSince(Map<BlockPos, BlockState> before) {
        List<BlockPos> changed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            if (level.getBlockState(entry.getKey()) != entry.getValue()) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    /** The flying blocks alive in the level. */
    List<? extends FlyingBlockEntity> flyingBlocks() {
        return level.getEntities(WeatherEntities.FLYING_BLOCK.get(), entity -> true);
    }

    /** Fails the test with the message unless the condition holds. */
    void expect(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /** Runs a step after a number of ticks; an exception in it fails the test. */
    void after(int ticks, Runnable step) {
        helper.runAfterDelay(ticks, step);
    }

    /** Runs a check on every remaining tick of the test; an exception in it fails the test. */
    void eachTick(Runnable check) {
        helper.onEachTick(check);
    }

    /**
     * Passes the test on the first tick on which the check no longer throws, provided the log is clean. When the
     * test runs out of time the last failure of the check is reported.
     */
    void succeedWhen(Runnable check) {
        helper.startSequence().thenWaitUntil(() -> {
            try {
                check.run();
            } catch (GameTestAssertException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new GameTestAssertException(failure.toString());
            }
        }).thenExecute(this::failOnLoggedProblems).thenSucceed();
    }

    /** Passes the test now, provided the mod has logged no warning or error since the test began. */
    void succeed() {
        failOnLoggedProblems();
        helper.succeed();
    }

    private void failOnLoggedProblems() {
        List<String> lines = LogWatch.since(logMark);
        if (!lines.isEmpty()) {
            helper.testInfo.fail(new GameTestAssertException(
                "The mod logged " + lines.size() + " warning(s) or error(s) during the test, first: " + lines.get(0)));
        }
    }

    private void sweep() {
        WeatherManager.clear(level);
        data.runtime().downbursts().clear();
        for (BlockPos pos : List.copyOf(data.deflectors().positions())) {
            data.deflectors().remove(pos);
        }
        for (long packed : data.temporaryFires().save().getLongArray("Pos")) {
            data.temporaryFires().remove(BlockPos.of(packed));
        }
        AABB around = helper.getBounds().inflate(SWEEP_BLOCKS);
        for (Entity debris : level.getEntities((Entity) null, around, TestBed::isDebris)) {
            debris.discard();
        }
        data.setDirty();
    }

    private static boolean isDebris(Entity entity) {
        return entity instanceof FlyingBlockEntity || entity instanceof ItemEntity
            || entity instanceof ExperienceOrb || entity instanceof LightningBolt;
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!cleanups.isEmpty()) {
            try {
                cleanups.pop().run();
            } catch (RuntimeException failure) {
                LOGGER.error("Cleaning up after game test {} failed", helper.testInfo.getTestName(), failure);
            }
        }
        try {
            clearScene();
            sweep();
        } catch (RuntimeException failure) {
            LOGGER.error("Sweeping after game test {} failed", helper.testInfo.getTestName(), failure);
        }
    }

    /**
     * Empties the scene: the floor is bedrock again and everything above it air, some way above the template too.
     * The game test framework clears a scene only before it builds one, so a block left standing would act on the
     * tests that run beside it later; a lightning rod, for one, draws every strike within 128 blocks.
     */
    private void clearScene() {
        AABB bounds = helper.getBounds();
        BlockPos from = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos to = BlockPos.containing(bounds.maxX - 1.0, bounds.maxY - 1.0 + CLEAR_ABOVE_BLOCKS, bounds.maxZ - 1.0);
        BlockState floor = Blocks.BEDROCK.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState wanted = pos.getY() == from.getY() ? floor : air;
            if (level.getBlockState(pos) != wanted) {
                level.setBlock(pos, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
