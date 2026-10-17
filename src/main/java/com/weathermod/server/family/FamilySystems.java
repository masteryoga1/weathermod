package com.weathermod.server.family;

import com.weathermod.core.WeatherFamily;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of {@link FamilySystem}s: one system per supported family. Families without a system are still
 * simulated as moving storms but have no server-side effect, and the director never spawns them naturally.
 */
public final class FamilySystems {
    private static final Map<WeatherFamily, FamilySystem> SYSTEMS;
    private static final Set<WeatherFamily> SUPPORTED;

    static {
        EnumMap<WeatherFamily, FamilySystem> systems = new EnumMap<>(WeatherFamily.class);
        register(systems, new PrecipitationSystem());
        register(systems, new ObscurantSystem());
        SYSTEMS = Collections.unmodifiableMap(systems);
        SUPPORTED = Collections.unmodifiableSet(EnumSet.copyOf(systems.keySet()));
    }

    private FamilySystems() {
    }

    private static void register(Map<WeatherFamily, FamilySystem> systems, FamilySystem system) {
        FamilySystem previous = systems.put(system.family(), system);
        if (previous != null) {
            throw new IllegalStateException("Two family systems claim " + system.family() + ": "
                + previous.getClass().getName() + " and " + system.getClass().getName());
        }
    }

    /** The system that simulates a family, or empty when the family has no server behaviour yet. */
    public static Optional<FamilySystem> forFamily(WeatherFamily family) {
        return family == null ? Optional.empty() : Optional.ofNullable(SYSTEMS.get(family));
    }

    /** The families with a system, in enum order: precipitation and obscurant in this milestone. */
    public static Set<WeatherFamily> supported() {
        return SUPPORTED;
    }

    /** True when {@link #forFamily(WeatherFamily)} would find a system for the family. */
    public static boolean isSupported(WeatherFamily family) {
        return family != null && SYSTEMS.containsKey(family);
    }
}
