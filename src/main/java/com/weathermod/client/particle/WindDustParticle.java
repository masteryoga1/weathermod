package com.weathermod.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * A small sandy speck blown along the ground by a strong wind. It takes the emitter's wind velocity with a little
 * per-speck variation, jitters vertically, collides with the world, settles under light gravity and fades out over
 * the last third of its short life.
 */
public final class WindDustParticle extends TextureSheetParticle {
    /** Shortest life in ticks. */
    public static final int MIN_LIFETIME = 20;
    /** Longest life in ticks. */
    public static final int MAX_LIFETIME = 40;
    /** Smallest quad half-size in blocks. */
    public static final float MIN_SIZE = 0.08F;
    /** Largest quad half-size in blocks. */
    public static final float MAX_SIZE = 0.15F;
    /** Red of the base dust colour. */
    public static final float BASE_RED = 0.72F;
    /** Green of the base dust colour. */
    public static final float BASE_GREEN = 0.66F;
    /** Blue of the base dust colour. */
    public static final float BASE_BLUE = 0.55F;
    /** Each speck is tinted by up to this fraction lighter or darker. */
    public static final float COLOR_JITTER = 0.10F;
    /** Gravity in vanilla particle units. */
    public static final float GRAVITY = 0.02F;
    /** Slowest speck relative to the wind velocity it was given. */
    public static final float MIN_SPEED_SCALE = 0.9F;
    /** Fastest speck relative to the wind velocity it was given. */
    public static final float MAX_SPEED_SCALE = 1.2F;
    /** Random vertical velocity added at birth, in blocks per tick either way. */
    public static final double VERTICAL_JITTER = 0.02D;

    private WindDustParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                             double zSpeed, SpriteSet sprites) {
        super(level, x, y, z);
        double speedScale = MIN_SPEED_SCALE + this.random.nextFloat() * (MAX_SPEED_SCALE - MIN_SPEED_SCALE);
        this.xd = xSpeed * speedScale;
        this.yd = ySpeed + (this.random.nextDouble() * 2.0D - 1.0D) * VERTICAL_JITTER;
        this.zd = zSpeed * speedScale;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = MIN_SIZE + this.random.nextFloat() * (MAX_SIZE - MIN_SIZE);
        this.setSize(this.quadSize, this.quadSize);
        this.hasPhysics = true;
        this.gravity = GRAVITY;
        float tint = 1.0F + (this.random.nextFloat() * 2.0F - 1.0F) * COLOR_JITTER;
        this.setColor(Mth.clamp(BASE_RED * tint, 0.0F, 1.0F), Mth.clamp(BASE_GREEN * tint, 0.0F, 1.0F),
            Mth.clamp(BASE_BLUE * tint, 0.0F, 1.0F));
        this.alpha = EmitterMath.dustAlpha(0, this.lifetime);
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
            this.alpha = EmitterMath.dustAlpha(this.age, this.lifetime);
        }
    }

    /** Creates dust specks from the sprite set registered for {@code weathermod:wind_dust}. */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new WindDustParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites);
        }
    }
}
