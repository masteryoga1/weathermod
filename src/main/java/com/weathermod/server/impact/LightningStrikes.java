package com.weathermod.server.impact;

import com.weathermod.WeatherMod;
import com.weathermod.config.DestructionMode;
import com.weathermod.config.LightningFire;
import com.weathermod.core.ConvectiveParams;
import com.weathermod.entity.FlyingBlockEntity;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.StormRuntime;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.FamilyContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The lightning of the mod's storms, and the filter that keeps vanilla's own random lightning out while the mod
 * drives the thunder.
 *
 * <p>A vanilla bolt that is visual only still powers a lightning rod at its strike position, still cleans copper
 * there and still emits its game event; only its fire and its damage are switched off. The mod therefore spawns
 * every bolt visual only, does the damage and the fire itself under its own rules, and decides by the bolt's height
 * whether vanilla's rod and copper code may act: a bolt that must not touch blocks is spawned
 * {@link #EFFECT_FREE_LIFT_BLOCKS} above the surface, where vanilla finds only air. Rods are thus powered where
 * storms may change blocks and nowhere else. The mod never powers a rod itself.
 *
 * <p>The filter goes by the moment a bolt joins the level, because a vanilla storm bolt cannot be told from a
 * summoned one by its fields: vanilla's own lightning is added while chunks tick, between the moment a level begins
 * to tick its chunks and its first entity tick. Scheduled functions and command blocks act earlier in the level's
 * tick, thrown tridents while the entities tick and typed commands outside every level tick.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class LightningStrikes {
    /** Vanilla's lightning damage; kept so armour and enchantments behave as players expect. */
    public static final float LIGHTNING_DAMAGE = 5.0F;
    /** Entities within this horizontal distance of the strike are hit, as vanilla. */
    public static final double EFFECT_RADIUS_BLOCKS = 3.0;
    /** A lightning rod this near a strike draws it, as vanilla, in blocks. */
    public static final int ROD_SEARCH_BLOCKS = 128;
    /**
     * A bolt that must not touch blocks is spawned this far above the surface: vanilla's strike position is then
     * the air cell above the surface block, so its own code powers no rod and cleans no copper.
     */
    public static final double EFFECT_FREE_LIFT_BLOCKS = 0.0625;
    /** Vanilla's skeleton trap chance per unit of effective difficulty. */
    public static final double TRAP_CHANCE_PER_DIFFICULTY = 0.01;

    /** How far above the strike entities are still hit, on top of {@link #EFFECT_RADIUS_BLOCKS}; as vanilla. */
    private static final double EFFECT_HEIGHT_BLOCKS = 6.0;

    /** True while this class adds its own bolt or trap horse. Server thread only. */
    private static boolean spawning;
    /** The server level whose tick has begun and whose entities have not ticked yet. Written by the server thread. */
    @Nullable
    private static ServerLevel chunkPhaseLevel;
    /**
     * The spawn state that level's chunk source held when the tick began. The chunk source makes a new one
     * immediately before it ticks its chunks, so another one in its place means that chunk ticking has begun.
     */
    @Nullable
    private static NaturalSpawner.SpawnState spawnStateAtTickStart;

    private LightningStrikes() {
    }

    /**
     * One natural strike of a storm: at a point picked by {@link StrikeTargeting}, within the level's bolt budget
     * and strike spacing, only in a chunk that ticks its entities (a bolt elsewhere would never tick and so never
     * end). With vanilla's chance the strike springs a skeleton trap instead of doing damage, but never inside a
     * weather deflector's circle. Returns whether a bolt was spawned.
     */
    public static boolean strikeFromStorm(StormInstance storm, ConvectiveParams params, FamilyContext context) {
        ServerLevel level = context.level();
        RandomSource random = context.random();
        double[] target = new double[2];
        StrikeTargeting.pick(storm.x(), storm.z(), storm.radius(), playersInside(level, storm), random, target);
        return strike(level, context.data().runtime(), target[0], target[1], params.lightningFireChance(),
            storm.intensity(), true, random);
    }

    /**
     * Strikes the surface at (x, z) now, ignoring the budgets and without a skeleton trap: commands and tests. A
     * lightning rod nearby draws the strike as it draws any other. Returns whether a bolt was spawned, which it is
     * not in a chunk that is not loaded or does not tick its entities.
     *
     * @param fireChance chance that the strike lights a fire where the config asks the storm type for one, 0..1
     */
    public static boolean strikeAt(ServerLevel level, double x, double z, float fireChance) {
        return strike(level, DimensionWeatherData.get(level).runtime(), x, z, fireChance, 1.0F, false,
            level.getRandom());
    }

    /** True while this class is adding its own bolt or trap horse to the level. */
    public static boolean isSpawning() {
        return spawning;
    }

    /**
     * True from the moment a server level begins to tick its chunks until its first entity ticks (or the tick
     * ends): the window in which vanilla strikes its own random lightning. What the level's tick does before its
     * chunks tick lies outside it: the scheduled functions and the scheduled block ticks, which run every command
     * block. The beginning is read off the chunk source's spawn state, which is replaced just before the chunks
     * tick; while the game is frozen it is not replaced, no chunk ticks and the window stays shut.
     */
    public static boolean inChunkPhase(ServerLevel level) {
        return chunkPhaseLevel == level && level.getChunkSource().getLastSpawnState() != spawnStateAtTickStart;
    }

    /**
     * Remembers a server level as the one whose tick has begun, with the spawn state its chunk source holds at
     * that moment; levels tick one after another.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelTickPre(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) {
            chunkPhaseLevel = level;
            spawnStateAtTickStart = level.getChunkSource().getLastSpawnState();
        }
    }

    /** Ends the chunk phase of a server level that ticked no entity. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel) {
            chunkPhaseLevel = null;
            spawnStateAtTickStart = null;
        }
    }

    /** Ends the chunk phase at the level's first entity tick; afterwards one null check per entity. */
    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        ServerLevel remembered = chunkPhaseLevel;
        if (remembered != null && event.getEntity().level() == remembered) {
            chunkPhaseLevel = null;
            spawnStateAtTickStart = null;
        }
    }

    /**
     * Drops vanilla's own storm lightning and the skeleton trap horse it adds just before a bolt, while the mod
     * drives the thunder flag of the level and the filter is switched on. Only entities that join during the
     * chunk phase are dropped, so Channeling tridents and lightning summoned by a typed command, a command block
     * or a scheduled function keep working; the mod's own bolts and trap horses pass because they are added under
     * {@link #isSpawning()}.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (spawning || event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!inChunkPhase(level) || !isVanillaStormEntity(event.getEntity())) {
            return;
        }
        if (!level.isThundering() || !WeatherManager.isEnabled(level)) {
            return;
        }
        if (WeatherManager.vanillaWeatherBridge() && WeatherManager.suppressVanillaLightning()) {
            event.setCanceled(true);
        }
    }

    private static boolean isVanillaStormEntity(Entity entity) {
        if (entity instanceof LightningBolt bolt) {
            return bolt.getCause() == null;
        }
        return entity instanceof SkeletonHorse horse && horse.isTrap() && !horse.isVehicle()
            && horse.tickCount == 0;
    }

    private static boolean strike(ServerLevel level, StormRuntime runtime, double targetX, double targetZ,
                                  float fireChance, float power, boolean natural, RandomSource random) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetZ)) {
            return false;
        }
        int blockX = Mth.floor(targetX);
        int blockZ = Mth.floor(targetZ);
        LevelChunk chunk = level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4);
        if (chunk == null || !level.isPositionEntityTicking(new BlockPos(blockX, 0, blockZ))) {
            return false;
        }
        if (natural && (!runtime.strikeAllowed(targetX, targetZ) || !runtime.tryBolt())) {
            return false;
        }
        BlockPos pos = new BlockPos(blockX,
            chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX & 15, blockZ & 15) + 1, blockZ);
        boolean drawnAway = false;
        Optional<BlockPos> rod = findRod(level, pos);
        if (rod.isPresent()) {
            BlockPos aboveRod = rod.get().above();
            LevelChunk rodChunk = level.getChunkSource().getChunkNow(aboveRod.getX() >> 4, aboveRod.getZ() >> 4);
            if (rodChunk == null || !level.isPositionEntityTicking(aboveRod)) {
                // No bolt can be shown on this rod, yet it draws the strike: the bolt stays where it was aimed
                // and does nothing there.
                drawnAway = true;
            } else if (natural && !runtime.strikeAllowed(aboveRod.getX() + 0.5, aboveRod.getZ() + 0.5)) {
                return false;
            } else {
                pos = aboveRod;
                chunk = rodChunk;
            }
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;
        BlockPos surface = pos.below();
        BlockState surfaceState = chunk.getBlockState(surface);

        DestructionMode mode = WeatherManager.destructionMode();
        boolean deflected = drawnAway || ProtectedActions.deflected(level, x, z);
        boolean trap = natural && !deflected && mode.affectsEntities()
            && springsTrap(level, pos, surfaceState, random);
        boolean blockEffects = !trap && mode.ripsBlocks() && !deflected
            && copperAllows(level, chunk, surface, surfaceState);
        if (!blockEffects && reactsToLightning(chunk.getBlockState(pos))) {
            return false;
        }

        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return false;
        }
        bolt.setVisualOnly(true);
        bolt.setDamage(LIGHTNING_DAMAGE);
        Vec3 foot = Vec3.atBottomCenterOf(pos);
        bolt.moveTo(blockEffects ? foot : foot.add(0.0, EFFECT_FREE_LIFT_BLOCKS, 0.0));
        if (trap) {
            SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
            if (horse != null) {
                horse.setTrap(true);
                horse.setAge(0);
                horse.setPos(x, y, z);
                addOwn(level, horse);
            }
        }
        if (!addOwn(level, bolt)) {
            return false;
        }

        runtime.recordStrike(x, z);
        WeatherNetwork.sendStrike(level, x, y, z, power);
        if (!trap && mode.affectsEntities() && !deflected) {
            hitEntities(level, bolt, x, y, z);
        }
        if (blockEffects && lightsFire(level, fireChance, random)) {
            placeFire(level, chunk, pos);
        }
        return true;
    }

    /**
     * The lightning rod that draws a strike at {@code pos}: the nearest one within {@link #ROD_SEARCH_BLOCKS} that
     * is the top block of its column, as vanilla picks it. Rods in chunks that are not loaded are passed over. A
     * rod in a loaded chunk that does not tick its entities still draws the strike. No bolt can be shown there, so
     * the bolt falls where it was aimed and is as free of effects as one inside a weather deflector's circle: the
     * rod protects its surroundings and the storm keeps its lightning.
     */
    private static Optional<BlockPos> findRod(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().findClosest(
            type -> type.is(PoiTypes.LIGHTNING_ROD),
            rodPos -> isTopOfColumn(level, rodPos),
            pos,
            ROD_SEARCH_BLOCKS,
            PoiManager.Occupancy.ANY);
    }

    private static boolean isTopOfColumn(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null
            && chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15) == pos.getY();
    }

    /** Vanilla's skeleton trap roll: never on a lightning rod, only where mobs may spawn, by the local difficulty. */
    private static boolean springsTrap(ServerLevel level, BlockPos pos, BlockState surfaceState,
                                       RandomSource random) {
        if (surfaceState.getBlock() instanceof LightningRodBlock) {
            return false;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return false;
        }
        double chance = level.getCurrentDifficultyAt(pos).getEffectiveDifficulty() * TRAP_CHANCE_PER_DIFFICULTY;
        return random.nextDouble() < chance;
    }

    /**
     * Whether vanilla's bolt may clean the copper it would clean when it strikes {@code surface}: the surface
     * block itself or, under a lightning rod, the block the rod is mounted on. No copper there passes; copper
     * passes when the weather may change that block. A position that is not loaded fails, so vanilla's own look at
     * it never loads a chunk.
     */
    private static boolean copperAllows(ServerLevel level, LevelChunk chunk, BlockPos surface,
                                        BlockState surfaceState) {
        BlockPos copperPos = surface;
        BlockState copperState = surfaceState;
        if (surfaceState.getBlock() instanceof LightningRodBlock) {
            if (!surfaceState.hasProperty(LightningRodBlock.FACING)) {
                return false;
            }
            copperPos = surface.relative(surfaceState.getValue(LightningRodBlock.FACING).getOpposite());
            LevelChunk copperChunk = sameChunk(chunk, copperPos)
                ? chunk
                : level.getChunkSource().getChunkNow(copperPos.getX() >> 4, copperPos.getZ() >> 4);
            if (copperChunk == null) {
                return false;
            }
            copperState = copperChunk.getBlockState(copperPos);
        }
        if (!(copperState.getBlock() instanceof WeatheringCopper)) {
            return true;
        }
        return ProtectedActions.mayBreak(level, copperPos, copperState);
    }

    private static boolean sameChunk(LevelChunk chunk, BlockPos pos) {
        return chunk.getPos().x == pos.getX() >> 4 && chunk.getPos().z == pos.getZ() >> 4;
    }

    /** Whether vanilla's bolt code acts on a block of this kind at its strike position. */
    private static boolean reactsToLightning(BlockState state) {
        return state.getBlock() instanceof LightningRodBlock || state.getBlock() instanceof WeatheringCopper;
    }

    /** Adds a bolt or a trap horse of the mod, marked so the join filter lets it pass. */
    private static boolean addOwn(ServerLevel level, Entity entity) {
        boolean before = spawning;
        spawning = true;
        try {
            return level.addFreshEntity(entity);
        } finally {
            spawning = before;
        }
    }

    /** Vanilla's lightning hit on everything alive around the strike, sparing items, orbs and flying blocks. */
    private static void hitEntities(ServerLevel level, LightningBolt bolt, double x, double y, double z) {
        AABB box = new AABB(
            x - EFFECT_RADIUS_BLOCKS, y - EFFECT_RADIUS_BLOCKS, z - EFFECT_RADIUS_BLOCKS,
            x + EFFECT_RADIUS_BLOCKS, y + EFFECT_HEIGHT_BLOCKS + EFFECT_RADIUS_BLOCKS, z + EFFECT_RADIUS_BLOCKS);
        List<Entity> struck = level.getEntities(bolt, box, LightningStrikes::canBeStruck);
        for (Entity entity : struck) {
            if (!EventHooks.onEntityStruckByLightning(entity, bolt)) {
                entity.thunderHit(level, bolt);
            }
        }
    }

    private static boolean canBeStruck(Entity entity) {
        return entity.isAlive()
            && !(entity instanceof ItemEntity)
            && !(entity instanceof ExperienceOrb)
            && !(entity instanceof LightningBolt)
            && !(entity instanceof FlyingBlockEntity);
    }

    /** Whether this strike lights a fire: never, by the type's chance under the doFireTick rule, or always. */
    private static boolean lightsFire(ServerLevel level, float fireChance, RandomSource random) {
        LightningFire fire = WeatherManager.lightningFire();
        if (fire == LightningFire.ALWAYS) {
            return true;
        }
        return fire == LightningFire.NORMAL
            && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
            && random.nextFloat() < fireChance;
    }

    private static void placeFire(ServerLevel level, LevelChunk chunk, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !chunk.getBlockState(pos).isAir()) {
            return;
        }
        if (!ProtectedActions.surroundingsLoaded(level, pos)) {
            return;
        }
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (fire.canSurvive(level, pos)) {
            ProtectedActions.place(level, pos, fire);
        }
    }

    /** The positions of the living players who are not spectators and stand inside the storm's radius. */
    private static List<Vec3> playersInside(ServerLevel level, StormInstance storm) {
        List<Vec3> inside = new ArrayList<>();
        double radiusSqr = (double) storm.radius() * storm.radius();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator()
                    && storm.distanceToSqr(player.getX(), player.getZ()) <= radiusSqr) {
                inside.add(player.position());
            }
        }
        return inside;
    }
}
