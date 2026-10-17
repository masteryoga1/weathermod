package com.weathermod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.weathermod.WeatherMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The mod's key mappings. The overlay key is unbound by default; once the player binds it in the controls screen,
 * each press flips the debug overlay and confirms the new state on the action bar.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherKeyMappings {
    /** Translation key of the controls-screen category that groups the mod's keys. */
    public static final String CATEGORY = "key.categories.weathermod";
    /** Translation key of the overlay toggle. */
    public static final String TOGGLE_OVERLAY_KEY = "key.weathermod.toggle_overlay";

    /** Toggles the weather debug overlay. Unbound until the player picks a key. */
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping(TOGGLE_OVERLAY_KEY, KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);

    private WeatherKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_OVERLAY);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, WeatherKeyMappings::onClientTick);
    }

    /** Game-bus tick handler: consumes every queued press of the overlay key and applies the toggles. */
    public static void onClientTick(ClientTickEvent.Post event) {
        boolean changed = false;
        while (TOGGLE_OVERLAY.consumeClick()) {
            WeatherDebugOverlay.toggle();
            changed = true;
        }
        if (changed) {
            announceOverlayState();
        }
    }

    /** Tells the player on the action bar whether the overlay is now shown or hidden. */
    public static void announceOverlayState() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(WeatherDebugOverlay.isToggled()
                ? ClientCommands.OVERLAY_ON : ClientCommands.OVERLAY_OFF), true);
        }
    }
}
