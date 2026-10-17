package com.weathermod.server.family;

import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.climate.ClimateSample;
import com.weathermod.server.impact.DepositionSystem;

/**
 * The precipitation family: rain, sleet and snow storms. The vanilla rain switch is handled by the bridge for the
 * whole dimension; this system adds the ground effects, which in this milestone are snow layers and ice under snow.
 */
public final class PrecipitationSystem implements FamilySystem {
    /** Deposition runs every this many simulation ticks per storm, so the configured budget is per two ticks. */
    public static final int DEPOSITION_INTERVAL_TICKS = 2;

    /** What a type drops in a climate: its fixed mode, or the kind the temperature picks when the mode is automatic. */
    public static PrecipitationKind kindAt(WeatherType type, ClimateSample climate) {
        return PrecipitationKind.resolve(type.precipitation(), climate.temperature());
    }

    @Override
    public WeatherFamily family() {
        return WeatherFamily.PRECIPITATION;
    }

    /**
     * Every other tick, when deposition is enabled by the destruction mode and at least one deposition toggle,
     * spends the configured block budget on snow and ice under the storm. Types that do not precipitate and storms
     * that are still at zero intensity change nothing.
     */
    @Override
    public void tick(StormInstance storm, WeatherType type, FamilyContext context) {
        if (context.tick() % DEPOSITION_INTERVAL_TICKS != 0) {
            return;
        }
        if (!type.precipitates() || storm.intensity() <= 0.0F) {
            return;
        }
        if (!WeatherManager.depositionEnabled()) {
            return;
        }
        DepositionSystem.deposit(storm, type, context, WeatherManager.depositionBlocksPerTick());
    }
}
