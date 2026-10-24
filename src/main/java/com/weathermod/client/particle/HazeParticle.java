package com.weathermod.client.particle;

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
 * like any other particle, so it darkens under storm clouds and glows near torches.
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
    /** Darkest grey a puff is tinted. */
    public static final float MIN_GREY = 0.85F;
    /** Lightest grey a puff is tinted. */
    public static final float MAX_GREY = 0.95F;

    private HazeParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                         double zSpeed, SpriteSet sprites) {
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
        this.alpha = EmitterMath.hazeAlpha(0, this.lifetime);
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
            this.alpha = EmitterMath.hazeAlpha(this.age, this.lifetime);
        }
    }

    /** Creates haze puffs from the sprite set registered for {@code weathermod:haze}. */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new HazeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites);
        }
    }
}
