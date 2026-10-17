package com.weathermod.client;

import com.weathermod.WeatherMod;
import com.weathermod.config.ClientConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Top-left text overlay listing the wind and every storm the client knows. It is drawn as a GUI layer above all
 * vanilla layers whenever the client config enables it or it has been toggled on at runtime through the key
 * mapping or {@code /weathermodc overlay}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherDebugOverlay {
    /** Id of the GUI layer registered with NeoForge. */
    public static final ResourceLocation LAYER_ID = WeatherMod.id("debug_overlay");
    /** Title drawn on the first line. */
    public static final String HEADER = "Weather Mod";
    /** Vertical distance between lines in GUI pixels. */
    public static final int LINE_HEIGHT = 10;
    /** Distance from the top-left corner of the screen in GUI pixels. */
    public static final int MARGIN = 4;
    /** Opaque white, drawn with a drop shadow. */
    public static final int TEXT_COLOR = 0xFFFFFFFF;

    private static volatile boolean toggled;

    private WeatherDebugOverlay() {
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, WeatherDebugOverlay::render);
    }

    /** Flips the runtime toggle and returns whether the overlay is now toggled on. */
    public static boolean toggle() {
        toggled = !toggled;
        return toggled;
    }

    /** Sets the runtime toggle. */
    public static void setToggled(boolean value) {
        toggled = value;
    }

    /** Whether the runtime toggle is on, regardless of the config setting. */
    public static boolean isToggled() {
        return toggled;
    }

    /** Whether the overlay is drawn: the runtime toggle or the {@code debugOverlay} client config option. */
    public static boolean isVisible() {
        return toggled || isEnabledByConfig();
    }

    /** Whether the client config asks for the overlay; false while the config has not been loaded yet. */
    public static boolean isEnabledByConfig() {
        return ClientConfig.SPEC.isLoaded() && ClientConfig.debugOverlay.get();
    }

    /** GUI layer callback; draws nothing unless the overlay is visible and a player is in a level. */
    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!isVisible()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.options.hideGui
            || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        List<String> lines = buildLines(ClientWeatherState.INSTANCE, player.getX(), player.getZ(), partialTick);
        Font font = minecraft.font;
        int y = MARGIN;
        for (String line : lines) {
            guiGraphics.drawString(font, line, MARGIN, y, TEXT_COLOR, true);
            y += LINE_HEIGHT;
        }
    }

    /**
     * Builds the overlay text for a viewer at the given horizontal position: the header, the wind, the storm count
     * and one line per storm with id, type, distance, intensity and age.
     */
    public static List<String> buildLines(ClientWeatherState state, double viewerX, double viewerZ,
                                          float partialTick) {
        List<String> lines = new ArrayList<>(3 + state.stormCount());
        lines.add(HEADER);
        lines.add(formatWind(state));
        lines.add("Storms: " + state.stormCount());
        for (ClientStorm storm : state.storms()) {
            lines.add(formatStorm(storm, viewerX, viewerZ, partialTick));
        }
        return lines;
    }

    /** One line describing the wind, or a placeholder until the first wind update has arrived. */
    public static String formatWind(ClientWeatherState state) {
        if (!state.hasWind()) {
            return "Wind: waiting for server";
        }
        return String.format(Locale.ROOT, "Wind: %.0f deg at %.1f b/s", state.windDirectionDegrees(),
            state.windSpeed());
    }

    /** One line describing a storm as seen from the given horizontal position. */
    public static String formatStorm(ClientStorm storm, double viewerX, double viewerZ, float partialTick) {
        double dx = storm.getX(partialTick) - viewerX;
        double dz = storm.getZ(partialTick) - viewerZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int percent = Math.round(storm.getIntensity(partialTick) * 100.0F);
        return String.format(Locale.ROOT, "#%d %s  %.0f m  %d%%  %d/%d s", storm.id(), storm.typeId().getPath(),
            distance, percent, storm.ageSeconds(), storm.maxAgeSeconds());
    }
}
