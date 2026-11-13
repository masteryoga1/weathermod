package com.weathermod.server.impact;

import com.mojang.authlib.GameProfile;
import com.weathermod.block.DeflectorIndex;
import com.weathermod.block.WeatherDeflectorBlock;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.WeatherManager;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The one door through which the weather changes blocks. Every removal, conversion and placement is attributed to
 * one fake player and announced with the event NeoForge's own callers fire for it, before the world changes, so
 * that spawn protection, the world border and every protection mod can refuse; a refusal leaves the world
 * untouched.
 *
 * <p>Nothing here loads a chunk: a position whose own chunk or one of the four columns next to it is not loaded is
 * refused, because changing a block updates its neighbours and an update across the edge of the loaded world would
 * load what lies beyond.
 */
public final class ProtectedActions {
    /** The identity every world change of the mod is attributed to. */
    public static final GameProfile PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("weathermod:storm".getBytes(StandardCharsets.UTF_8)), "[WeatherMod]");

    private ProtectedActions() {
    }

    /** The fake player for a level; fetched on every call, never cached across levels or ticks. */
    public static FakePlayer actor(ServerLevel level) {
        return FakePlayerFactory.get(level, PROFILE);
    }

    /**
     * Whether the weather may break or convert a block: the position and its neighbours are loaded,
     * {@code level.mayInteract(actor, pos)} agrees (spawn protection, world border) and the break event posted by
     * {@code CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, actor, pos, state)} is not cancelled. Fires the
     * event; call it last, after every cheaper test.
     *
     * @param level the level the block is in
     * @param pos   the block's position
     * @param state the block as the caller read it
     */
    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos at = pos.immutable();
        if (!surroundingsLoaded(level, at)) {
            return false;
        }
        FakePlayer actor = actor(level);
        if (!level.mayInteract(actor, at)) {
            return false;
        }
        return !CommonHooks.fireBlockBreak(level, GameType.SURVIVAL, actor, at, state).isCanceled();
    }

    /**
     * Places a block on behalf of the weather. False when the position or a neighbour is not loaded or
     * {@code mayInteract} refuses. Otherwise the placement event is fired
     * ({@code EventHooks.onBlockPlace(actor, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP)})
     * before anything changes; a cancellation returns false and the world is untouched; otherwise the block is set
     * with {@link Block#UPDATE_ALL} and the result of that is returned.
     *
     * <p>Because the event comes first, a listener that asks the event for the placed block sees the block being
     * replaced. Listeners that decide by position and actor, as protection mods do, are served correctly.
     *
     * @param level the level to place in
     * @param pos   where to place
     * @param state the block to place
     */
    public static boolean place(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos at = pos.immutable();
        if (!surroundingsLoaded(level, at)) {
            return false;
        }
        FakePlayer actor = actor(level);
        if (!level.mayInteract(actor, at)) {
            return false;
        }
        if (EventHooks.onBlockPlace(actor, BlockSnapshot.create(level.dimension(), level, at), Direction.UP)) {
            return false;
        }
        return level.setBlock(at, state, Block.UPDATE_ALL);
    }

    /**
     * Replaces {@code oldState} by {@code newState} in place (scouring): asks {@link #mayBreak} for the old block
     * and then sets the new one. False when breaking is refused, when the block is no longer {@code oldState}
     * after the event, or when the level did not take the change.
     *
     * @param level    the level the block is in
     * @param pos      the block's position
     * @param oldState the block as the caller read it
     * @param newState the block that takes its place
     */
    public static boolean convert(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        BlockPos at = pos.immutable();
        if (!mayBreak(level, at, oldState) || level.getBlockState(at) != oldState) {
            return false;
        }
        return level.setBlock(at, newState, Block.UPDATE_ALL);
    }

    /**
     * Removes a block the caller was allowed to break ({@link #mayBreak} returned true): when {@code drop}, its
     * loot is dropped as if the actor had broken it with an empty hand; then the block is replaced by what its
     * fluid state leaves (air for a dry block); when that succeeded the destroy game event is sent and
     * {@link #breakEffect} is asked for the sound and particles. This is {@code Level.destroyBlock} without its
     * unconditional level event.
     *
     * <p>False, and nothing happens, when the position or a neighbour is not loaded or the block there is no
     * longer {@code state}.
     *
     * @param level the level the block is in
     * @param pos   the block's position
     * @param state the block as the caller read it and offered to {@link #mayBreak}
     * @param drop  whether the block's loot is dropped
     */
    public static boolean destroy(ServerLevel level, BlockPos pos, BlockState state, boolean drop) {
        BlockPos at = pos.immutable();
        if (!surroundingsLoaded(level, at) || level.getBlockState(at) != state) {
            return false;
        }
        FakePlayer actor = actor(level);
        FluidState fluid = level.getFluidState(at);
        if (drop) {
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(at) : null;
            Block.dropResources(state, level, at, blockEntity, actor, ItemStack.EMPTY);
        }
        if (!level.setBlock(at, fluid.createLegacyBlock(), Block.UPDATE_ALL)) {
            return false;
        }
        level.gameEvent(GameEvent.BLOCK_DESTROY, at, GameEvent.Context.of(actor, state));
        breakEffect(level, at, state);
        return true;
    }

    /**
     * Sends the block break sound and particles of a block (level event 2001) when the level's runtime grants
     * {@code tryBreakEffect()}. The only sender of that event in the mod: one event makes every client in range
     * add up to 64 particles outside every particle budget, so the whole level shares a small allowance per tick.
     *
     * @param level the level the block was in
     * @param pos   the block's position
     * @param state the block that broke
     */
    public static void breakEffect(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return;
        }
        if (DimensionWeatherData.get(level).runtime().tryBreakEffect()) {
            level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        }
    }

    /**
     * Whether (x, z) lies within {@code WeatherManager.deflectorRadius()} of a weather deflector of this dimension.
     * Deflectors in unloaded chunks count: the index remembers them. An entry whose chunk is loaded but whose block
     * is no longer a deflector is stale; it is removed from the index and ignored.
     *
     * @param level the level to ask
     * @param x     the position along {@code X}
     * @param z     the position along {@code Z}
     */
    public static boolean deflected(ServerLevel level, double x, double z) {
        DimensionWeatherData data = DimensionWeatherData.get(level);
        DeflectorIndex index = data.deflectors();
        if (index.size() == 0) {
            return false;
        }
        List<BlockPos> hits = index.covering(x, z, WeatherManager.deflectorRadius());
        if (hits.isEmpty()) {
            return false;
        }
        ServerChunkCache chunks = level.getChunkSource();
        boolean deflected = false;
        List<BlockPos> stale = null;
        for (BlockPos hit : hits) {
            LevelChunk chunk = chunks.getChunkNow(hit.getX() >> 4, hit.getZ() >> 4);
            if (chunk != null && !(chunk.getBlockState(hit).getBlock() instanceof WeatherDeflectorBlock)) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(hit);
                continue;
            }
            deflected = true;
            break;
        }
        if (stale != null) {
            boolean removed = false;
            for (BlockPos pos : stale) {
                removed |= index.remove(pos);
            }
            if (removed) {
                data.setDirty();
            }
        }
        return deflected;
    }

    /**
     * Whether the chunk of a position and the chunks of the four columns next to it are loaded; never loads one.
     * A block change there cannot reach into an unloaded chunk.
     */
    static boolean surroundingsLoaded(ServerLevel level, BlockPos pos) {
        ServerChunkCache chunks = level.getChunkSource();
        int x = pos.getX();
        int z = pos.getZ();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (chunks.getChunkNow(chunkX, chunkZ) == null) {
            return false;
        }
        int west = (x - 1) >> 4;
        int east = (x + 1) >> 4;
        int north = (z - 1) >> 4;
        int south = (z + 1) >> 4;
        return (west == chunkX || chunks.getChunkNow(west, chunkZ) != null)
            && (east == chunkX || chunks.getChunkNow(east, chunkZ) != null)
            && (north == chunkZ || chunks.getChunkNow(chunkX, north) != null)
            && (south == chunkZ || chunks.getChunkNow(chunkX, south) != null);
    }
}
