package com.weathermod.server.family;

import com.weathermod.core.Coverage;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;

/**
 * The server behaviour of one {@link WeatherFamily}: what a storm of that family does to the world every tick.
 * Movement, ageing and syncing stay with the simulator; a system only adds the family's own effects.
 */
public interface FamilySystem {
    /** The family this system simulates. Exactly one system claims each supported family. */
    WeatherFamily family();

    /**
     * Runs the family's effects for one storm. Called every server tick while the storm lives, after the storm has
     * moved and aged, with the type the storm's id resolved to.
     */
    void tick(StormInstance storm, WeatherType type, FamilyContext context);

    /** How strongly the storm affects a horizontal position, see {@link Coverage#coverage(float, double, float)}. */
    default float coverage(StormInstance storm, double x, double z) {
        return Coverage.coverage(storm.intensity(), storm.distanceTo(x, z), storm.radius());
    }
}
