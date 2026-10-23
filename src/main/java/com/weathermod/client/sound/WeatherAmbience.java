package com.weathermod.client.sound;

import com.weathermod.WeatherMod;
import com.weathermod.client.ClientWeatherState;
import com.weathermod.client.LocalConditions;
import com.weathermod.client.LocalWeather;
import com.weathermod.client.render.WeatherLevelDriver;
import com.weathermod.config.ClientConfig;
import com.weathermod.core.WeatherSounds;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Plays the five ambience loops from the weather at the camera: each client tick it turns the driver's rain level,
 * the local precipitation kind, a roof check and the wind into loop targets, starts the loops that should be heard
 * and eases the live ones toward their targets, so a storm edge fades in and out instead of switching. Game-bus
 * events only touch {@link #INSTANCE}.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class WeatherAmbience {
    /** Value of {@code ClientConfig.soundVolume} assumed while the client config has not been loaded. */
    public static final double DEFAULT_SOUND_VOLUME = 1.0D;
    /** Ticks to wait before offering a loop to the sound engine again after it refused to start one. */
    public static final int RETRY_TICKS = 20;
    /** A block this far above the camera's eye or higher counts as a roof. */
    public static final double ROOF_CLEARANCE = 1.0D;
    /** The ambience used by the running client. */
    public static final WeatherAmbience INSTANCE = new WeatherAmbience();

    private final Channel rainLight = new Channel(WeatherSounds.AMBIENT_RAIN_LIGHT);
    private final Channel rainHeavy = new Channel(WeatherSounds.AMBIENT_RAIN_HEAVY);
    private final Channel rainRoof = new Channel(WeatherSounds.AMBIENT_RAIN_ROOF);
    private final Channel snow = new Channel(WeatherSounds.AMBIENT_SNOW);
    private final Channel wind = new Channel(WeatherSounds.AMBIENT_WIND);
    private final Channel[] channels = {rainLight, rainHeavy, rainRoof, snow, wind};

    WeatherAmbience() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()) {
            return;
        }
        INSTANCE.tick(minecraft);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.stopAll();
    }

    /** The configured weather sound volume; {@link #DEFAULT_SOUND_VOLUME} while the client config is not loaded. */
    public static double volumeConfig() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.soundVolume.get() : DEFAULT_SOUND_VOLUME;
    }

    /**
     * Whether a motion-blocking block (or fluid) sits above a point: the heightmap column at the point reaches more
     * than {@link #ROOF_CLEARANCE} above it. Unloaded chunks report their minimum height, so they count as open sky.
     */
    public static boolean isRoofed(ClientLevel level, double x, double y, double z) {
        Objects.requireNonNull(level, "level");
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        return height > y + ROOF_CLEARANCE;
    }

    /**
     * The loop targets for this tick: {@link AmbienceMath.Targets#SILENT} while no level is loaded, the mod does not
     * manage the weather here or the master or weather sound category is muted ({@link #tick} stops the loops
     * outright in that case rather than fading them); otherwise {@link AmbienceMath#targets} of the driver's rain
     * level, the local kind, the roof check at the camera and the wind.
     */
    public static AmbienceMath.Targets computeTargets(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        ClientLevel level = minecraft.level;
        LocalWeather local = LocalWeather.INSTANCE;
        if (level == null || !local.active() || isMuted(minecraft)) {
            return AmbienceMath.Targets.SILENT;
        }
        LocalConditions conditions = local.conditions();
        boolean roofed = isRoofed(level, local.cameraX(), local.cameraY(), local.cameraZ());
        ClientWeatherState state = ClientWeatherState.INSTANCE;
        float windSpeed = state.hasWind() ? state.windSpeed() : 0.0F;
        float gustSpeed = state.hasWind() ? state.windGustSpeed() : 0.0F;
        return AmbienceMath.targets(WeatherLevelDriver.INSTANCE.rain(), conditions.kind(), roofed, windSpeed,
            gustSpeed, volumeConfig());
    }

    /**
     * One ambience tick: computes the targets and, for each channel, starts a loop when its target is audible and
     * none is live, drops loops the engine has finished with, and hands live loops their new target. While the
     * master or weather sound category is muted every live loop is stopped at once instead of being faded: the
     * engine drops a muted loop's channel but keeps reporting the instance as active, so a loop left fading would
     * stay channel-less and inaudible for the rest of the storm if the category came back within the fade. A fresh
     * loop is started once the category is audible again.
     */
    public void tick(Minecraft minecraft) {
        Objects.requireNonNull(minecraft, "minecraft");
        SoundManager soundManager = minecraft.getSoundManager();
        if (isMuted(minecraft)) {
            stopAll(soundManager);
            return;
        }
        AmbienceMath.Targets targets = computeTargets(minecraft);
        rainLight.update(soundManager, targets.rainLight(), 1.0F);
        rainHeavy.update(soundManager, targets.rainHeavy(), 1.0F);
        rainRoof.update(soundManager, targets.rainRoof(), 1.0F);
        snow.update(soundManager, targets.snow(), 1.0F);
        wind.update(soundManager, targets.wind(), targets.windPitch());
    }

    /** Stops every live loop at once and forgets it, as when the client leaves a world. */
    public void stopAll() {
        stopAll(Minecraft.getInstance().getSoundManager());
    }

    private void stopAll(SoundManager soundManager) {
        for (Channel channel : channels) {
            channel.stop(soundManager);
        }
    }

    private static boolean isMuted(Minecraft minecraft) {
        return minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
            || minecraft.options.getSoundSourceVolume(SoundSource.WEATHER) <= 0.0F;
    }

    /** One loop slot: the sound event it plays, the live instance if any, and a back-off after a refused start. */
    private static final class Channel {
        private final DeferredHolder<SoundEvent, SoundEvent> event;
        private WeatherLoopSound loop;
        private int retryCooldown;

        Channel(DeferredHolder<SoundEvent, SoundEvent> event) {
            this.event = event;
        }

        void update(SoundManager soundManager, float volume, float pitch) {
            if (loop != null && (loop.isStopped() || !soundManager.isActive(loop))) {
                loop = null;
            }
            if (loop != null) {
                loop.setTarget(volume, pitch);
                return;
            }
            if (volume <= AmbienceMath.SILENCE) {
                retryCooldown = 0;
                return;
            }
            if (retryCooldown > 0) {
                retryCooldown--;
                return;
            }
            WeatherLoopSound fresh = new WeatherLoopSound(event.get());
            fresh.setTarget(volume, pitch);
            soundManager.play(fresh);
            if (soundManager.isActive(fresh)) {
                loop = fresh;
            } else {
                retryCooldown = RETRY_TICKS;
            }
        }

        void stop(SoundManager soundManager) {
            if (loop != null) {
                loop.stopNow();
                soundManager.stop(loop);
                loop = null;
            }
            retryCooldown = 0;
        }
    }
}
