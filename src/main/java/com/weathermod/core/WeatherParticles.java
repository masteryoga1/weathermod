package com.weathermod.core;

import com.weathermod.WeatherMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's particle types: the haze puffs that make fog and overcast visible and the dust and leaves the wind
 * raises. Common code so both sides agree on the ids; the client registers the providers that draw them.
 */
public final class WeatherParticles {
    /** Every particle type of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, WeatherMod.MOD_ID);

    /** A large, slow, translucent puff that shows fog and overcast as drifting shapes. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> HAZE =
        PARTICLE_TYPES.register("haze", () -> new SimpleParticleType(false));
    /** A puff of dust a strong wind kicks up from open ground, tinted against the ground it rose from. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WIND_DUST =
        PARTICLE_TYPES.register("wind_dust", () -> new SimpleParticleType(false));
    /** A leaf the wind tears off a tree canopy, tinted like the leaf block it came off. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WIND_LEAF =
        PARTICLE_TYPES.register("wind_leaf", () -> new SimpleParticleType(false));

    private WeatherParticles() {
    }

    /** Attaches the particle type register to the mod bus; called once from the mod constructor. */
    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
