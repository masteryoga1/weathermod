package com.weathermod.client.render;

import com.weathermod.WeatherMod;
import com.weathermod.entity.WeatherEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Binds each of the mod's entity types to the renderer that draws it. Every registered entity type needs one on
 * the client.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherEntityRenderers {
    private WeatherEntityRenderers() {
    }

    /** Registers the flying block renderer. */
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WeatherEntities.FLYING_BLOCK.get(), FlyingBlockRenderer::new);
    }
}
