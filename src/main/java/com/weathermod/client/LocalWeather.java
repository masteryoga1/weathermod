package com.weathermod.client;

import com.weathermod.WeatherMod;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * The weather at the camera, recomputed once per client tick from {@link ClientWeatherState#INSTANCE} after that
 * state has ticked and before the renderer, particle, fog and sound consumers read it. It is active only while a
 * level is loaded and the server has sent a climate, which is the signal that the mod manages this dimension. It is
 * cleared whenever the client level goes away (logging out, a dimension change or a reconfiguration), so the frames
 * before the first tick in a new level see it inactive. Game-bus events only touch {@link #INSTANCE}; tests build
 * their own instances and call {@link #update}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class LocalWeather {
    /** The local weather used by the running client. */
    public static final LocalWeather INSTANCE = new LocalWeather();

    /** Storms are read at the end of their tick, so the current position is the one that counts. */
    private static final float END_OF_TICK = 1.0F;
    private static final Function<ResourceLocation, WeatherType> NO_TYPES = id -> null;

    private LocalConditions conditions = LocalConditions.NONE;
    private boolean active;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    LocalWeather() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        double x;
        double y;
        double z;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isInitialized() && camera.getEntity() != null && camera.getEntity().level() == level) {
            // The camera is only reset on a disconnect, so right after a dimension change it still sits in the
            // previous level until the first frame there is drawn; the player has already been moved.
            Vec3 position = camera.getPosition();
            x = position.x;
            y = position.y;
            z = position.z;
        } else {
            LocalPlayer player = minecraft.player;
            if (player == null) {
                return;
            }
            x = player.getX();
            y = player.getEyeY();
            z = player.getZ();
        }
        ClientWeatherState state = ClientWeatherState.INSTANCE;
        INSTANCE.update(state, typeLookup(level), x, y, z, state.hasClimate());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.clear();
    }

    /** The client level is replaced on a dimension change and nulled on a reconfiguration without a logout. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            INSTANCE.clear();
        }
    }

    /** The conditions computed this tick; {@link LocalConditions#NONE} while inactive. */
    public LocalConditions conditions() {
        return conditions;
    }

    /** Whether a level is loaded and {@link ClientWeatherState#hasClimate()} holds, so the mod owns the weather. */
    public boolean active() {
        return active;
    }

    /** X of the point the conditions were computed at: the camera when initialised, otherwise the player's eyes. */
    public double cameraX() {
        return cameraX;
    }

    /** Y of the point the conditions were computed at. */
    public double cameraY() {
        return cameraY;
    }

    /** Z of the point the conditions were computed at. */
    public double cameraZ() {
        return cameraZ;
    }

    /**
     * Recomputes the conditions at a point from a weather state: the pure part of the tick, shared by the tick
     * handler and the tests. The temperature is the state's when it knows the climate and
     * {@link LocalWeatherMath#UNKNOWN_TEMPERATURE} otherwise; when {@code active} is false the conditions become
     * {@link LocalConditions#NONE} without consulting the storms.
     *
     * @param state  the storms and climate to read
     * @param types  resolves a type id to its type, or to {@code null} when the id is unknown
     * @param x      the viewer's x
     * @param y      the viewer's y
     * @param z      the viewer's z
     * @param active whether the mod manages the weather where the viewer is
     */
    public void update(ClientWeatherState state, Function<ResourceLocation, WeatherType> types, double x, double y,
                       double z, boolean active) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(types, "types");
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        this.active = active;
        if (!active) {
            this.conditions = LocalConditions.NONE;
            return;
        }
        float temperature = state.hasClimate() ? state.temperature() : LocalWeatherMath.UNKNOWN_TEMPERATURE;
        this.conditions = LocalWeatherMath.compute(state.storms(), types, x, z, END_OF_TICK, temperature);
    }

    /**
     * Forgets the conditions and the viewer position and becomes inactive, as when the client leaves a world or its
     * level is replaced.
     */
    public void clear() {
        this.conditions = LocalConditions.NONE;
        this.active = false;
        this.cameraX = 0.0D;
        this.cameraY = 0.0D;
        this.cameraZ = 0.0D;
    }

    /**
     * The type lookup for a level: the synced weather type registry when datapacks have loaded, otherwise a lookup
     * that knows no type, which makes every storm count for nothing until the registry arrives.
     */
    private static Function<ResourceLocation, WeatherType> typeLookup(ClientLevel level) {
        Optional<Registry<WeatherType>> registry = WeatherRegistries.optionalRegistry(level.registryAccess());
        if (registry.isEmpty()) {
            return NO_TYPES;
        }
        Registry<WeatherType> present = registry.get();
        return present::get;
    }
}
