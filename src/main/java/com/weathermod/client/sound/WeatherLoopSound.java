package com.weathermod.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One looping ambience channel: played relative to the listener without attenuation like vanilla's biome loops,
 * with its volume and pitch eased toward a target every tick and stopped once it has stayed silent for a while. It
 * starts silent and fades in, so it declares that it may start at zero volume.
 */
public final class WeatherLoopSound extends AbstractTickableSoundInstance {
    /** Lowest pitch the sound engine accepts. */
    private static final float MIN_PITCH = 0.5F;
    /** Highest pitch the sound engine accepts. */
    private static final float MAX_PITCH = 2.0F;

    private float targetVolume;
    private float targetPitch = 1.0F;
    private int silentTicks;
    private boolean ticked;

    /** Creates a silent, looping, listener-relative loop of the event in the weather category. */
    public WeatherLoopSound(SoundEvent event) {
        super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
        this.volume = 0.0F;
        this.pitch = 1.0F;
    }

    /**
     * Sets the volume and pitch the loop eases toward. The volume is clamped to {@code [0, 1]} and the pitch to the
     * engine's range; before the first tick the pitch is taken immediately so a new loop does not glide in from
     * pitch 1.
     */
    public void setTarget(float volume, float pitch) {
        this.targetVolume = clampUnit(volume);
        this.targetPitch = clampPitch(pitch);
        if (!ticked) {
            this.pitch = this.targetPitch;
        }
    }

    /** The volume the loop is easing toward. */
    public float targetVolume() {
        return targetVolume;
    }

    /** Ends the loop now: the sound engine drops it on its next tick. */
    public void stopNow() {
        targetVolume = 0.0F;
        stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (isStopped()) {
            return;
        }
        ticked = true;
        volume = AmbienceMath.approach(volume, targetVolume, AmbienceMath.VOLUME_STEP);
        pitch = AmbienceMath.approach(pitch, targetPitch, AmbienceMath.PITCH_STEP);
        if (targetVolume <= AmbienceMath.SILENCE && volume <= AmbienceMath.SILENCE) {
            silentTicks++;
            if (silentTicks >= AmbienceMath.STOP_AFTER_TICKS) {
                stop();
            }
        } else {
            silentTicks = 0;
        }
    }

    private static float clampUnit(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }

    private static float clampPitch(float value) {
        if (Float.isNaN(value)) {
            return 1.0F;
        }
        if (value < MIN_PITCH) {
            return MIN_PITCH;
        }
        return value > MAX_PITCH ? MAX_PITCH : value;
    }
}
