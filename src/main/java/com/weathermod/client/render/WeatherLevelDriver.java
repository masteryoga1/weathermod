package com.weathermod.client.render;

import com.weathermod.WeatherMod;
import com.weathermod.client.LocalConditions;
import com.weathermod.client.LocalWeather;
import com.weathermod.config.ClientConfig;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Drives the vanilla rain and thunder levels of the client level from the weather at the camera, which is what
 * shader packs and the vanilla sky, fog and light code read for darkening, wet surfaces and puddles, and keeps the
 * smoothed haze level that the fog and particle modules read. The three levels ramp at a fixed step per tick like
 * vanilla's own weather; the rain and thunder pair is written after the local weather has ticked and again at the
 * start of every frame, so a weather packet handled between ticks cannot undo it before the sky and fog read it.
 * The levels reset whenever the client level goes away (logging out, a dimension change or a server
 * reconfiguration), so the previous level's rain never carries into a freshly created level: the frames before the
 * first tick there write nothing, and the first active tick in the new level, while its loading screen is still up,
 * starts the three levels at the local conditions instead of ramping up from zero, so the weather is already there
 * when the world appears; once the world is visible every change ramps. Game-bus events only touch
 * {@link #INSTANCE}; tests build their own instances and call {@link #tick}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherLevelDriver {
    /** Change of the rain level per tick; 0.01 is the ramp vanilla's weather cycle uses. */
    public static final float RAIN_STEP = 0.01F;
    /** Change of the thunder level per tick, the same ramp as the rain. */
    public static final float THUNDER_STEP = 0.01F;
    /** Change of the haze level per tick, twice the rain ramp so fog banks arrive and lift a little faster. */
    public static final float HAZE_STEP = 0.02F;
    /** Value of {@code ClientConfig.rainLevelDriver} assumed while the client config has not been loaded. */
    public static final boolean DEFAULT_RAIN_LEVEL_DRIVER = true;
    /** The driver used by the running client. */
    public static final WeatherLevelDriver INSTANCE = new WeatherLevelDriver();

    private final SmoothedLevel rain = new SmoothedLevel(RAIN_STEP);
    private final SmoothedLevel thunder = new SmoothedLevel(THUNDER_STEP);
    private final SmoothedLevel haze = new SmoothedLevel(HAZE_STEP);
    private boolean driving;
    private boolean fresh = true;

    WeatherLevelDriver() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        if (!(minecraft.screen instanceof ReceivingLevelScreen)) {
            INSTANCE.worldShown();
        }
        LocalWeather local = LocalWeather.INSTANCE;
        INSTANCE.tick(local.conditions(), local.active(), isEnabledByConfig());
        if (INSTANCE.isDriving()) {
            INSTANCE.applyTo(level);
        }
    }

    /**
     * Fired once per frame after the packets and ticks of that frame and before the game renderer runs, so
     * everything that reads the levels during the frame (the sky, the fog, and the light map on the frames it is
     * rebuilt) sees the driven values, even on a frame where a weather packet arrived without a tick.
     */
    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (!INSTANCE.isDriving()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            INSTANCE.applyTo(level);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.reset();
    }

    /** The client level is replaced on a dimension change and nulled on a reconfiguration without a logout. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            INSTANCE.reset();
        }
    }

    /** Whether the client config asks for the driver; {@link #DEFAULT_RAIN_LEVEL_DRIVER} while it is not loaded. */
    public static boolean isEnabledByConfig() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.rainLevelDriver.get() : DEFAULT_RAIN_LEVEL_DRIVER;
    }

    /** The smoothed rain level after the last tick, in {@code [0, 1]}. */
    public float rain() {
        return rain.current();
    }

    /** The smoothed thunder level after the last tick, in {@code [0, 1]}. */
    public float thunder() {
        return thunder.current();
    }

    /** The smoothed haze level after the last tick, in {@code [0, 1]}. */
    public float haze() {
        return haze.current();
    }

    /** The rain level at a partial tick, interpolated between the previous and the current tick. */
    public float rain(float partialTick) {
        return rain.get(partialTick);
    }

    /** The thunder level at a partial tick, interpolated between the previous and the current tick. */
    public float thunder(float partialTick) {
        return thunder.get(partialTick);
    }

    /** The haze level at a partial tick, interpolated between the previous and the current tick. */
    public float haze(float partialTick) {
        return haze.get(partialTick);
    }

    /**
     * Whether the last tick decided to write the vanilla fields: true only while the mod manages the weather here
     * and {@code ClientConfig.rainLevelDriver} is on, so an unmanaged dimension and a player who turned the driver
     * off keep vanilla's own rain and thunder levels.
     */
    public boolean isDriving() {
        return driving;
    }

    /**
     * Advances the three levels one tick toward the conditions, each clamped into {@code [0, 1]}, or toward zero
     * while the mod is inactive here, and records whether the vanilla fields should be written this tick. The first
     * active tick after construction or {@link #reset} snaps the levels straight to the conditions instead of
     * stepping toward them, unless {@link #worldShown} has been called since: that first tick normally happens
     * behind the loading screen of a new level, where ramping from zero would fade the weather in after the world
     * has appeared, while a level the player already sees (a dimension the server starts managing mid-play, or a
     * loading screen that did not wait for the chunks) gets the ramp. A tick while inactive leaves the driver fresh.
     *
     * @param conditions    the weather at the camera this tick
     * @param active        whether the mod manages the weather where the camera is
     * @param driverEnabled whether the client config asks for the driver
     */
    public void tick(LocalConditions conditions, boolean active, boolean driverEnabled) {
        Objects.requireNonNull(conditions, "conditions");
        if (active && fresh) {
            rain.snap(clampUnit(conditions.rain()));
            thunder.snap(clampUnit(conditions.thunder()));
            haze.snap(clampUnit(conditions.haze()));
            fresh = false;
        } else if (active) {
            rain.approach(clampUnit(conditions.rain()));
            thunder.approach(clampUnit(conditions.thunder()));
            haze.approach(clampUnit(conditions.haze()));
        } else {
            rain.approach(0.0F);
            thunder.approach(0.0F);
            haze.approach(0.0F);
        }
        driving = active && driverEnabled;
    }

    /**
     * Writes the level's {@code oRainLevel}, {@code rainLevel}, {@code oThunderLevel} and {@code thunderLevel} from
     * the previous and current rain and thunder levels, so {@code getRainLevel(partialTick)} and
     * {@code getThunderLevel(partialTick)} interpolate smoothly. The only vanilla state the client side of the mod
     * writes.
     */
    public void applyTo(ClientLevel level) {
        Objects.requireNonNull(level, "level");
        level.oRainLevel = rain.previous();
        level.rainLevel = rain.current();
        level.oThunderLevel = thunder.previous();
        level.thunderLevel = thunder.current();
    }

    /**
     * Records that the player can see the world, so a first active tick ramps toward the conditions instead of
     * snapping to them. Called every tick the level's loading screen is not up; a no-op once the driver has had its
     * first active tick, and undone by {@link #reset}.
     */
    public void worldShown() {
        fresh = false;
    }

    /**
     * Snaps every level to zero, stops driving and makes the driver fresh again, as when the client leaves a world
     * or its level is replaced.
     */
    public void reset() {
        rain.reset();
        thunder.reset();
        haze.reset();
        driving = false;
        fresh = true;
    }

    private static float clampUnit(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
