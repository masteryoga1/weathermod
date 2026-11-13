package com.weathermod.client.particle;

import com.weathermod.client.ClientWeatherState;
import com.weathermod.core.Headings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A leaf torn loose by the wind, tinted like the leaf block it came off. The wind keeps carrying it: every tick it
 * is pulled toward the wind's current velocity, so it follows gusts and picks up speed again after an obstacle. It
 * weaves across the wind on a slow sine, tumbles faster the harder the wind blows and falls under light gravity. It
 * is removed when it lands, as the vanilla cherry leaf is, and shrinks away when the wind holds it against an
 * obstacle or its life runs out, since an opaque particle cannot fade.
 */
public final class WindLeafParticle extends TextureSheetParticle {
    /** Shortest life in ticks. */
    public static final int MIN_LIFETIME = 60;
    /** Longest life in ticks. */
    public static final int MAX_LIFETIME = 120;
    /** Smallest quad half-size in blocks. */
    public static final float MIN_SIZE = 0.14F;
    /** Largest quad half-size in blocks. */
    public static final float MAX_SIZE = 0.20F;
    /**
     * Width and height of the collision box in blocks. It is smaller than the drawn quad and independent of it, so a
     * leaf that appears just outside a leaf block never starts with its box inside the leaf layer.
     */
    public static final float COLLISION_SIZE = 0.1F;
    /** Gravity in vanilla particle units. */
    public static final float GRAVITY = 0.02F;
    /** Darkest shade a leaf's tint is multiplied by. */
    public static final float MIN_SHADE = 0.65F;
    /**
     * Lightest shade a leaf's tint is multiplied by. The spread between the two matters more than either end: the
     * darker leaves stand out against the sky, the lighter ones against the canopy.
     */
    public static final float MAX_SHADE = 1.0F;
    /** Sideways velocity added per tick at the flutter's peak, in blocks per tick. */
    public static final float FLUTTER_AMPLITUDE = 0.02F;
    /** Radians the flutter sine advances per tick. */
    public static final float FLUTTER_RATE = 0.5F;
    /**
     * Ticks in a row the wind may hold a leaf against an obstacle before the leaf starts to shrink away. Long enough
     * for a leaf to slip over or under the edge of a canopy and fly on, short enough that it does not hang there.
     */
    public static final int PINNED_TICKS = 12;

    private static final float FULL_CIRCLE = (float) (2.0D * Math.PI);
    private static final double STILL_AIR = 1.0E-6D;
    /** The vanilla collision code drops a step shorter than 1.0E-7, so only a longer one can count as blocked. */
    private static final double NEGLIGIBLE_STEP = 1.0E-6D;
    private static final float CHANNEL_MAX = 255.0F;
    private static final int NO_TINT = -1;
    /** Where the leaf block a leaf came off sits relative to it: just above an underside leaf, just below a top one. */
    private static final double[] SOURCE_OFFSETS = {0.5D, -0.5D};

    private final float flutterPhase;
    private final float rollDirection;
    private final double sideX;
    private final double sideZ;
    private int pinnedTicks;

    private WindLeafParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
                             double zSpeed, SpriteSet sprites, int tint) {
        super(level, x, y, z);
        this.xd = xSpeed;
        this.yd = ySpeed;
        this.zd = zSpeed;
        this.lifetime = MIN_LIFETIME + this.random.nextInt(MAX_LIFETIME - MIN_LIFETIME + 1);
        this.quadSize = MIN_SIZE + this.random.nextFloat() * (MAX_SIZE - MIN_SIZE);
        this.setSize(COLLISION_SIZE, COLLISION_SIZE);
        this.hasPhysics = true;
        this.gravity = GRAVITY;
        float shade = (MIN_SHADE + this.random.nextFloat() * (MAX_SHADE - MIN_SHADE)) / CHANNEL_MAX;
        this.setColor(FastColor.ARGB32.red(tint) * shade, FastColor.ARGB32.green(tint) * shade,
            FastColor.ARGB32.blue(tint) * shade);
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
    public float getQuadSize(float partialTick) {
        return this.quadSize * EmitterMath.leafScale(this.age + partialTick, this.lifetime);
    }

    @Override
    public void tick() {
        this.oRoll = this.roll;
        ClientWeatherState wind = ClientWeatherState.INSTANCE;
        float windSpeed = wind.hasWind() ? Math.max(wind.windSpeed(), wind.windGustSpeed()) : 0.0F;
        float direction = wind.windDirectionDegrees();
        double carry = EmitterMath.driftPerTick(windSpeed, EmitterMath.LEAF_DRIFT);
        float flutter = Mth.sin(this.age * FLUTTER_RATE + this.flutterPhase) * FLUTTER_AMPLITUDE;
        this.roll += this.rollDirection * EmitterMath.leafRollStep(windSpeed);
        this.xd = EmitterMath.leafPull(this.xd, Headings.directionX(direction) * carry) + flutter * this.sideX;
        this.zd = EmitterMath.leafPull(this.zd, Headings.directionZ(direction) * carry) + flutter * this.sideZ;
        double wantedX = this.xd;
        double wantedZ = this.zd;
        super.tick();
        if (this.removed) {
            return;
        }
        boolean stuck = this.x == this.xo && this.y == this.yo && this.z == this.zo;
        if (this.onGround || stuck) {
            // Landed, or blocked from above: the vanilla particle never moves again after either.
            this.remove();
            return;
        }
        boolean pinned = isBlocked(wantedX, this.xd) || isBlocked(wantedZ, this.zd);
        this.pinnedTicks = pinned ? this.pinnedTicks + 1 : 0;
        if (this.pinnedTicks >= PINNED_TICKS) {
            this.lifetime = Math.min(this.lifetime, this.age + Mth.ceil(EmitterMath.LEAF_SHRINK_TICKS));
        }
    }

    /** The vanilla move zeroes the velocity along an axis it could not carry the whole step on. */
    private static boolean isBlocked(double wantedStep, double velocityAfterMove) {
        return Math.abs(wantedStep) > NEGLIGIBLE_STEP && velocityAfterMove == 0.0D;
    }

    /**
     * Creates leaves from the sprite set registered for {@code weathermod:wind_leaf}. A {@link SimpleParticleType}
     * carries no colour, so the provider reads the tint of the leaf block the leaf came off itself: the block just
     * above or just below the spawn point, through the block colours (oak follows the biome, birch and spruce have
     * their own greens) or its map colour where the block is not tinted (cherry, azalea). A leaf with no leaf block
     * next to it takes the biome's foliage colour.
     */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new WindLeafParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, sprites, sourceTint(level, x, y, z));
        }

        private static int sourceTint(ClientLevel level, double x, double y, double z) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (double offset : SOURCE_OFFSETS) {
                pos.set(x, y + offset, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(BlockTags.LEAVES)) {
                    int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
                    return tint != NO_TINT ? tint : state.getMapColor(level, pos).col;
                }
            }
            return BiomeColors.getAverageFoliageColor(level, pos.set(x, y, z));
        }
    }
}
