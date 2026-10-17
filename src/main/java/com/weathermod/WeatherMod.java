package com.weathermod;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point. Wires the registries, config and network layer together; everything else
 * lives in its own package and talks through those.
 */
@Mod(WeatherMod.MOD_ID)
public final class WeatherMod {
    public static final String MOD_ID = "weathermod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WeatherMod(IEventBus modBus, ModContainer container) {
        LOGGER.info("Weather Mod {} loading", container.getModInfo().getVersion());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
