package com.weathermod.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * How much a storm is allowed to change the world. Chosen by the server config and read by the impact
 * code before it rips blocks or pushes entities.
 */
public enum DestructionMode implements TranslatableEnum {
    /** Blocks are ripped, entities are pushed and lightning does damage. */
    FULL("full"),
    /**
     * Entities are pushed and damaged but storms never rip or throw blocks. Snow and ice deposition under snow
     * storms still occur unless their own toggles are off; only {@link #VISUAL_ONLY} disables them.
     */
    ENTITIES_ONLY("entities_only"),
    /** Storms are only drawn; nothing in the world is touched, deposition included. */
    VISUAL_ONLY("visual_only");

    private static final String TRANSLATION_PREFIX = ConfigSetup.TRANSLATION_PREFIX + "destructionMode.";

    private final String serializedName;

    DestructionMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** Lower-case name used in translation keys and in text output. */
    public String getSerializedName() {
        return serializedName;
    }

    /** True when this mode lets storms break and move blocks. */
    public boolean ripsBlocks() {
        return this == FULL;
    }

    /** True when this mode lets storms push or hurt entities. */
    public boolean affectsEntities() {
        return this != VISUAL_ONLY;
    }

    /** Translation key shown in the configuration screen. */
    public String getTranslationKey() {
        return TRANSLATION_PREFIX + serializedName;
    }

    @Override
    public Component getTranslatedName() {
        return Component.translatable(getTranslationKey());
    }
}
