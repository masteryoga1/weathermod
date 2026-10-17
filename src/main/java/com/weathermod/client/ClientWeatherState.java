package com.weathermod.client;

import com.weathermod.WeatherMod;
import com.weathermod.core.Headings;
import com.weathermod.network.StormDeltaPayload;
import com.weathermod.network.StormListPayload;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.StormSnapshot;
import com.weathermod.network.WindUpdatePayload;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The client's view of the weather in its current dimension: every storm the server has sent and the latest wind.
 * Payload handlers feed it on the main thread, the client tick ages and moves the storms between updates and drops
 * the ones the server has stopped updating, and logging out clears everything. Game-bus events only touch
 * {@link #INSTANCE}; tests build their own instances.
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

    private final Map<Integer, ClientStorm> storms = new LinkedHashMap<>();
    private final Collection<ClientStorm> stormView = Collections.unmodifiableCollection(storms.values());
    private float windDirectionDegrees;
    private float windSpeed;
    private boolean windKnown;
    private long ticks;

    ClientWeatherState() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !minecraft.isPaused()) {
            INSTANCE.tick();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        INSTANCE.clear();
    }

    /**
     * Replaces every known storm with the list the server sent and forgets the wind until the next wind update; an
     * empty list forgets all storms. The server sends the list whenever the player enters a dimension and follows it
     * with the wind where weather is enabled, so a dimension without weather ends up showing neither.
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
        windDirectionDegrees = 0.0F;
        windSpeed = 0.0F;
        windKnown = false;
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

    /** Drops the storm named by the payload; unknown ids are ignored. */
    public void apply(StormRemovedPayload payload) {
        Objects.requireNonNull(payload, "payload");
        remove(payload.id());
    }

    /** Stores the dimension's wind, normalising the direction to {@code [0, 360)}. */
    public void apply(WindUpdatePayload payload) {
        Objects.requireNonNull(payload, "payload");
        setWind(payload.directionDegrees(), payload.speed());
    }

    /** Sets the wind directly, normalising the direction to {@code [0, 360)} and clamping the speed at zero. */
    public void setWind(float directionDegrees, float speed) {
        this.windDirectionDegrees = Headings.wrapDegrees(directionDegrees);
        this.windSpeed = Math.max(0.0F, speed);
        this.windKnown = true;
    }

    /** Removes one storm and reports whether it was known. */
    public boolean remove(int id) {
        return storms.remove(id) != null;
    }

    /** Forgets every storm and the wind, as happens when the client leaves a world. */
    public void clear() {
        storms.clear();
        windDirectionDegrees = 0.0F;
        windSpeed = 0.0F;
        windKnown = false;
        ticks = 0L;
    }

    /**
     * Advances every storm by one client tick and drops the storms that have gone more than {@link #STALE_TICKS}
     * ticks without a server update. A dropped storm comes back with the next delta if the player re-enters its
     * interest radius.
     */
    public void tick() {
        for (ClientStorm storm : storms.values()) {
            storm.tick();
        }
        storms.values().removeIf(storm -> storm.ticksSinceUpdate() > STALE_TICKS);
        ticks++;
    }

    /** Read-only live view of the known storms, in the order they were first seen. */
    public Collection<ClientStorm> storms() {
        return stormView;
    }

    /** The storm with the given id, if the client knows it. */
    public Optional<ClientStorm> storm(int id) {
        return Optional.ofNullable(storms.get(id));
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

    /** Wind speed in blocks per second; 0 until the first wind update. */
    public float windSpeed() {
        return windSpeed;
    }

    /** Whether a wind update has arrived since the state was last cleared or last received a storm list. */
    public boolean hasWind() {
        return windKnown;
    }

    /** Client ticks applied since the state was last cleared. */
    public long ticks() {
        return ticks;
    }
}
