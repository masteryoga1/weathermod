package com.weathermod.server;

import com.weathermod.core.WeatherType;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;

/**
 * A storm a family system wants spawned after the family loop. {@code lifetimeTicks} 0 keeps the lifetime the type
 * picks; an empty {@code familyTag} leaves the child's family state at its defaults. Family systems hand these to
 * {@link DimensionWeatherData#queueChild} because the storm list must not change while it is being ticked.
 *
 * @param parentId      id of the storm that asks for the child
 * @param type          the child's weather type
 * @param x             the child's centre along {@code X}
 * @param z             the child's centre along {@code Z}
 * @param lifetimeTicks the child's lifetime in ticks, or 0 to keep the one its type picks; never negative
 * @param familyTag     the family state the child is born with, or an empty tag for the defaults
 */
public record ChildSpawn(int parentId, ResourceKey<WeatherType> type, double x, double z, int lifetimeTicks,
                         CompoundTag familyTag) {
    public ChildSpawn {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(familyTag, "familyTag");
        lifetimeTicks = Math.max(0, lifetimeTicks);
    }

    /** A child with the type's own lifetime and a default family state. */
    public ChildSpawn(int parentId, ResourceKey<WeatherType> type, double x, double z) {
        this(parentId, type, x, z, 0, new CompoundTag());
    }
}
