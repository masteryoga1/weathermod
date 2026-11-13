package com.weathermod.client.particle;

import com.weathermod.compat.iris.IrisCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * A big, slow, translucent grey puff: the geometric carrier of fog and overcast. It fades in, drifts at exactly the
 * velocity the emitter gave it (the wind's share plus a slow rise), fades out before it dies and is lit by the level
 * like any other particle, so it darkens under storm clouds and glows near torches. Its opacity is split between
 * the sprite and the vertex alpha: without a shader pack the vertex alpha carries part of it, under a pack the
 * vertex alpha runs up to 1 so a pack that darkens particles by their vertex alpha still lights the puff fully at
 * its peak. Such a pack still shows the puff darker than that while it fades in and out, about a quarter of its
 * life; the fade is kept because a puff this large that grew and shrank instead would draw the eye more than a
 * faint veil does.
 */
public final class HazeParticle extends TextureSheetParticle {
    /** Shortest life in ticks. */
    public static final int MIN_LIFETIME = 160;
    /** Longest life in ticks. */
    public static final int MAX_LIFETIME = 280;
    /** Smallest quad half-size in blocks. */
    public static final float MIN_SIZE = 3.0F;
    /** Largest quad half-size in blocks. */
    public static final float MAX_SIZE = 6.0F;
    /**
     * Darkest grey a puff is tinted. Shader packs are known to halve the alpha of a translucent particle whose
     * colour is dark ({@code r^2 + g^2 + b^2 < 1}, a grey below 0.58), taking it for campfire smoke; stay above it.
     */
    public static final float MIN_GREY = 0.85F;
    /**
     * Lightest grey a puff is tinted. Kept below 1 because shader packs are known to treat a pure white translucent
     * particle as snow from another mod.
     */
    public static final float MAX_GREY = 0.95F;

    private final float peakAlpha;

    private HazeParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                         double zSpeed, SpriteSet sprites, float peakAlpha) {
        super(level, x, y, z);
        this.xd = xSpeed;
        this.yd = ySpeed;
        this.zd = zSpeed;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = MIN_SIZE + this.random.nextFloat() * (MAX_SIZE - MIN_SIZE);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        float grey = MIN_GREY + this.random.nextFloat() * (MAX_GREY - MIN_GREY);
        this.setColor(grey, grey, grey);
        this.peakAlpha = peakAlpha;
        this.alpha = EmitterMath.hazeAlpha(0, this.lifetime, peakAlpha);
        this.pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed) {
            this.alpha = EmitterMath.hazeAlpha(this.age, this.lifetime, this.peakAlpha);
        }
    }

    /**
     * Creates haze puffs from the sprite set registered for {@code weathermod:haze}. Each puff takes its peak vertex
     * alpha from whether a shader pack is in use when it is created and keeps it for its whole life, so after a pack
     * is switched on or off the puffs already in the air keep the other look until they die, at most
     * {@link #MAX_LIFETIME} ticks later.
     */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            float peakAlpha = EmitterMath.hazePeakAlpha(IrisCompat.isShaderPackInUse());
            return new HazeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites, peakAlpha);
        }
    }
}
