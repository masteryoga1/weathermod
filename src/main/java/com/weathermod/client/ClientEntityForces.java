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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
 * the player for floating.
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
     * {@link ForceRules#underOpenSky}), every downburst in progress ({@link WindForce#addDownburst}) and, under
     * open sky only, the ambient gust wind plus the outflow of the convective storms. The upward part of the
     * funnels is dropped on the ticks {@link LiftPulse#allowLift} refuses.
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
        if (!liftAllowed(target, inField, lastVerticalMove)) {
            lift = 0.0;
        }

        for (ClientDownburst downburst : state.downbursts()) {
            WindForce.addDownburst(downburst.x(), downburst.z(), downburst.radius(), downburst.speed(),
                WindForce.downburstEnvelope(downburst.ageTicks(), downburst.durationTicks()), x, z, sum);
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
     * Advances the lift pulse by one tick and says whether the funnels may lift the body in it. A body that
     * stands on the ground or lies in a fluid counts as resting: the server does not count such a tick as
     * floating either, and a boat on the water or a swimmer would otherwise use its pulse up before the funnel
     * arrives. Outside every field nothing lifts, but a streak that began inside one is counted on until the body
     * lands or descends, as the server counts it on; a body that never began one keeps a fresh pulse.
     */
    private static boolean liftAllowed(Entity target, boolean inField, double lastVerticalMove) {
        if (!inField && LIFT_PULSE.streak() == 0) {
            LIFT_PULSE.reset();
            return false;
        }
        boolean resting = target.onGround() || target.isInFluidType();
        boolean allowed = LIFT_PULSE.allowLift(resting, lastVerticalMove, target.getGravity());
        return inField && allowed;
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
