package com.weathermod.client;

import com.weathermod.WeatherMod;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.Coverage;
import com.weathermod.core.ModTags;
import com.weathermod.core.VortexField;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WindForce;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.impact.ForceRules;
import java.lang.ref.WeakReference;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * The client's share of pushing entities: each side pushes what it simulates. Minecraft simulates the local
 * player, and a vehicle the local player steers, on the client, so this class pushes them there, with the same
 * pure functions the server uses for everything else ({@link VortexField}, {@link WindForce}) and the weather the
 * server synced into {@link ClientWeatherState}. No packet is involved: the motion reaches the server with the
 * player's own move packets, so the player is never snapped back.
 *
 * <p>The lift of a vortex is passed through a {@link LiftPulse}, which pauses it before the server would take
 * the player for floating. A steered vehicle is carried with short pauses. A player on foot is lifted in hops,
 * because the server counts every tick such a player spends in the air, except while the player glides on an
 * elytra or may fly, which the server does not count. The host of an integrated server is never paused: that
 * server allows flight and kicks nobody for floating.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientEntityForces {
    /** Forces are computed at the start of a tick, where the mirrored storms stand at the end of theirs. */
    private static final float END_OF_TICK = 1.0F;
    private static final LiftPulse LIFT_PULSE = new LiftPulse();

    /** The body pushed in the last tick, held weakly so that a level left behind is not kept alive. */
    private static WeakReference<Entity> tracked = new WeakReference<>(null);
    private static double trackedY;

    private ClientEntityForces() {
    }

    /**
     * Pushes the local player, or the vehicle the local player steers, at the start of the player's tick. Nothing
     * happens while the game is paused or frozen with {@code /tick freeze} (players keep ticking then, the
     * weather stands still), before the server's config has arrived, when that config turns the pushing of
     * players off or lets storms affect no entity, and for a player who is dead, a spectator or flying by
     * ability. A passenger who does not steer is carried by the vehicle and not pushed, and a body whose type,
     * or the type of anything riding it, is in {@code #weathermod:wind_immune} is left alone.
     *
     * <p>The push is the sum, at the body's position and velocity, of every funnel whose storm and type are known
     * ({@link VortexField#acceleration}, with the shelter of a roof taken from
     * {@link ForceRules#underOpenSky}), every downburst in progress that reaches the body's height
     * ({@link WindForce#addDownburst}) and, under open sky only, the ambient gust wind plus the outflow of the
     * convective storms. The upward part of the funnels is dropped on the ticks the {@link LiftPulse} refuses.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (!player.isLocalPlayer()) {
            return;
        }
        Level level = player.level();
        if (Minecraft.getInstance().isPaused() || !level.tickRateManager().runsNormally()) {
            forgetTarget();
            return;
        }
        Entity target = mayPush(player) ? targetOf(player) : null;
        if (target == null) {
            forgetTarget();
            return;
        }
        double lastVerticalMove = verticalMoveOf(target);

        ClientWeatherState state = ClientWeatherState.INSTANCE;
        Registry<WeatherType> types = WeatherRegistries.optionalRegistry(level.registryAccess()).orElse(null);
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        Vec3 velocity = target.getDeltaMovement();
        boolean openSky = ForceRules.underOpenSky(level, target);
        Vector3d sum = new Vector3d();
        Vector3d part = new Vector3d();

        boolean inField = false;
        double lift = 0.0;
        if (types != null) {
            for (ClientFunnel funnel : state.funnels()) {
                ClientStorm storm = state.storm(funnel.stormId()).orElse(null);
                WeatherType type = storm == null ? null : types.get(storm.typeId());
                if (type == null || type.vortex().isEmpty()) {
                    continue;
                }
                VortexParams params = type.vortex().get();
                float exposure = VortexField.exposure(params.tier(), openSky);
                if (VortexField.acceleration(params, funnel.spine(END_OF_TICK), storm.getX(END_OF_TICK),
                    storm.getZ(END_OF_TICK), x, y, z, velocity.x, velocity.y, velocity.z, exposure, part)) {
                    inField = true;
                    sum.add(part.x, Math.min(part.y, 0.0), part.z);
                    lift += Math.max(part.y, 0.0);
                }
            }
        }
        if (!liftAllowed(target, target == player, inField, lastVerticalMove)) {
            lift = 0.0;
        }

        for (ClientDownburst downburst : state.downbursts()) {
            if (downburstReaches(level, downburst, y)) {
                WindForce.addDownburst(downburst.x(), downburst.z(), downburst.radius(), downburst.speed(),
                    WindForce.downburstEnvelope(downburst.ageTicks(), downburst.durationTicks()), x, z, sum);
            }
        }

        if (openSky) {
            part.set(0.0, 0.0, 0.0);
            WindForce.addWind(state.windDirectionDegrees(), state.windGustSpeed(), part);
            if (types != null) {
                addOutflows(state, types, x, z, part);
            }
            WindForce.toAcceleration(part, part);
            sum.add(part);
        }

        double ay = sum.y + lift;
        if (sum.x != 0.0 || ay != 0.0 || sum.z != 0.0) {
            target.setDeltaMovement(target.getDeltaMovement().add(sum.x, ay, sum.z));
        }
    }

    /**
     * Whether weather pushes this player at all: the server's config is known and lets players be pushed and
     * entities be affected, and the player is alive, no spectator and not flying by ability.
     */
    private static boolean mayPush(Player player) {
        return ServerConfig.SPEC.isLoaded() && WeatherManager.pushPlayers()
            && WeatherManager.destructionMode().affectsEntities()
            && player.isAlive() && !player.isSpectator() && !player.getAbilities().flying;
    }

    /**
     * The body this client simulates for the player: the player on foot, the root vehicle when the player steers
     * it, nothing when the player is only carried. Nothing either for a body that is immune to wind.
     */
    @Nullable
    private static Entity targetOf(Player player) {
        Entity target = player;
        if (player.isPassenger()) {
            target = player.getRootVehicle();
            if (!target.isControlledByLocalInstance()) {
                return null;
            }
        }
        if (target.getType().is(ModTags.EntityTypes.WIND_IMMUNE)) {
            return null;
        }
        for (Entity passenger : target.getIndirectPassengers()) {
            if (passenger.getType().is(ModTags.EntityTypes.WIND_IMMUNE)) {
                return null;
            }
        }
        return target;
    }

    /**
     * How far the body moved up in its last tick, from its height one tick ago as this class saw it. A body seen
     * for the first time (the player mounted, dismounted or changed level) starts a new pulse.
     *
     * <p>The entity's own {@code yo} cannot be used: the level copies the position into it just before it ticks
     * an entity, so for a player on foot it already equals the current height when the tick event fires.
     */
    private static double verticalMoveOf(Entity target) {
        double y = target.getY();
        double move;
        if (tracked.get() == target) {
            move = y - trackedY;
        } else {
            LIFT_PULSE.reset();
            tracked = new WeakReference<>(target);
            move = y - target.yo;
        }
        trackedY = y;
        return move;
    }

    /** Stops following a body: the next one pushed starts a new pulse. */
    private static void forgetTarget() {
        if (tracked.get() != null) {
            tracked = new WeakReference<>(null);
        }
        LIFT_PULSE.reset();
    }

    /**
     * Advances the lift pulse by one tick and says whether the funnels may lift the body in it. The host of an
     * integrated server is lifted without a pause: that server allows flight and never kicks for floating. A
     * client of any other server cannot see whether flight is allowed there and takes it that it is not.
     *
     * <p>A body that stands on the ground or lies in a fluid counts as resting: the server does not count such a
     * tick as floating either, and a boat on the water or a swimmer would otherwise use its pulse up before the
     * funnel arrives.
     *
     * <p>A player on foot is counted from the tick the body leaves the ground, inside a field or not, as the
     * server counts every tick such a player spends in the air whatever the vertical motion, and is lifted again
     * only after resting: the ride on foot is a row of hops. A player the server leaves out of that count (see
     * {@link #neverCountedFloating}) is lifted without a pause, and counted from zero once that ends, as the
     * server counts. A steered vehicle is counted while a field reaches it. Outside every field nothing lifts,
     * but a streak that began inside one is counted on until the vehicle lands or descends, as the server counts
     * it on; a vehicle that never began one keeps a fresh pulse.
     *
     * @param onFoot whether the body is the player itself and not a vehicle the player steers
     */
    private static boolean liftAllowed(Entity target, boolean onFoot, boolean inField, double lastVerticalMove) {
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            LIFT_PULSE.reset();
            return inField;
        }
        boolean resting = target.onGround() || target.isInFluidType();
        if (onFoot) {
            boolean allowed = LIFT_PULSE.allowLiftOnFoot(resting || neverCountedFloating(target),
                target.getGravity());
            return inField && allowed;
        }
        if (!inField && LIFT_PULSE.streak() == 0) {
            LIFT_PULSE.reset();
            return false;
        }
        boolean allowed = LIFT_PULSE.allowLift(resting, lastVerticalMove, target.getGravity());
        return inField && allowed;
    }

    /**
     * Whether the server takes a player on foot for floating in no tick at all, however long the player stays in
     * the air: while gliding on an elytra, while allowed to fly (creative mode, or flight another mod grants),
     * under Levitation and during a riptide spin. The server syncs all four to the player's own client.
     */
    private static boolean neverCountedFloating(Entity target) {
        return target instanceof Player player && (player.isFallFlying() || player.mayFly()
            || player.hasEffect(MobEffects.LEVITATION) || player.isAutoSpinAttack());
    }

    /**
     * Whether a downburst reaches the body's height, by the rule the server pushes the other entities with: no
     * further than {@link WindForce#DOWNBURST_VERTICAL_REACH_BLOCKS} above or below the surface under the
     * downburst's centre. The surface is read from this client's own chunk, one above the top block that stops
     * motion or holds a fluid. The server does not count leaves, with a heightmap that clients are not sent, so
     * under a canopy the two differ by the height of the crown.
     *
     * <p>Where the surface cannot be had the downburst counts as in reach: under a ceiling, where the heightmap
     * reports the roof and the server looks for the floor around a player instead, and while the chunk of the
     * centre is not loaded on this client. Never loads a chunk.
     */
    private static boolean downburstReaches(Level level, ClientDownburst downburst, double y) {
        if (level.dimensionType().hasCeiling()) {
            return true;
        }
        int centreX = Mth.floor(downburst.x());
        int centreZ = Mth.floor(downburst.z());
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(centreX),
            SectionPos.blockToSectionCoord(centreZ));
        if (chunk == null) {
            return true;
        }
        int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, centreX, centreZ) + 1;
        return WindForce.downburstReachesHeight(y, surfaceY);
    }

    /** Adds the outflow of every convective storm of a known type at a point to a wind vector. */
    private static void addOutflows(ClientWeatherState state, Registry<WeatherType> types, double x, double z,
                                    Vector3d wind) {
        for (ClientStorm storm : state.storms()) {
            WeatherType type = types.get(storm.typeId());
            if (type == null || !(storm.intensity() > 0.0F)) {
                continue;
            }
            Optional<ConvectiveParams> convective = type.convective();
            if (convective.isEmpty() || !(convective.get().outflowSpeed() > 0.0F)) {
                continue;
            }
            WindForce.addOutflow(storm.headingDegrees(), convective.get().outflowSpeed(),
                Coverage.coverage(storm.intensity(), storm.distanceTo(x, z), storm.radius()), wind);
        }
    }
}
