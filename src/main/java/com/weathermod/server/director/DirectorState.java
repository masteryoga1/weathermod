package com.weathermod.server.director;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * The storm director's bookkeeping for one dimension: the countdown to the next natural spawn attempt, whether an
 * operator paused natural spawning, and when and how often it has spawned. Persisted inside the dimension's
 * weather data under {@code "Director"}; every key is optional on load so worlds saved before the director existed
 * start with a fresh state.
 */
public final class DirectorState {
    /** Smallest frequency multiplier the re-arm arithmetic accepts, so a tiny multiplier never overflows the timer. */
    public static final double MIN_FREQUENCY = 0.01D;
    /** Lower bound of the random factor applied to the re-arm interval. */
    public static final float MIN_REARM_FACTOR = 0.5F;
    /** Upper bound (exclusive) of the random factor applied to the re-arm interval. */
    public static final float MAX_REARM_FACTOR = 1.5F;

    private static final String KEY_TICKS_UNTIL_ATTEMPT = "TicksUntilAttempt";
    private static final String KEY_PAUSED = "Paused";
    private static final String KEY_LAST_SPAWN_GAME_TIME = "LastSpawnGameTime";
    private static final String KEY_NATURAL_SPAWNS = "NaturalSpawns";

    /** Ticks left before the director tries to spawn a storm; zero or less means the next director tick tries. */
    public int ticksUntilAttempt;
    /** True while natural spawning is paused by command. Dissipation and heading bias pause with it. */
    public boolean paused;
    /** Game time of the most recent director spawn, or zero when there has been none since the world began. */
    public long lastSpawnGameTime;
    /** How many storms the director has spawned in this dimension over the life of the world. */
    public int naturalSpawns;

    /** A fresh state: not paused, nothing spawned yet, and the first attempt due at the next director tick. */
    public DirectorState() {
    }

    /** A state with every field given, as loaded from NBT or built by tests. Negative counters are clamped to zero. */
    public DirectorState(int ticksUntilAttempt, boolean paused, long lastSpawnGameTime, int naturalSpawns) {
        this.ticksUntilAttempt = Math.max(0, ticksUntilAttempt);
        this.paused = paused;
        this.lastSpawnGameTime = Math.max(0L, lastSpawnGameTime);
        this.naturalSpawns = Math.max(0, naturalSpawns);
    }

    /** Writes the state to a new compound tag. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_TICKS_UNTIL_ATTEMPT, ticksUntilAttempt);
        tag.putBoolean(KEY_PAUSED, paused);
        tag.putLong(KEY_LAST_SPAWN_GAME_TIME, lastSpawnGameTime);
        tag.putInt(KEY_NATURAL_SPAWNS, naturalSpawns);
        return tag;
    }

    /**
     * Reads a state written by {@link #save()}. Missing keys fall back to the fresh-state defaults and negative
     * counters are clamped to zero, so a damaged or pre-director tag still loads.
     */
    public static DirectorState load(CompoundTag tag) {
        return new DirectorState(
            tag.getInt(KEY_TICKS_UNTIL_ATTEMPT),
            tag.getBoolean(KEY_PAUSED),
            tag.getLong(KEY_LAST_SPAWN_GAME_TIME),
            tag.getInt(KEY_NATURAL_SPAWNS));
    }

    /**
     * The ticks to wait before the next attempt: {@code intervalSeconds} in ticks divided by the frequency
     * multiplier, times a random factor in {@code [MIN_REARM_FACTOR, MAX_REARM_FACTOR)}. The frequency is clamped to
     * at least {@link #MIN_FREQUENCY} and the result is never less than one tick.
     */
    public static int rearmTicks(int intervalSeconds, double frequency, RandomSource random) {
        double safeFrequency = Math.max(MIN_FREQUENCY, frequency);
        double base = Math.max(0, intervalSeconds) * (double) SharedConstants.TICKS_PER_SECOND / safeFrequency;
        double factor = MIN_REARM_FACTOR + random.nextFloat() * (MAX_REARM_FACTOR - MIN_REARM_FACTOR);
        long ticks = Math.round(base * factor);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ticks));
    }

    /** Re-arms the countdown with {@link #rearmTicks} and returns the new value. */
    public int rearm(int intervalSeconds, double frequency, RandomSource random) {
        this.ticksUntilAttempt = rearmTicks(intervalSeconds, frequency, random);
        return this.ticksUntilAttempt;
    }

    /** Records a director spawn at the given game time. */
    public void recordSpawn(long gameTime) {
        this.lastSpawnGameTime = Math.max(0L, gameTime);
        this.naturalSpawns++;
    }

    /** True once the director has spawned at least one storm in this dimension. */
    public boolean hasSpawned() {
        return naturalSpawns > 0;
    }

    /** Whole seconds until the next attempt, rounded up; zero when an attempt is already due. */
    public int secondsUntilAttempt() {
        if (ticksUntilAttempt <= 0) {
            return 0;
        }
        return (ticksUntilAttempt + SharedConstants.TICKS_PER_SECOND - 1) / SharedConstants.TICKS_PER_SECOND;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DirectorState that)) {
            return false;
        }
        return ticksUntilAttempt == that.ticksUntilAttempt
            && paused == that.paused
            && lastSpawnGameTime == that.lastSpawnGameTime
            && naturalSpawns == that.naturalSpawns;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(ticksUntilAttempt);
        result = 31 * result + Boolean.hashCode(paused);
        result = 31 * result + Long.hashCode(lastSpawnGameTime);
        result = 31 * result + Integer.hashCode(naturalSpawns);
        return result;
    }

    @Override
    public String toString() {
        return "DirectorState[ticksUntilAttempt=" + ticksUntilAttempt + ", paused=" + paused
            + ", lastSpawnGameTime=" + lastSpawnGameTime + ", naturalSpawns=" + naturalSpawns + "]";
    }
}
