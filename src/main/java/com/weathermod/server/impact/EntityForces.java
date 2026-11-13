package com.weathermod.server.impact;

import com.weathermod.WeatherMod;
import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.Coverage;
import com.weathermod.core.FunnelSpine;
import com.weathermod.core.VortexField;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WindForce;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.StormRuntime;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.WindState;
import com.weathermod.server.family.ActiveVortex;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import org.joml.Vector3d;

/**
 * The server's share of pushing entities: each side pushes what it simulates. The server pushes every entity
 * except players and entities a player controls, which each client pushes itself with the same pure functions
 * ({@link VortexField}, {@link WindForce}) and the state the server synced. Three passes run per level tick: the
 * published vortices, the downbursts in progress and, every other tick, the ambient wind with the outflow of
 * convective storms around each player.
 *
 * <p>Every push goes through {@link ForceRules#apply}: the velocity changes silently and the entity tracker
 * resends it on a slow cadence. The mod sends no motion packet of its own and never marks an entity as hurt, so
 * no player is ever snapped back. All passes together push at most
 * {@link StormRuntime#MAX_FORCED_ENTITIES_PER_LEVEL_TICK} entities per level tick, and the budget is paid only
 * for entities that were really pushed.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class EntityForces {
    /** A downburst pushes entities this far above and below the surface under its centre. */
    private static final double DOWNBURST_VERTICAL_REACH_BLOCKS = 32.0;
    private static final EntityTypeTest<Entity, Entity> ANY_ENTITY = EntityTypeTest.forClass(Entity.class);

    private EntityForces() {
    }

    /**
     * One pass per level tick, called by the simulator after the family loop. Does nothing unless the destruction
     * mode lets storms affect entities. Then, in this order:
     * <ol>
     * <li>every published vortex that acts on the world burns what stands inside a fire whirl, throws the crew out
     * of a boat inside a waterspout, and pushes the entities in its field with {@link VortexField#acceleration},
     * scaled by their mass; an entity the field reaches is stamped for the fall damage policy and, while it is
     * lifted, has its fall distance cleared;</li>
     * <li>every downburst in progress pushes the entities within its reach with
     * {@link WindForce#addDownburst};</li>
     * <li>every {@link ForceRules#WIND_FORCE_INTERVAL_TICKS} ticks the ambient wind plus the outflow of the
     * convective storms pushes the entities under open sky near each player, each entity once.</li>
     * </ol>
     *
     * @param types resolves a storm to its weather type, or to {@code null} when the type is unknown
     * @param tick  the weather tick of the dimension, which downbursts are timed in
     */
    public static void tickLevel(ServerLevel level, DimensionWeatherData data,
                                 Function<StormInstance, WeatherType> types, int tick) {
        if (!WeatherManager.destructionMode().affectsEntities()) {
            return;
        }
        StormRuntime runtime = data.runtime();
        boolean windDue = tick % ForceRules.WIND_FORCE_INTERVAL_TICKS == 0 && !level.players().isEmpty();
        if (runtime.vortices().isEmpty() && runtime.downbursts().isEmpty() && !windDue) {
            return;
        }
        List<Entity> found = new ArrayList<>();
        Vector3d acceleration = new Vector3d();
        for (ActiveVortex vortex : runtime.vortices()) {
            if (vortex.actsOnWorld()) {
                pushWithVortex(level, runtime, vortex, tick, found, acceleration);
            }
        }
        for (ActiveDownburst downburst : runtime.downbursts()) {
            if (!downburst.isOver(tick)) {
                pushWithDownburst(level, runtime, downburst, tick, found, acceleration);
            }
        }
        if (windDue) {
            pushWithWind(level, data, types, tick, found, acceleration);
        }
    }

    /**
     * Applies the fall damage policy to a living entity that lands on the server within
     * {@link ForceRules#VORTEX_FALL_GRACE_TICKS} of its last contact with a vortex: the damage multiplier becomes
     * {@link ForceRules#fallMultiplier} of the configured mode, and a multiplier of zero cancels the fall. An
     * entity that never met a vortex carries no stamp and is left alone.
     */
    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        CompoundTag persistent = entity.getPersistentData();
        if (!persistent.contains(ForceRules.VORTEX_STAMP_KEY, Tag.TAG_LONG)) {
            return;
        }
        long stamp = persistent.getLong(ForceRules.VORTEX_STAMP_KEY);
        if (!ForceRules.recentlyInVortex(stamp, entity.level().getGameTime())) {
            return;
        }
        float multiplier = ForceRules.fallMultiplier(WeatherManager.tornadoFallDamage(), event.getDamageMultiplier());
        if (multiplier <= 0.0F) {
            event.setCanceled(true);
        } else {
            event.setDamageMultiplier(multiplier);
        }
    }

    private static void pushWithVortex(ServerLevel level, StormRuntime runtime, ActiveVortex vortex, int tick,
                                       List<Entity> found, Vector3d acceleration) {
        int budget = Math.min(ForceRules.MAX_FORCED_ENTITIES_PER_STORM_TICK, runtime.forcedLeft());
        if (budget <= 0) {
            return;
        }
        VortexParams params = vortex.params();
        FunnelSpine spine = vortex.spine();
        boolean burns = params.kind().setsFire();
        boolean throwsCrews = params.kind() == VortexKind.WATERSPOUT;
        double centreX = vortex.x();
        double centreZ = vortex.z();
        double baseX = vortex.baseX();
        double baseZ = vortex.baseZ();
        double reach = VortexField.influenceRadius(params, spine.strength());
        AABB box = new AABB(baseX - reach, spine.groundY() - VortexField.REACH_BELOW_BLOCKS, baseZ - reach,
            baseX + reach, spine.topY() + VortexField.REACH_ABOVE_BLOCKS, baseZ + reach);
        found.clear();
        level.getEntities(ANY_ENTITY, box, entity -> inBox(box, entity) && concerns(entity, burns, throwsCrews),
            found, budget);

        boolean playersPushed = WeatherManager.pushPlayers();
        long gameTime = level.getGameTime();
        int pushed = 0;
        for (Entity entity : found) {
            double x = entity.getX();
            double y = entity.getY();
            double z = entity.getZ();
            if (burns && !entity.fireImmune() && ForceRules.insideFunnel(spine, centreX, centreZ, x, y, z)) {
                entity.setRemainingFireTicks(
                    Math.max(entity.getRemainingFireTicks(), ForceRules.FIRE_WHIRL_BURN_TICKS));
            }
            if (throwsCrews && entity instanceof Boat && entity.isVehicle()
                && ForceRules.insideFunnel(spine, centreX, centreZ, x, y, z)) {
                entity.ejectPassengers();
            }
            ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            if ((player != null || steeredByPlayer(entity)) && !playersPushed) {
                continue;
            }
            Vec3 velocity = entity.getKnownMovement();
            float exposure = VortexField.exposure(params.tier(), ForceRules.underOpenSky(level, entity));
            if (!VortexField.acceleration(params, spine, centreX, centreZ, x, y, z, velocity.x, velocity.y,
                velocity.z, exposure, acceleration)) {
                continue;
            }
            entity.getPersistentData().putLong(ForceRules.VORTEX_STAMP_KEY, gameTime);
            if (acceleration.y > 0.0) {
                entity.resetFallDistance();
            }
            if (player != null) {
                // The server does not simulate a player. Its copy of the velocity is kept at what the client last
                // reported, so that the motion packet vanilla sends when the player is hurt carries the orbit.
                player.setDeltaMovement(player.getKnownMovement());
            } else if (pushable(entity)) {
                float mass = ForceRules.mass(entity);
                ForceRules.apply(entity, acceleration.x * mass, acceleration.y * mass, acceleration.z * mass, tick);
                pushed++;
            }
        }
        runtime.spendForced(pushed);
    }

    private static void pushWithDownburst(ServerLevel level, StormRuntime runtime, ActiveDownburst downburst,
                                          int tick, List<Entity> found, Vector3d acceleration) {
        float envelope = downburst.envelope(tick);
        int budget = Math.min(ForceRules.MAX_FORCED_ENTITIES_PER_STORM_TICK, runtime.forcedLeft());
        if (!(envelope > 0.0F) || budget <= 0) {
            return;
        }
        double centreX = downburst.x();
        double centreZ = downburst.z();
        OptionalInt surface = GroundFinder.groundY(level, Mth.floor(centreX), Mth.floor(centreZ),
            surfaceHint(level, centreX, centreZ));
        if (surface.isEmpty()) {
            return;
        }
        double surfaceY = surface.getAsInt();
        double reach = (double) downburst.radius() * WindForce.DOWNBURST_REACH_FACTOR;
        double reachSqr = reach * reach;
        AABB box = new AABB(centreX - reach, surfaceY - DOWNBURST_VERTICAL_REACH_BLOCKS, centreZ - reach,
            centreX + reach, surfaceY + DOWNBURST_VERTICAL_REACH_BLOCKS, centreZ + reach);
        found.clear();
        level.getEntities(ANY_ENTITY, box, entity -> inBox(box, entity)
            && horizontalDistanceSqr(entity, centreX, centreZ) <= reachSqr && pushable(entity), found, budget);

        int pushed = 0;
        for (Entity entity : found) {
            acceleration.set(0.0, 0.0, 0.0);
            WindForce.addDownburst(centreX, centreZ, downburst.radius(), downburst.speed(), envelope,
                entity.getX(), entity.getZ(), acceleration);
            if (acceleration.x == 0.0 && acceleration.y == 0.0 && acceleration.z == 0.0) {
                continue;
            }
            float mass = ForceRules.mass(entity);
            ForceRules.apply(entity, acceleration.x * mass, acceleration.y * mass, acceleration.z * mass, tick);
            pushed++;
        }
        runtime.spendForced(pushed);
    }

    private static void pushWithWind(ServerLevel level, DimensionWeatherData data,
                                     Function<StormInstance, WeatherType> types, int tick, List<Entity> found,
                                     Vector3d acceleration) {
        StormRuntime runtime = data.runtime();
        if (runtime.forcedLeft() <= 0) {
            return;
        }
        WindState ambient = data.wind();
        float gustSpeed = ambient.gustSpeed();
        List<Outflow> outflows = outflows(data, types);
        float strongest = gustSpeed;
        for (Outflow outflow : outflows) {
            strongest += outflow.speed();
        }
        if (!(strongest > WindForce.PUSH_THRESHOLD_BLOCKS_PER_SECOND)) {
            return;
        }

        double rangeSqr = ForceRules.WIND_FORCE_PLAYER_RANGE_BLOCKS * ForceRules.WIND_FORCE_PLAYER_RANGE_BLOCKS;
        Vector3d wind = new Vector3d();
        IntOpenHashSet seen = new IntOpenHashSet();
        for (ServerPlayer player : level.players()) {
            int left = runtime.forcedLeft();
            if (left <= 0) {
                return;
            }
            wind.set(0.0, 0.0, 0.0);
            WindForce.addWind(ambient.directionDegrees(), gustSpeed, wind);
            for (Outflow outflow : outflows) {
                StormInstance storm = outflow.storm();
                double distance = Math.sqrt(horizontalDistanceSqr(player, storm.x(), storm.z()));
                WindForce.addOutflow(storm.headingDegrees(), outflow.speed(),
                    Coverage.coverage(storm.intensity(), distance, storm.radius()), wind);
            }
            WindForce.toAcceleration(wind, acceleration);
            if (acceleration.x == 0.0 && acceleration.z == 0.0) {
                continue;
            }
            double ax = acceleration.x * ForceRules.WIND_FORCE_INTERVAL_TICKS;
            double az = acceleration.z * ForceRules.WIND_FORCE_INTERVAL_TICKS;
            found.clear();
            level.getEntities(ANY_ENTITY, player.getBoundingBox().inflate(ForceRules.WIND_FORCE_PLAYER_RANGE_BLOCKS),
                entity -> entity.distanceToSqr(player) <= rangeSqr && !seen.contains(entity.getId())
                    && pushable(entity) && ForceRules.underOpenSky(level, entity), found, left);
            for (Entity entity : found) {
                seen.add(entity.getId());
                float mass = ForceRules.mass(entity);
                ForceRules.apply(entity, ax * mass, 0.0, az * mass, tick);
            }
            runtime.spendForced(found.size());
        }
    }

    /** The convective storms whose outflow can add to the wind somewhere, each with its type's outflow speed. */
    private static List<Outflow> outflows(DimensionWeatherData data, Function<StormInstance, WeatherType> types) {
        List<Outflow> outflows = List.of();
        for (StormInstance storm : data.storms()) {
            WeatherType type = types.apply(storm);
            if (type == null || !(storm.intensity() > 0.0F)) {
                continue;
            }
            Optional<ConvectiveParams> convective = type.convective();
            if (convective.isEmpty() || !(convective.get().outflowSpeed() > 0.0F)) {
                continue;
            }
            if (outflows.isEmpty()) {
                outflows = new ArrayList<>();
            }
            outflows.add(new Outflow(storm, convective.get().outflowSpeed()));
        }
        return outflows;
    }

    /**
     * Where the surface under a downburst is expected, which only matters under a ceiling: the height of the
     * player nearest to its centre, or the sea level without players.
     */
    private static int surfaceHint(ServerLevel level, double x, double z) {
        int hint = level.getSeaLevel();
        if (!level.dimensionType().hasCeiling()) {
            return hint;
        }
        double nearestSqr = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double distanceSqr = horizontalDistanceSqr(player, x, z);
            if (distanceSqr < nearestSqr) {
                nearestSqr = distanceSqr;
                hint = player.getBlockY();
            }
        }
        return hint;
    }

    /**
     * Whether a vortex has anything to do with an entity in its box: players and what they steer (their own
     * client pushes them, the server burns them, stamps them for the fall damage policy and keeps a player's
     * velocity current), everything the server may push, whatever can burn in a fire whirl and every boat in a
     * waterspout. Spectators and dead entities never.
     */
    private static boolean concerns(Entity entity, boolean burns, boolean throwsCrews) {
        if (!entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        return entity instanceof ServerPlayer || steeredByPlayer(entity) || pushable(entity)
            || (burns && !entity.fireImmune()) || (throwsCrews && entity instanceof Boat);
    }

    /**
     * Whether a player steers the entity, so that the player's client moves it. The server still moves it by the
     * packets of that client and counts its falls, which is why the field stamps it and clears its fall distance
     * as it does for the player.
     */
    private static boolean steeredByPlayer(Entity entity) {
        return entity.getControllingPassenger() instanceof Player;
    }

    private static boolean pushable(Entity entity) {
        return ForceRules.mayBePushed(entity) && ForceRules.categoryEnabled(entity);
    }

    /** Entity queries reach a little beyond their box, so every predicate tests the box again. */
    private static boolean inBox(AABB box, Entity entity) {
        return box.contains(entity.getX(), entity.getY(), entity.getZ());
    }

    private static double horizontalDistanceSqr(Entity entity, double x, double z) {
        double dx = entity.getX() - x;
        double dz = entity.getZ() - z;
        return dx * dx + dz * dz;
    }

    /** A convective storm and the outflow speed of its type, in blocks per second at full coverage. */
    private record Outflow(StormInstance storm, float speed) {
    }
}
