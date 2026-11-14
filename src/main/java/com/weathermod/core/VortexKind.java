package com.weathermod.core;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * What a storm of the vortex family is. The kind selects the rules that differ between vortices (who follows a
 * parent storm, who burns, who is rated on the Enhanced Fujita scale) and the look of the funnel; the numbers of
 * the force field come from {@link VortexParams}.
 */
public enum VortexKind implements StringRepresentable {
    /** A tornado under a supercell, rated EF0 to EF5. */
    TORNADO("tornado"),
    /** A weak vortex under an ordinary thunderstorm. */
    LANDSPOUT("landspout"),
    /** A vortex over open water that ends over land. */
    WATERSPOUT("waterspout"),
    /** A short-lived whirl on the gust front of a squall. */
    GUSTNADO("gustnado"),
    /** A whirl of sand in hot, dry, calm air. */
    DUST_DEVIL("dust_devil"),
    /** A burning whirl, the weather of the Nether. */
    FIRE_WHIRL("fire_whirl");

    /** Serializes a kind as its lower-case name, for example {@code "dust_devil"}. */
    public static final Codec<VortexKind> CODEC = StringRepresentable.fromEnum(VortexKind::values);

    private final String serializedName;

    VortexKind(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /** True for {@link #TORNADO}: the only kind shown with an EF rating and the only kind that sets sirens off. */
    public boolean isRated() {
        return this == TORNADO;
    }

    /** True for {@link #FIRE_WHIRL}, the kind that lights fires and burns what is inside its funnel. */
    public boolean setsFire() {
        return this == FIRE_WHIRL;
    }

    /** True for {@link #TORNADO}, the kind that stays attached to the storm that spawned it. */
    public boolean followsParent() {
        return this == TORNADO;
    }
}
