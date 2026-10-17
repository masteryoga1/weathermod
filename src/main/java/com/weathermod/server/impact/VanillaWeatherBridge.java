package com.weathermod.server.impact;

import com.weathermod.core.Coverage;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Keeps the vanilla rain and thunder flags of a dimension in step with the storms: vanilla rains while a
 * precipitating storm covers a player and is dry otherwise, so crops, cauldrons, fishing and mob spawning respond
 * to the mod's weather. The decision is pure; applying it is the only part that touches the level.
 */
public final class VanillaWeatherBridge {
    /** A player needs at least this coverage from a precipitating storm for vanilla rain to switch on. */
    public static final float RAIN_COVERAGE_THRESHOLD = 0.25F;
    /** A covering storm whose type has at least this thunder level also switches vanilla thunder on. */
    public static final float THUNDER_VISUAL_THRESHOLD = 0.5F;
    /**
     * The rain and thunder timers written on every change. Vanilla counts them down and flips the flags when they
     * reach zero, so they are rewritten whenever they drop below half of this.
     */
    public static final int REARM_TICKS = 1_000_000;

    private VanillaWeatherBridge() {
    }

    /**
     * What the vanilla flags should be.
     *
     * @param raining    whether vanilla should rain
     * @param thundering whether vanilla should thunder as well
     */
    public record Decision(boolean raining, boolean thundering) {
        /** Neither rain nor thunder. */
        public static final Decision CLEAR = new Decision(false, false);
    }

    /**
     * Decides the vanilla flags from the storms and the players of a dimension. Vanilla rains when some player has
     * at least {@link #RAIN_COVERAGE_THRESHOLD} coverage from a storm whose type precipitates, and thunders when
     * such a covering storm's type has at least {@link #THUNDER_VISUAL_THRESHOLD} thunder level. Storms whose type
     * cannot be resolved are ignored.
     */
    public static Decision decide(List<StormInstance> storms, Function<StormInstance, WeatherType> types,
                                  List<? extends Position> players) {
        if (storms.isEmpty() || players.isEmpty()) {
            return Decision.CLEAR;
        }
        boolean raining = false;
        boolean thundering = false;
        for (StormInstance storm : storms) {
            WeatherType type = types.apply(storm);
            if (type == null || !type.precipitates() || !coversAnyone(storm, players)) {
                continue;
            }
            raining = true;
            if (type.visuals().thunderLevel() >= THUNDER_VISUAL_THRESHOLD) {
                thundering = true;
                break;
            }
        }
        return new Decision(raining, thundering);
    }

    /** True when at least one of the positions has {@link #RAIN_COVERAGE_THRESHOLD} coverage or more from the storm. */
    public static boolean coversAnyone(StormInstance storm, List<? extends Position> players) {
        for (Position player : players) {
            if (coverageAt(storm, player) >= RAIN_COVERAGE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** The storm's coverage at a position, horizontally; height is ignored. */
    public static float coverageAt(StormInstance storm, Position position) {
        return Coverage.coverage(storm.intensity(), storm.distanceTo(position.x(), position.z()), storm.radius());
    }

    /**
     * Writes a decision into the level's weather flags when they differ from it, or when the vanilla timers have
     * run down far enough to need re-arming. Does nothing when the bridge is disabled in the config or the dimension
     * has no sky light, where vanilla itself never changes the weather.
     */
    public static void apply(ServerLevel level, Decision decision) {
        if (!WeatherManager.vanillaWeatherBridge() || !level.dimensionType().hasSkyLight()) {
            return;
        }
        LevelData data = level.getLevelData();
        if (data.isRaining() != decision.raining() || data.isThundering() != decision.thundering() || rearmDue(data)) {
            level.setWeatherParameters(0, REARM_TICKS, decision.raining(), decision.thundering());
        }
    }

    /**
     * True when the level's rain timer has run below half of {@link #REARM_TICKS}. Vanilla decrements it every tick
     * while the weather cycle game rule is on and flips the rain flag when it reaches zero, so the bridge rewrites
     * the timers before that can happen. Level data that carries no timers never needs re-arming.
     */
    public static boolean rearmDue(LevelData data) {
        return data instanceof ServerLevelData serverData && rearmDue(serverData.getRainTime());
    }

    /** True when a rain timer of the given length is below half of {@link #REARM_TICKS}. */
    public static boolean rearmDue(int rainTimeTicks) {
        return rainTimeTicks < REARM_TICKS / 2;
    }
}
