package com.weathermod.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;

/**
 * Whether the lightning of the mod's storms starts fires. Chosen by the server config and read by the lightning
 * code after it has decided that a strike may change blocks at all, which only the full destruction mode allows.
 */
public enum LightningFire implements TranslatableEnum {
    /** Lightning never starts a fire. */
    OFF("off"),
    /**
     * A ground strike starts a fire by the chance of the storm's weather type, and only while the
     * {@code doFireTick} game rule is on.
     */
    NORMAL("normal"),
    /** Every ground strike starts a fire, whatever the weather type's chance and the game rule say. */
    ALWAYS("always");

    private static final String TRANSLATION_PREFIX = ConfigSetup.TRANSLATION_PREFIX + "lightningFire.";

    private final String serializedName;

    LightningFire(String serializedName) {
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
