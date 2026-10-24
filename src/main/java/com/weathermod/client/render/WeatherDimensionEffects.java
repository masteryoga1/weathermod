package com.weathermod.client.render;

import com.weathermod.client.LocalWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;

/**
 * The overworld's dimension effects with the mod's precipitation renderer and splash ticker in the two rain hooks;
 * sky, clouds, fog colour and the light map stay vanilla. With {@code ClientConfig.precipitationRenderer} off both
 * hooks return false and vanilla draws its own rain at the driven rain level. The effects are installed for every
 * level with the overworld effects, including dimensions the server does not manage; there
 * {@link LocalWeather#active()} is false, the driver leaves vanilla's own rain level alone, and both hooks return
 * false as well so vanilla's rain, splashes and rain sounds stay as they are without the mod.
 */
public final class WeatherDimensionEffects extends DimensionSpecialEffects.OverworldEffects {
    /** Creates the overworld effects with the mod's rain hooks. */
    public WeatherDimensionEffects() {
        super();
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture,
                                     double camX, double camY, double camZ) {
        if (!LocalWeather.INSTANCE.active() || !PrecipitationRenderer.isEnabledByConfig()) {
            return false;
        }
        return PrecipitationRenderer.render(level, ticks, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        if (!LocalWeather.INSTANCE.active() || !PrecipitationRenderer.isEnabledByConfig()) {
            return false;
        }
        return RainSplashes.tick(level, ticks, camera);
    }
}
