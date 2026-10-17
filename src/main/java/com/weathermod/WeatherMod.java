package com.weathermod;

import com.mojang.logging.LogUtils;
import com.weathermod.config.ConfigSetup;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point. Registers the config files with the mod container; the registry, network, simulation,
 * command and client layers each register themselves through their own {@code @EventBusSubscriber} class
 * and talk to each other only through the registry and the payloads.
 */
@Mod(WeatherMod.MOD_ID)
public final class WeatherMod {
    public static final String MOD_ID = "weathermod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WeatherMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("Weather Mod {} loading", container.getModInfo().getVersion());
        ConfigSetup.register(container);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
