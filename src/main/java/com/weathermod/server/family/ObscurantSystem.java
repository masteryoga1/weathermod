package com.weathermod.server.family;

import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherType;
import com.weathermod.server.StormInstance;

/**
 * The obscurant family: fog, haze and other storms that only reduce visibility. Obscurants have no effect on the
 * world in this milestone; the client draws them from the storm's visuals, and the server only moves and ages them.
 */
public final class ObscurantSystem implements FamilySystem {
    @Override
    public WeatherFamily family() {
        return WeatherFamily.OBSCURANT;
    }

    /** Obscurants leave the world untouched: no blocks, entities or vanilla weather flags change under them. */
    @Override
    public void tick(StormInstance storm, WeatherType type, FamilyContext context) {
        // The visual part of an obscurant lives on the client; there is nothing for the server to change.
    }
}
