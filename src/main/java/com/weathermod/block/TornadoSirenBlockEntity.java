package com.weathermod.block;

import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The working half of the tornado siren. On the server it looks for a rated tornado in range once in
 * {@link SirenMath#CHECK_INTERVAL_TICKS} ticks, keeps the hold counter that lets the siren sound on for
 * {@link SirenMath#HOLD_TICKS} after the last one, and writes {@link TornadoSirenBlock#ACTIVE}. On the client it
 * only hands itself to {@link #clientTick}, which is how the sound code learns about sirens.
 */
public final class TornadoSirenBlockEntity extends BlockEntity {
    /**
     * Called on the client for every loaded siren, every tick the level's block entities tick. Does nothing until
     * the client sound code assigns its own handler during client setup; common code never reads anything back.
     */
    public static BiConsumer<Level, TornadoSirenBlockEntity> clientTick = (level, siren) -> { };

    /** Key of the saved hold counter, an int. */
    private static final String HOLD_KEY = "Hold";
    private static final double BLOCK_CENTRE = 0.5;

    private int holdTicks;
    /** Server ticks left until the next look for tornadoes; zero on a fresh block entity, which looks at once. */
    private int ticksUntilCheck;

    /** Creates the block entity of the siren at a position. */
    public TornadoSirenBlockEntity(BlockPos pos, BlockState state) {
        super(WeatherBlocks.TORNADO_SIREN_ENTITY.get(), pos, state);
    }

    /**
     * The siren's server tick. Once in {@link SirenMath#CHECK_INTERVAL_TICKS} ticks, starting with the first tick
     * after the block entity loaded, it looks for a tornado and moves the hold counter on by one interval. Every
     * tick it compares {@link TornadoSirenBlock#ACTIVE} with what power and hold counter ask for and writes the
     * property when the two differ, so a redstone signal is answered within a tick.
     *
     * @param level the siren's level; anything but a server level is ignored
     * @param pos   the siren's position
     * @param state the block state at the position now
     * @param siren the block entity
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, TornadoSirenBlockEntity siren) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (siren.ticksUntilCheck > 0) {
            siren.ticksUntilCheck--;
        } else {
            siren.ticksUntilCheck = SirenMath.CHECK_INTERVAL_TICKS - 1;
            int hold = SirenMath.nextHold(siren.holdTicks, tornadoInRange(serverLevel, pos),
                SirenMath.CHECK_INTERVAL_TICKS);
            if (hold != siren.holdTicks) {
                siren.holdTicks = hold;
                siren.setChanged();
            }
        }
        boolean active = SirenMath.active(state.getValue(TornadoSirenBlock.POWERED), siren.holdTicks);
        if (active != state.getValue(TornadoSirenBlock.ACTIVE)) {
            level.setBlock(pos, state.setValue(TornadoSirenBlock.ACTIVE, active), Block.UPDATE_ALL);
        }
    }

    /**
     * The siren's client tick: passes the block entity to {@link #clientTick}. Nothing is passed on while the tick
     * rate manager holds the level still; while the game is paused the client ticks no block entity at all.
     */
    static void callClientHook(Level level, BlockPos pos, BlockState state, TornadoSirenBlockEntity siren) {
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        BiConsumer<Level, TornadoSirenBlockEntity> hook = clientTick;
        if (hook != null) {
            hook.accept(level, siren);
        }
    }

    /** Ticks the siren keeps sounding without a tornado in range; zero when the last one is long gone. */
    public int holdTicks() {
        return holdTicks;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        holdTicks = Mth.clamp(tag.getInt(HOLD_KEY), 0, SirenMath.HOLD_TICKS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(HOLD_KEY, holdTicks);
    }

    /**
     * Whether weather runs in the level and one of its storms is a rated tornado within the configured range of
     * the block's centre, see {@link SirenMath#inRange}.
     */
    private static boolean tornadoInRange(ServerLevel level, BlockPos pos) {
        if (!WeatherManager.isEnabled(level)) {
            return false;
        }
        double sirenX = pos.getX() + BLOCK_CENTRE;
        double sirenZ = pos.getZ() + BLOCK_CENTRE;
        int range = WeatherManager.sirenRange();
        List<StormInstance> storms = WeatherManager.get(level).storms();
        for (int i = 0; i < storms.size(); i++) {
            StormInstance storm = storms.get(i);
            if (!SirenMath.inRange(sirenX, sirenZ, storm.x(), storm.z(), storm.radius(), range)) {
                continue;
            }
            Optional<WeatherType> type = WeatherManager.type(level, storm);
            if (type.isPresent() && type.get().vortex().filter(vortex -> vortex.kind().isRated()).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
