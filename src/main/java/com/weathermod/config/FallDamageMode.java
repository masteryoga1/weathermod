package com.weathermod.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * How much fall damage an entity takes when it lands shortly after a vortex carried it. Chosen by the server
 * config and read by the fall handler of the entity forces; falls a vortex had no part in are never changed.
 */
public enum FallDamageMode implements TranslatableEnum {
    /** The fall hurts as any other fall does. */
    FULL("full"),
    /** The fall does half the damage. */
    REDUCED("reduced"),
    /** The fall does no damage. */
    NONE("none");

    private static final String TRANSLATION_PREFIX = ConfigSetup.TRANSLATION_PREFIX + "tornadoFallDamage.";

    private final String serializedName;

    FallDamageMode(String serializedName) {
        this.serializedName = serializedName;
    }

    /** Lower-case name used in translation keys and in text output. */
    public String getSerializedName() {
        return serializedName;
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
