package com.weathermod.core;

import com.weathermod.WeatherMod;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * The weather type datapack registry. Entries load from {@code data/<namespace>/weathermod/weather_type/<name>.json}
 * and are synced to clients, so both sides can resolve a storm's type by id.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherRegistries {
    /** Registry key of the weather type datapack registry, {@code weathermod:weather_type}. */
    public static final ResourceKey<Registry<WeatherType>> WEATHER_TYPE =
        ResourceKey.createRegistryKey(WeatherMod.id("weather_type"));

    private WeatherRegistries() {
    }

    @SubscribeEvent
    public static void onNewDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(WEATHER_TYPE, WeatherType.CODEC, WeatherType.CODEC);
        WeatherMod.LOGGER.debug("Registered datapack registry {}", WEATHER_TYPE.location());
    }

    /** Creates the registry key for a weather type id. */
    public static ResourceKey<WeatherType> key(ResourceLocation id) {
        return ResourceKey.create(WEATHER_TYPE, id);
    }

    /** Creates the registry key for a weather type in the mod's own namespace. */
    public static ResourceKey<WeatherType> key(String path) {
        return key(WeatherMod.id(path));
    }

    /**
     * Returns the loaded weather type registry.
     *
     * @throws IllegalStateException when the registry is not present, which only happens before datapacks load
     */
    public static Registry<WeatherType> registry(RegistryAccess access) {
        return access.registryOrThrow(WEATHER_TYPE);
    }

    /** Returns the loaded weather type registry, or empty before datapacks have loaded. */
    public static Optional<Registry<WeatherType>> optionalRegistry(RegistryAccess access) {
        return access.registry(WEATHER_TYPE);
    }

    /** Resolves a weather type id to its holder, or empty when the id is unknown. */
    public static Optional<Holder.Reference<WeatherType>> holder(RegistryAccess access, ResourceLocation id) {
        return access.registry(WEATHER_TYPE).flatMap(registry -> registry.getHolder(id));
    }

    /** Resolves a weather type key to its holder, or empty when the key is unknown. */
    public static Optional<Holder.Reference<WeatherType>> holder(RegistryAccess access, ResourceKey<WeatherType> key) {
        return access.registry(WEATHER_TYPE).flatMap(registry -> registry.getHolder(key));
    }

    /** Streams every enabled weather type with a positive spawn weight, in registry order. */
    public static Stream<Holder.Reference<WeatherType>> naturalTypes(RegistryAccess access) {
        return access.registry(WEATHER_TYPE)
            .map(Registry::holders)
            .orElseGet(Stream::empty)
            .filter(holder -> holder.value().spawnsNaturally());
    }
}
