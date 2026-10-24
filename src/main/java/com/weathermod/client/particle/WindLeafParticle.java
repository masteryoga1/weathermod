package com.weathermod.client.particle;

import com.weathermod.client.ClientWeatherState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * A leaf torn loose by the wind, tinted with the foliage colour where it came off. It is carried at the velocity the
 * emitter gave it, weaves across the wind on a slow sine, tumbles faster the harder the wind blows, falls under light
 * gravity and is removed when it lands, as the vanilla cherry leaf is.
 */
public final class WindLeafParticle extends TextureSheetParticle {
    /** Shortest life in ticks. */
    public static final int MIN_LIFETIME = 60;
    /** Longest life in ticks. */
    public static final int MAX_LIFETIME = 120;
    /** Smallest quad half-size in blocks. */
    public static final float MIN_SIZE = 0.10F;
    /** Largest quad half-size in blocks. */
    public static final float MAX_SIZE = 0.16F;
    /** Gravity in vanilla particle units. */
    public static final float GRAVITY = 0.01F;
    /** Sideways velocity added per tick at the flutter's peak, in blocks per tick. */
    public static final float FLUTTER_AMPLITUDE = 0.02F;
    /** Radians the flutter sine advances per tick. */
    public static final float FLUTTER_RATE = 0.5F;

    private static final float FULL_CIRCLE = (float) (2.0D * Math.PI);
    private static final double STILL_AIR = 1.0E-6D;

    private final float flutterPhase;
    private final float rollDirection;
    private final double sideX;
    private final double sideZ;

    private WindLeafParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                             double zSpeed, SpriteSet sprites, int foliageColor) {
        super(level, x, y, z);
        this.xd = xSpeed;
        this.yd = ySpeed;
        this.zd = zSpeed;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = MIN_SIZE + this.random.nextFloat() * (MAX_SIZE - MIN_SIZE);
        this.setSize(this.quadSize, this.quadSize);
        this.hasPhysics = true;
        this.gravity = GRAVITY;
        this.setColor(FastColor.ARGB32.red(foliageColor) / 255.0F, FastColor.ARGB32.green(foliageColor) / 255.0F,
            FastColor.ARGB32.blue(foliageColor) / 255.0F);
        this.flutterPhase = this.random.nextFloat() * FULL_CIRCLE;
        this.rollDirection = this.random.nextBoolean() ? 1.0F : -1.0F;
        this.roll = this.random.nextFloat() * FULL_CIRCLE;
        this.oRoll = this.roll;
        double horizontal = Math.sqrt(xSpeed * xSpeed + zSpeed * zSpeed);
        if (horizontal > STILL_AIR) {
            this.sideX = -zSpeed / horizontal;
            this.sideZ = xSpeed / horizontal;
        } else {
            this.sideX = 1.0D;
            this.sideZ = 0.0D;
        }
        this.pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public void tick() {
        this.oRoll = this.roll;
        ClientWeatherState wind = ClientWeatherState.INSTANCE;
        float windSpeed = wind.hasWind() ? wind.windSpeed() : 0.0F;
        this.roll += this.rollDirection * EmitterMath.leafRollStep(windSpeed);
        float flutter = Mth.sin(this.age * FLUTTER_RATE + this.flutterPhase) * FLUTTER_AMPLITUDE;
        this.xd += flutter * this.sideX;
        this.zd += flutter * this.sideZ;
        super.tick();
        if (!this.removed && this.onGround) {
            this.remove();
        }
    }

    /**
     * Creates leaves from the sprite set registered for {@code weathermod:wind_leaf}, reading the average foliage
     * colour at the spawn position itself because a {@link SimpleParticleType} carries no colour.
     */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            int foliageColor = BiomeColors.getAverageFoliageColor(level, BlockPos.containing(x, y, z));
            return new WindLeafParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites, foliageColor);
        }
    }
}
