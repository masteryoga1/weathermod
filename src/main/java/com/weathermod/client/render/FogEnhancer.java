package com.weathermod.client.render;

import com.weathermod.WeatherMod;
import com.weathermod.client.LocalWeather;
import com.weathermod.compat.iris.IrisCompat;
import com.weathermod.config.ClientConfig;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Shortens the fog and greys its colour under the driver's haze, as an enhancement on top of the haze particles
 * that carry the fog for shader packs which ignore the fog events. It touches only the plain-air fog of a level the
 * mod manages, composes with the planes and colour other listeners left on the event, and never changes the fog
 * shape; without haze it leaves the events alone.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class FogEnhancer {
    /** Value of {@code ClientConfig.hazeStrength} assumed while the client config has not been loaded. */
    public static final double DEFAULT_HAZE_STRENGTH = 1.0D;

    private FogEnhancer() {
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.NONE) {
            return;
        }
        FogRenderer.FogMode mode = event.getMode();
        if (mode != FogRenderer.FogMode.FOG_TERRAIN && mode != FogRenderer.FogMode.FOG_SKY) {
            return;
        }
        float haze = currentHaze((float) event.getPartialTick());
        if (haze <= HazeMath.MIN_HAZE) {
            return;
        }
        float newFar = HazeMath.farPlane(event.getFarPlaneDistance(), haze);
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(HazeMath.nearPlane(event.getNearPlaneDistance(), newFar));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        // The same gate the distance event applies: water, lava and powder snow fog keep their own colour.
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float haze = currentHaze((float) event.getPartialTick());
        if (haze <= HazeMath.MIN_HAZE) {
            return;
        }
        float red = event.getRed();
        float green = event.getGreen();
        float blue = event.getBlue();
        float grey = HazeMath.greyTarget(red, green, blue);
        event.setRed(HazeMath.blendChannel(red, grey, haze));
        event.setGreen(HazeMath.blendChannel(green, grey, haze));
        event.setBlue(HazeMath.blendChannel(blue, grey, haze));
    }

    /**
     * The haze the fog shows at a partial tick: zero while the mod does not manage the weather here, otherwise the
     * driver's smoothed haze through {@link HazeMath#effectiveHaze} with the configured strength and the shader pack
     * state.
     */
    public static float currentHaze(float partialTick) {
        if (!LocalWeather.INSTANCE.active()) {
            return 0.0F;
        }
        float haze = WeatherLevelDriver.INSTANCE.haze(partialTick);
        if (haze <= 0.0F) {
            return 0.0F;
        }
        return HazeMath.effectiveHaze(haze, hazeStrength(), IrisCompat.isShaderPackInUse());
    }

    /** The configured haze strength multiplier; {@link #DEFAULT_HAZE_STRENGTH} while the config is not loaded. */
    public static double hazeStrength() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.hazeStrength.get() : DEFAULT_HAZE_STRENGTH;
    }
}
