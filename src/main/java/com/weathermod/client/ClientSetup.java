package com.weathermod.client;

import com.weathermod.WeatherMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client start-up: registers NeoForge's configuration screen for the mod's three config files and reports that
 * the client side is ready. Everything else on the client registers itself through its own event subscriber.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModContainer container = modContainer();
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        WeatherMod.LOGGER.info("Weather Mod client ready: configuration screen registered, debug overlay {}",
            WeatherDebugOverlay.isVisible() ? "on" : "off");
    }

    /**
     * The mod's own container: the one whose mod bus is currently dispatching when called from a mod bus event,
     * otherwise looked up by mod id.
     */
    public static ModContainer modContainer() {
        ModContainer active = ModLoadingContext.get().getActiveContainer();
        if (active != null && WeatherMod.MOD_ID.equals(active.getModId())) {
            return active;
        }
        return ModList.get().getModContainerById(WeatherMod.MOD_ID)
            .orElseThrow(() -> new IllegalStateException("Mod container for " + WeatherMod.MOD_ID + " not found"));
    }
}
