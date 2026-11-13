package com.weathermod.core;

import com.weathermod.WeatherMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * Registry keys of the damage types shipped with the mod. The entries themselves are datapack JSON under
 * {@code data/weathermod/damage_type/}; a damage source is made from a key with the level's damage sources.
 */
public final class WeatherDamageTypes {
    /** Damage from hailstones, {@code data/weathermod/damage_type/hail.json}. */
    public static final ResourceKey<DamageType> HAIL =
        ResourceKey.create(Registries.DAMAGE_TYPE, WeatherMod.id("hail"));

    private WeatherDamageTypes() {
    }
}
