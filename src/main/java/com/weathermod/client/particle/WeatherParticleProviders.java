package com.weathermod.client.particle;

import com.weathermod.WeatherMod;
import com.weathermod.core.WeatherParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * Binds each of the mod's particle types to the provider that draws it, with the sprite sets the particle engine
 * loads from {@code assets/weathermod/particles/<name>.json}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherParticleProviders {
    private WeatherParticleProviders() {
    }

    @SubscribeEvent
    public static void onRegisterProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(WeatherParticles.HAZE.get(), HazeParticle.Provider::new);
        event.registerSpriteSet(WeatherParticles.WIND_DUST.get(), WindDustParticle.Provider::new);
        event.registerSpriteSet(WeatherParticles.WIND_LEAF.get(), WindLeafParticle.Provider::new);
    }
}
