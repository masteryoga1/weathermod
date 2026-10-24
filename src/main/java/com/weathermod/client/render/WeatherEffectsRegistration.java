package com.weathermod.client.render;

import com.weathermod.WeatherMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

/**
 * Installs {@link WeatherDimensionEffects} as the overworld's dimension effects and, on login, warns once when a
 * level that uses the overworld effects ended up with another mod's instance instead, since only one mod can own
 * the rain hook per dimension.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherEffectsRegistration {
    private static boolean warnedAboutReplacement;

    private WeatherEffectsRegistration() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterDimensionSpecialEffectsEvent event) {
        event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new WeatherDimensionEffects());
        WeatherMod.LOGGER.debug("Registered the weather dimension effects for {}", BuiltinDimensionTypes.OVERWORLD_EFFECTS);
    }

    /** Whether the level's dimension effects are the mod's, so its rain hooks reach the mod's renderer. */
    public static boolean isInstalled(ClientLevel level) {
        return level != null && level.effects() instanceof WeatherDimensionEffects;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (warnedAboutReplacement) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || isInstalled(level)) {
            return;
        }
        if (!BuiltinDimensionTypes.OVERWORLD_EFFECTS.equals(level.dimensionType().effectsLocation())) {
            return;
        }
        warnedAboutReplacement = true;
        WeatherMod.LOGGER.warn("another mod replaced the overworld dimension effects; vanilla draws the rain at the"
            + " driven rain level");
    }
}
