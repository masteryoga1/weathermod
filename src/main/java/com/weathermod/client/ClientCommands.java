package com.weathermod.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.weathermod.WeatherMod;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-only commands under {@code /weathermodc}: {@code overlay [on|off]} toggles the debug overlay and
 * {@code storms} prints the wind and every storm the client knows into the chat. They run entirely on the client
 * and never reach the server.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientCommands {
    /** Root literal of the client command tree. */
    public static final String ROOT = "weathermodc";

    /** Feedback: the overlay is now shown. */
    public static final String OVERLAY_ON = "command.weathermod.client.overlay.on";
    /** Feedback: the overlay is now hidden. */
    public static final String OVERLAY_OFF = "command.weathermod.client.overlay.off";
    /** Feedback: the overlay is hidden at runtime but still forced on by the client config. */
    public static final String OVERLAY_CONFIG_FORCED = "command.weathermod.client.overlay.config_forced";
    /** Feedback: the client knows no storms. */
    public static final String STORMS_NONE = "command.weathermod.client.storms.none";
    /** Feedback header before the storm lines; argument: storm count. */
    public static final String STORMS_HEADER = "command.weathermod.client.storms.header";
    /** Feedback line per storm; arguments: id, type, distance, intensity percent, age seconds, max age seconds. */
    public static final String STORMS_ENTRY = "command.weathermod.client.storms.entry";
    /** Feedback line for the wind; arguments: direction degrees, speed. */
    public static final String STORMS_WIND = "command.weathermod.client.storms.wind";
    /** Feedback line for the wind before the first wind update. */
    public static final String STORMS_WIND_UNKNOWN = "command.weathermod.client.storms.wind_unknown";

    private ClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    /** Registers the {@code /weathermodc} tree on a dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
            .then(Commands.literal("overlay")
                .executes(context -> setOverlay(context.getSource(), !WeatherDebugOverlay.isToggled()))
                .then(Commands.literal("on")
                    .executes(context -> setOverlay(context.getSource(), true)))
                .then(Commands.literal("off")
                    .executes(context -> setOverlay(context.getSource(), false))))
            .then(Commands.literal("storms")
                .executes(context -> listStorms(context.getSource()))));
    }

    /** Sets the runtime overlay toggle and reports the resulting state. */
    public static int setOverlay(CommandSourceStack source, boolean shown) {
        WeatherDebugOverlay.setToggled(shown);
        if (shown) {
            source.sendSuccess(() -> Component.translatable(OVERLAY_ON), false);
        } else if (WeatherDebugOverlay.isEnabledByConfig()) {
            source.sendSuccess(() -> Component.translatable(OVERLAY_CONFIG_FORCED), false);
        } else {
            source.sendSuccess(() -> Component.translatable(OVERLAY_OFF), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Prints the wind and one line per known storm, measured from the command source's position. */
    public static int listStorms(CommandSourceStack source) {
        ClientWeatherState state = ClientWeatherState.INSTANCE;
        Vec3 position = source.getPosition();

        if (state.hasWind()) {
            String direction = String.format(Locale.ROOT, "%.0f", state.windDirectionDegrees());
            String speed = String.format(Locale.ROOT, "%.1f", state.windSpeed());
            source.sendSuccess(() -> Component.translatable(STORMS_WIND, direction, speed), false);
        } else {
            source.sendSuccess(() -> Component.translatable(STORMS_WIND_UNKNOWN), false);
        }

        if (state.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(STORMS_NONE), false);
            return 0;
        }

        int count = state.stormCount();
        source.sendSuccess(() -> Component.translatable(STORMS_HEADER, count), false);
        for (ClientStorm storm : state.storms()) {
            String distance = String.format(Locale.ROOT, "%.0f", storm.distanceTo(position.x, position.z));
            int percent = Math.round(storm.intensity() * 100.0F);
            source.sendSuccess(() -> Component.translatable(STORMS_ENTRY, storm.id(), storm.typeId().toString(),
                distance, percent, storm.ageSeconds(), storm.maxAgeSeconds()), false);
        }
        return count;
    }
}
