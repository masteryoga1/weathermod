package com.weathermod.core;

import com.weathermod.WeatherMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's sound events: the five ambience loops the client plays under rain, snow and wind. Common code so both
 * sides agree on the ids; the client decides when each loop plays and how loud.
 */
public final class WeatherSounds {
    /** Every sound event of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
        DeferredRegister.create(Registries.SOUND_EVENT, WeatherMod.MOD_ID);

    /** Light rain heard in the open: hiss and sparse droplets. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_RAIN_LIGHT = register("ambient.rain_light");
    /** Heavy rain heard in the open: dense roar and many droplets. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_RAIN_HEAVY = register("ambient.rain_heavy");
    /** Rain heard from under a roof: muffled hiss and drips. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_RAIN_ROOF = register("ambient.rain_roof");
    /** The quiet breath of falling snow. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_SNOW = register("ambient.snow");
    /** Wind swelling and whistling; pitched up slightly while a gust blows. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_WIND = register("ambient.wind");

    private WeatherSounds() {
    }

    /** Registers one variable-range sound event whose id and sounds.json key are both {@code weathermod:<name>}. */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(WeatherMod.id(name)));
    }

    /** Attaches the sound event register to the mod bus; called once from the mod constructor. */
    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
