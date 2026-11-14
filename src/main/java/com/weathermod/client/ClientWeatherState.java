package com.weathermod.client;

import com.weathermod.WeatherMod;
import com.weathermod.core.Headings;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.network.ClimateUpdatePayload;
import com.weathermod.network.DownburstPayload;
import com.weathermod.network.FunnelSpinePayload;
import com.weathermod.network.LightningStrikePayload;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.StormSnapshot;
import com.weathermod.network.WindUpdatePayload;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The client's view of the weather in its current dimension: every storm the server has sent with the funnel of
 * each vortex among them, the downbursts in progress, the lightning strikes of the last minute, the latest wind
 * and the latest climate at the player. Payload handlers feed it on the main thread, the client tick ages and
 * moves the storms between updates and drops the ones the server has stopped updating, and leaving the level
 * (logging out, a dimension change or a reconfiguration) clears everything, since the server sends the new
 * dimension's full state right after the respawn and a stale climate must not count as the mod being active
 * there. While the server's tick rate manager has frozen the game the mirror does not tick either, so nothing
 * drifts away or goes stale under {@code /tick freeze}. Game-bus events only touch {@link #INSTANCE}; tests build
 * their own instances.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID, value = Dist.CLIENT)
public final class ClientWeatherState {
    /** The state used by the running client. */
    public static final ClientWeatherState INSTANCE = new ClientWeatherState();

    /**
     * Client ticks without a server update after which a storm is dropped. The server re-sends every storm within a
     * player's interest radius at least every {@code syncIntervalTicks} (at most 100), so a storm this stale has
     * left the player's interest radius and no removal payload will come for it.
     */
    public static final int STALE_TICKS = 400;

    /** Strikes are kept this many client ticks, one minute, for the overlay and for matching vanilla thunder. */
    public static final int STRIKE_MEMORY_TICKS = 1200;

    /** At most this many strikes are remembered; a newer one pushes the oldest out. */
    public static final int MAX_REMEMBERED_STRIKES = 64;

    private static final Consumer<ClientStrike> NO_STRIKE_LISTENER = strike -> { };

    private final Map<Integer, ClientStorm> storms = new LinkedHashMap<>();
    private final Collection<ClientStorm> stormView = Collections.unmodifiableCollection(storms.values());
    private final Map<Integer, ClientFunnel> funnels = new LinkedHashMap<>();
    private final Collection<ClientFunnel> funnelView = Collections.unmodifiableCollection(funnels.values());
    private final List<ClientDownburst> downbursts = new ArrayList<>();
    private final List<ClientDownburst> downburstView = Collections.unmodifiableList(downbursts);
    private final List<ClientStrike> strikes = new ArrayList<>();
    private final List<ClientStrike> strikeView = Collections.unmodifiableList(strikes);
    private Consumer<ClientStrike> strikeListener = NO_STRIKE_LISTENER;
    private float windDirectionDegrees;
    private float windSpeed;
    private float windGustSpeed;
    private boolean windKnown;
    private float temperature;
    private float humidity;
    private PrecipitationKind precipitationKind = PrecipitationKind.NONE;
    private String seasonName = ClimateUpdatePayload.NO_SEASON;
    private float seasonProgress;
    private boolean climateKnown;
    private long ticks;

    ClientWeatherState() {
    }

    /**
     * Ticks the mirror once per client tick while a level exists, the game is not paused and the level's tick
     * rate manager runs normally. A server frozen with {@code /tick freeze} sends no updates, so a mirror that
     * kept ticking would dead-reckon its storms away from their real positions and drop them as stale.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !minecraft.isPaused() && minecraft.level.tickRateManager().runsNormally()) {
            INSTANCE.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.clear();
    }

    /** The client level is replaced on a dimension change and nulled on a reconfiguration without a logout. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            INSTANCE.clear();
        }
    }

    /**
     * Replaces every known storm with the list the server sent and forgets the wind and the climate until the next
     * updates; an empty list forgets all storms. A funnel whose storm is not in the list goes with it. The server
     * sends the list whenever the player enters a dimension and follows it with the wind and the climate where
     * weather is enabled, so a dimension without weather ends up showing none of them.
     */
    public void apply(StormListPayload payload) {
        Objects.requireNonNull(payload, "payload");
        Map<Integer, ClientStorm> previous = new LinkedHashMap<>(storms);
        storms.clear();
        for (StormSnapshot snapshot : payload.storms()) {
            ClientStorm existing = previous.get(snapshot.id());
            if (existing != null) {
                existing.update(snapshot);
                storms.put(existing.id(), existing);
            } else {
                storms.put(snapshot.id(), new ClientStorm(snapshot));
            }
        }
        funnels.keySet().retainAll(storms.keySet());
        forgetWind();
        forgetClimate();
    }

    /** Adds the storm when its id is new and updates it otherwise. */
    public void apply(StormDeltaPayload payload) {
        Objects.requireNonNull(payload, "payload");
        apply(payload.storm());
    }

    /** Adds or updates one storm from a snapshot and returns the client storm that now holds it. */
    public ClientStorm apply(StormSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ClientStorm existing = storms.get(snapshot.id());
        if (existing == null) {
            ClientStorm created = new ClientStorm(snapshot);
            storms.put(created.id(), created);
            return created;
        }
        existing.update(snapshot);
        return existing;
    }

    /** Drops the storm named by the payload, and its funnel; unknown ids are ignored. */
    public void apply(StormRemovedPayload payload) {
        Objects.requireNonNull(payload, "payload");
        remove(payload.id());
    }

    /**
     * Creates the funnel of a storm from its first keyframe or hands a later keyframe to the funnel that exists.
     * A spine for a storm the client does not know is ignored: the server bundles the spine behind the storm's
     * delta, so an unknown id means the storm has been dropped here in the meantime.
     */
    public void apply(FunnelSpinePayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!storms.containsKey(payload.stormId())) {
            return;
        }
        ClientFunnel existing = funnels.get(payload.stormId());
        if (existing == null) {
            funnels.put(payload.stormId(), new ClientFunnel(payload.stormId(), payload.spine()));
        } else {
            existing.accept(payload.spine(), payload.intervalTicks());
        }
    }

    /**
     * Remembers the strike, stamped with the current client tick, then calls the strike listener with it. When
     * {@link #MAX_REMEMBERED_STRIKES} are remembered already the oldest makes room.
     */
    public void apply(LightningStrikePayload payload) {
        Objects.requireNonNull(payload, "payload");
        ClientStrike strike = new ClientStrike(payload.x(), payload.y(), payload.z(), payload.power(), ticks);
        while (strikes.size() >= MAX_REMEMBERED_STRIKES) {
            strikes.remove(0);
        }
        strikes.add(strike);
        strikeListener.accept(strike);
    }

    /** Starts mirroring a downburst; it ages with the client ticks and is dropped when it is over. */
    public void apply(DownburstPayload payload) {
        Objects.requireNonNull(payload, "payload");
        downbursts.add(new ClientDownburst(payload));
    }

    /** Stores the dimension's wind, normalising the direction to {@code [0, 360)}. */
    public void apply(WindUpdatePayload payload) {
        Objects.requireNonNull(payload, "payload");
        setWind(payload.directionDegrees(), payload.speed(), payload.gustSpeed());
    }

    /** Stores the climate at the player as the server sampled it. */
    public void apply(ClimateUpdatePayload payload) {
        Objects.requireNonNull(payload, "payload");
        setClimate(payload.temperature(), payload.humidity(), payload.kind(), payload.seasonName(),
            payload.seasonProgress());
    }

    /**
     * Sets a wind without a gust: the gust speed becomes the sustained speed. See
     * {@link #setWind(float, float, float)}.
     */
    public void setWind(float directionDegrees, float speed) {
        setWind(directionDegrees, speed, speed);
    }

    /**
     * Sets the wind directly, normalising the direction to {@code [0, 360)}, clamping the speed at zero and raising
     * the gust speed to at least the sustained speed.
     */
    public void setWind(float directionDegrees, float speed, float gustSpeed) {
        this.windDirectionDegrees = Headings.wrapDegrees(directionDegrees);
        this.windSpeed = Math.max(0.0F, speed);
        this.windGustSpeed = Math.max(this.windSpeed, gustSpeed);
        this.windKnown = true;
    }

    /**
     * Sets the climate directly, clamping the humidity and the season progress into {@code [0, 1]}.
     *
     * @param temperature    the temperature on the vanilla biome scale
     * @param humidity       the humidity, 0 to 1
     * @param kind           what falls at that temperature
     * @param seasonName     the lower-case season name, {@link ClimateUpdatePayload#NO_SEASON} for none
     * @param seasonProgress position in the seasonal cycle, 0 to 1
     */
    public void setClimate(float temperature, float humidity, PrecipitationKind kind, String seasonName,
                           float seasonProgress) {
        this.temperature = temperature;
        this.humidity = clampUnit(humidity);
        this.precipitationKind = Objects.requireNonNull(kind, "kind");
        this.seasonName = Objects.requireNonNull(seasonName, "seasonName");
        this.seasonProgress = clampUnit(seasonProgress);
        this.climateKnown = true;
    }

    /** Removes one storm together with its funnel and reports whether the storm was known. */
    public boolean remove(int id) {
        funnels.remove(id);
        return storms.remove(id) != null;
    }

    /**
     * Sets who is told about every strike right after it has been remembered. The thunder sounds hook in here;
     * the default listener does nothing, and {@code null} restores it. The listener survives {@link #clear()}.
     */
    public void setStrikeListener(@Nullable Consumer<ClientStrike> listener) {
        this.strikeListener = listener == null ? NO_STRIKE_LISTENER : listener;
    }

    /**
     * Forgets every storm, funnel, downburst and strike, the wind and the climate, as happens when the client
     * leaves a world or its level is replaced.
     */
    public void clear() {
        storms.clear();
        funnels.clear();
        downbursts.clear();
        strikes.clear();
        forgetWind();
        forgetClimate();
        ticks = 0L;
    }

    /**
     * Advances every storm, funnel and downburst by one client tick. Storms that have gone more than
     * {@link #STALE_TICKS} ticks without a server update are dropped with their funnels (a dropped storm comes
     * back with the next delta if the player re-enters its interest radius), downbursts that are over are
     * dropped, and so are strikes older than {@link #STRIKE_MEMORY_TICKS}.
     */
    public void tick() {
        for (ClientStorm storm : storms.values()) {
            storm.tick();
        }
        if (storms.values().removeIf(storm -> storm.ticksSinceUpdate() > STALE_TICKS)) {
            funnels.keySet().retainAll(storms.keySet());
        }
        for (ClientFunnel funnel : funnels.values()) {
            funnel.tick();
        }
        for (ClientDownburst downburst : downbursts) {
            downburst.tick();
        }
        downbursts.removeIf(ClientDownburst::isOver);
        ticks++;
        while (!strikes.isEmpty() && ticks - strikes.get(0).clientTick() > STRIKE_MEMORY_TICKS) {
            strikes.remove(0);
        }
    }

    /** Read-only live view of the known storms, in the order they were first seen. */
    public Collection<ClientStorm> storms() {
        return stormView;
    }

    /** The storm with the given id, if the client knows it. */
    public Optional<ClientStorm> storm(int id) {
        return Optional.ofNullable(storms.get(id));
    }

    /** The funnel of the storm with the given id, if that storm is known and the server has sent its spine. */
    public Optional<ClientFunnel> funnel(int stormId) {
        return Optional.ofNullable(funnels.get(stormId));
    }

    /** Read-only live view of the known funnels, in the order their first keyframes arrived. */
    public Collection<ClientFunnel> funnels() {
        return funnelView;
    }

    /** Read-only live view of the downbursts in progress, in the order they started. */
    public List<ClientDownburst> downbursts() {
        return downburstView;
    }

    /** Read-only live view of the strikes of the last {@link #STRIKE_MEMORY_TICKS} ticks, oldest first. */
    public List<ClientStrike> recentStrikes() {
        return strikeView;
    }

    /** Number of storms the client currently knows. */
    public int stormCount() {
        return storms.size();
    }

    /** Whether the client knows no storms. */
    public boolean isEmpty() {
        return storms.isEmpty();
    }

    /** Compass direction the wind blows toward, in degrees in {@code [0, 360)}; 0 until the first wind update. */
    public float windDirectionDegrees() {
        return windDirectionDegrees;
    }

    /** Sustained wind speed in blocks per second; 0 until the first wind update. */
    public float windSpeed() {
        return windSpeed;
    }

    /**
     * Momentary gust speed in blocks per second, never below {@link #windSpeed()}; equal to it while no gust blows
     * and 0 until the first wind update.
     */
    public float windGustSpeed() {
        return windGustSpeed;
    }

    /** Whether the last wind update carried a gust, that is a gust speed above the sustained speed. */
    public boolean isWindGusting() {
        return windKnown && windGustSpeed > windSpeed;
    }

    /** Whether a wind update has arrived since the state was last cleared or last received a storm list. */
    public boolean hasWind() {
        return windKnown;
    }

    /** Temperature at the player on the vanilla biome scale; 0 until the first climate update. */
    public float temperature() {
        return temperature;
    }

    /** Humidity at the player in {@code [0, 1]}; 0 until the first climate update. */
    public float humidity() {
        return humidity;
    }

    /** What falls at the player's temperature; {@link PrecipitationKind#NONE} until the first climate update. */
    public PrecipitationKind precipitationKind() {
        return precipitationKind;
    }

    /** Lower-case season name at the player; {@link ClimateUpdatePayload#NO_SEASON} until the first climate update. */
    public String seasonName() {
        return seasonName;
    }

    /** Position in the seasonal cycle in {@code [0, 1]}; 0 until the first climate update. */
    public float seasonProgress() {
        return seasonProgress;
    }

    /** Whether a climate update has arrived since the state was last cleared or last received a storm list. */
    public boolean hasClimate() {
        return climateKnown;
    }

    /** Whether the known climate names a season rather than {@link ClimateUpdatePayload#NO_SEASON}. */
    public boolean hasSeason() {
        return climateKnown && !ClimateUpdatePayload.NO_SEASON.equals(seasonName);
    }

    /** Client ticks applied since the state was last cleared. */
    public long ticks() {
        return ticks;
    }

    private void forgetWind() {
        windDirectionDegrees = 0.0F;
        windSpeed = 0.0F;
        windGustSpeed = 0.0F;
        windKnown = false;
    }

    private void forgetClimate() {
        temperature = 0.0F;
        humidity = 0.0F;
        precipitationKind = PrecipitationKind.NONE;
        seasonName = ClimateUpdatePayload.NO_SEASON;
        seasonProgress = 0.0F;
        climateKnown = false;
    }

    private static float clampUnit(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
