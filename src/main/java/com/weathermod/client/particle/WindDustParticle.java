package com.weathermod.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A speckled puff of dust the wind kicks up from open ground. It is opaque, like the vanilla dust and ash
 * particles, so it keeps its colour under a shader pack, and it is tinted against the ground it rose from: pale
 * over grass, dirt and stone, darker over sand and snow. It takes the emitter's wind velocity with a little per-puff
 * variation and a small upward kick, grows in, slows down, and shrinks away over the last third of its life; a puff
 * that lands or is stopped by a wall is removed.
 */
public final class WindDustParticle extends TextureSheetParticle {
    /** Shortest life in ticks. */
    public static final int MIN_LIFETIME = 30;
    /** Longest life in ticks. */
    public static final int MAX_LIFETIME = 50;
    /** Smallest quad half-size in blocks. */
    public static final float MIN_SIZE = 0.12F;
    /** Largest quad half-size in blocks. */
    public static final float MAX_SIZE = 0.22F;
    /** Width and height of the collision box in blocks; smaller than the drawn quad so a puff slips past edges. */
    public static final float COLLISION_SIZE = 0.1F;
    /**
     * Each puff is tinted by up to this fraction lighter or darker. Kept small so no tint drifts into the colour
     * ranges shader packs single out as glowing particles.
     */
    public static final float COLOR_JITTER = 0.08F;
    /** Gravity in vanilla particle units. */
    public static final float GRAVITY = 0.02F;
    /** Slowest puff relative to the wind velocity it was given. */
    public static final float MIN_SPEED_SCALE = 0.9F;
    /** Fastest puff relative to the wind velocity it was given. */
    public static final float MAX_SPEED_SCALE = 1.2F;
    /** Smallest upward velocity added at birth, in blocks per tick. */
    public static final double MIN_LIFT = 0.0D;
    /** Largest upward velocity added at birth, in blocks per tick: dust is kicked up, not dropped. */
    public static final double MAX_LIFT = 0.04D;
    /**
     * A puff that covers less horizontal distance than this in a tick, in blocks, has been stopped by something and
     * is removed; the slowest free puff still covers about 0.04.
     */
    public static final double STALLED_STEP = 1.0E-3D;

    private static final float CHANNEL_MAX = 255.0F;
    private static final int QUARTER_TURNS = 4;
    private static final float QUARTER_TURN = (float) (Math.PI / 2.0D);

    private WindDustParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                             double zSpeed, SpriteSet sprites, int color) {
        super(level, x, y, z);
        double speedScale = MIN_SPEED_SCALE + this.random.nextFloat() * (MAX_SPEED_SCALE - MIN_SPEED_SCALE);
        this.xd = xSpeed * speedScale;
        this.yd = ySpeed + MIN_LIFT + this.random.nextDouble() * (MAX_LIFT - MIN_LIFT);
        this.zd = zSpeed * speedScale;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = MIN_SIZE + this.random.nextFloat() * (MAX_SIZE - MIN_SIZE);
        this.setSize(COLLISION_SIZE, COLLISION_SIZE);
        this.hasPhysics = true;
        this.gravity = GRAVITY;
        float tint = (1.0F + (this.random.nextFloat() * 2.0F - 1.0F) * COLOR_JITTER) / CHANNEL_MAX;
        this.setColor(Mth.clamp(FastColor.ARGB32.red(color) * tint, 0.0F, 1.0F),
            Mth.clamp(FastColor.ARGB32.green(color) * tint, 0.0F, 1.0F),
            Mth.clamp(FastColor.ARGB32.blue(color) * tint, 0.0F, 1.0F));
        // Quarter turns keep the texels square on screen and turn four sprites into sixteen looks.
        this.roll = this.random.nextInt(QUARTER_TURNS) * QUARTER_TURN;
        this.oRoll = this.roll;
        this.pickSprite(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * EmitterMath.dustScale(this.age + partialTick, this.lifetime);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.removed) {
            return;
        }
        double step = Math.abs(this.x - this.xo) + Math.abs(this.z - this.zo);
        if (this.onGround || step < STALLED_STEP) {
            this.remove();
        }
    }

    /**
     * Creates dust puffs from the sprite set registered for {@code weathermod:wind_dust}. A
     * {@link SimpleParticleType} carries no colour, so the provider reads the map colour of the ground under the
     * spawn point itself and lets {@link EmitterMath#dustColor} turn it into a dust colour.
     */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            int color = EmitterMath.dustColor(groundColor(level, Mth.floor(x), Mth.floor(z)));
            return new WindDustParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites, color);
        }

        /**
         * The map colour of the surface of a column. Snow layers do not block motion, so the heightmap looks
         * through them and the snow is recognised by the block resting on the surface instead.
         */
        private static int groundColor(ClientLevel level, int blockX, int blockZ) {
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, surface, blockZ);
            if (level.getBlockState(pos).is(Blocks.SNOW)) {
                return MapColor.SNOW.col;
            }
            pos.setY(surface - 1);
            return level.getBlockState(pos).getMapColor(level, pos).col;
        }
    }
}
