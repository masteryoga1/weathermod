package com.weathermod.gametest;

import com.weathermod.WeatherMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Hands the mod's game tests to NeoForge. NeoForge asks for game tests only outside production and only where
 * game tests are switched on at all, and this class answers only when the mod's own namespace is among the enabled
 * ones, which the Gradle runs set through the {@value #NAMESPACES_PROPERTY} system property. A normal game
 * therefore never loads {@link WeatherGameTests} or anything it uses.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class GameTestRegistration {
    /** The system property NeoForge reads the enabled game test namespaces from, separated by commas. */
    public static final String NAMESPACES_PROPERTY = "neoforge.enabledGameTestNamespaces";

    private GameTestRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterGameTests(RegisterGameTestsEvent event) {
        if (namespaceEnabled(System.getProperty(NAMESPACES_PROPERTY))) {
            LogWatch.install();
            event.register(WeatherGameTests.class);
        }
    }

    /** Whether a comma-separated list of namespaces, as the property holds it, names the mod's namespace. */
    static boolean namespaceEnabled(@Nullable String namespaces) {
        if (namespaces == null) {
            return false;
        }
        for (String namespace : namespaces.split(",")) {
            if (namespace.trim().equals(WeatherMod.MOD_ID)) {
                return true;
            }
        }
        return false;
    }
}
