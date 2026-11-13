package com.weathermod.server;

import net.minecraft.nbt.CompoundTag;

/**
 * Typed per-storm state of a family system, persisted inside the storm under {@code "Family"}. A storm keeps the
 * tag it was loaded with until its family system first asks for the state through
 * {@link StormInstance#familyState}, so a storm whose family never runs is saved back unchanged.
 */
public interface FamilyState {
    /** Writes the state to a new tag. Keys are private to the family. */
    CompoundTag save();
}
