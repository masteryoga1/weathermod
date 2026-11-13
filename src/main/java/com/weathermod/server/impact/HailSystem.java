package com.weathermod.server.impact;

import com.weathermod.config.DestructionMode;
import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.HailCore;
import com.weathermod.core.ModTags;
import com.weathermod.core.WeatherDamageTypes;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.FamilyContext;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * What falling hail does to the world under a storm's hail core (see {@link HailCore}): it hurts players and mobs
 * that stand under the open sky, and it breaks crops, small plants and, when the server allows it, glass that lies
 * open to the sky. Called by the convective family every tick; it does nothing while the storm does not hail.
 */
public final class HailSystem {
    /** Entities are checked every this many ticks. */
    public static final int ENTITY_INTERVAL_TICKS = 10;
    /** Chance per pass that an exposed entity is hit, times the hail strength at its position. */
    public static final float HIT_CHANCE = 0.25F;
    /** Mobs are only checked this near a player inside the core. */
    public static final double ENTITY_SCAN_BLOCKS = 32.0;
    /** At most this many entities are checked per storm and pass. */
    public static final int MAX_VICTIMS_PER_PASS = 64;
    /** Columns of the core that are tried for a block to break, per storm and tick. */
    public static final int BLOCK_ATTEMPTS_PER_TICK = 4;
    /** Chance that a breakable top block that was picked breaks, times the hail strength at its position. */
    public static final float BLOCK_BREAK_CHANCE = 0.25F;
    /** Hail never takes a mob below this health (one heart) unless hailKillsMobs is on. Players are not spared. */
    public static final float HAIL_MOB_HEALTH_FLOOR = 2.0F;
    /** Glass breaks only where the local hail strength is at least this (the centre of a strong core). */
    public static final float GLASS_MIN_STRENGTH = 0.8F;

    private HailSystem() {
    }

    /**
     * One tick of hail under a storm. Entities are hurt every {@link #ENTITY_INTERVAL_TICKS} where the destruction
     * mode lets storms affect entities and the type's hail does damage; blocks are tried every tick where the mode
     * lets storms change blocks and the server has hail block damage on. Nothing happens while the storm's hail
     * strength is zero.
     */
    public static void tick(StormInstance storm, ConvectiveParams params, FamilyContext context) {
        if (!(storm.hail() > 0.0F)) {
            return;
        }
        DestructionMode mode = WeatherManager.destructionMode();
        if (mode.affectsEntities() && params.hailDamage() > 0.0F
                && context.tick() % ENTITY_INTERVAL_TICKS == 0) {
            hurtEntities(storm, params.hailDamage(), context);
        }
        if (mode.ripsBlocks() && WeatherManager.hailBlockDamage()) {
            breakBlocks(storm, context);
        }
    }

    /**
     * Whether a hit of {@code damage} may land on a living entity with this health: always on a player and always
     * when {@code hailKillsMobs} is on, otherwise only when the hit leaves the mob at
     * {@link #HAIL_MOB_HEALTH_FLOOR} or more.
     */
    public static boolean mayHit(boolean player, float health, float damage, boolean hailKillsMobs) {
        return player || hailKillsMobs || health - damage >= HAIL_MOB_HEALTH_FLOOR;
    }

    private static float strengthAt(StormInstance storm, double x, double z) {
        return HailCore.strengthAt(storm.x(), storm.z(), storm.headingDegrees(), storm.radius(), storm.hail(), x, z);
    }

    /**
     * Collects the players inside the hail core and then the living entities inside the core that are within
     * {@link #ENTITY_SCAN_BLOCKS} of one of them, at most {@link #MAX_VICTIMS_PER_PASS} together, and rolls a hit
     * for each one that stands under the open sky.
     */
    private static void hurtEntities(StormInstance storm, float damage, FamilyContext context) {
        ServerLevel level = context.level();
        List<LivingEntity> victims = new ArrayList<>();
        List<ServerPlayer> anchors = new ArrayList<>();
        IntSet seen = new IntOpenHashSet();
        for (ServerPlayer player : level.players()) {
            if (victims.size() >= MAX_VICTIMS_PER_PASS) {
                break;
            }
            if (player.isAlive() && !player.isSpectator()
                    && strengthAt(storm, player.getX(), player.getZ()) > 0.0F) {
                anchors.add(player);
                victims.add(player);
                seen.add(player.getId());
            }
        }
        if (victims.isEmpty()) {
            return;
        }
        EntityTypeTest<Entity, LivingEntity> living = EntityTypeTest.forClass(LivingEntity.class);
        for (ServerPlayer anchor : anchors) {
            if (victims.size() >= MAX_VICTIMS_PER_PASS) {
                break;
            }
            AABB box = new AABB(
                anchor.getX() - ENTITY_SCAN_BLOCKS, anchor.getY() - ENTITY_SCAN_BLOCKS,
                anchor.getZ() - ENTITY_SCAN_BLOCKS, anchor.getX() + ENTITY_SCAN_BLOCKS,
                anchor.getY() + ENTITY_SCAN_BLOCKS, anchor.getZ() + ENTITY_SCAN_BLOCKS);
            level.getEntities(living, box,
                entity -> entity.isAlive() && !entity.isSpectator()
                    && strengthAt(storm, entity.getX(), entity.getZ()) > 0.0F && seen.add(entity.getId()),
                victims, MAX_VICTIMS_PER_PASS);
        }

        RandomSource random = context.random();
        boolean hailKillsMobs = WeatherManager.hailKillsMobs();
        DamageSource source = level.damageSources().source(WeatherDamageTypes.HAIL);
        for (LivingEntity victim : victims) {
            float strength = strengthAt(storm, victim.getX(), victim.getZ());
            if (random.nextFloat() >= HIT_CHANCE * strength) {
                continue;
            }
            if (!level.canSeeSky(BlockPos.containing(victim.getEyePosition()))) {
                continue;
            }
            if (mayHit(victim instanceof Player, victim.getHealth(), damage, hailKillsMobs)) {
                victim.hurt(source, damage);
            }
        }
    }

    /**
     * Tries {@link #BLOCK_ATTEMPTS_PER_TICK} random columns of the core: the top block of a loaded column that is
     * not inside a deflector breaks when hail breaks its kind, the roll succeeds and the weather may break it.
     */
    private static void breakBlocks(StormInstance storm, FamilyContext context) {
        ServerLevel level = context.level();
        RandomSource random = context.random();
        float radius = storm.radius();
        double centreX = HailCore.centreX(storm.x(), storm.headingDegrees(), radius);
        double centreZ = HailCore.centreZ(storm.z(), storm.headingDegrees(), radius);
        double coreRadius = HailCore.coreRadius(radius);
        boolean breaksGlass = WeatherManager.hailBreaksGlass();
        for (int attempt = 0; attempt < BLOCK_ATTEMPTS_PER_TICK; attempt++) {
            double distance = coreRadius * Math.sqrt(random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2.0;
            double x = centreX + Math.cos(angle) * distance;
            double z = centreZ + Math.sin(angle) * distance;
            float strength = strengthAt(storm, x, z);
            if (!(strength > 0.0F)) {
                continue;
            }
            int blockX = Mth.floor(x);
            int blockZ = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
            if (chunk == null) {
                continue;
            }
            BlockPos pos = new BlockPos(blockX,
                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, blockX & 15, blockZ & 15), blockZ);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir() || !breaks(state, strength, breaksGlass)) {
                continue;
            }
            if (random.nextFloat() >= BLOCK_BREAK_CHANCE * strength) {
                continue;
            }
            if (ProtectedActions.deflected(level, x, z)) {
                continue;
            }
            if (ProtectedActions.mayBreak(level, pos, state)) {
                ProtectedActions.destroy(level, pos, state, true);
            }
        }
    }

    /** Whether hail of the given local strength breaks a block of this kind. */
    private static boolean breaks(BlockState state, float strength, boolean breaksGlass) {
        if (state.is(ModTags.Blocks.HAIL_BREAKS)) {
            return true;
        }
        return breaksGlass && strength >= GLASS_MIN_STRENGTH && state.is(ModTags.Blocks.HAIL_BREAKS_GLASS);
    }
}
