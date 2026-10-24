package com.weathermod.client;

import com.weathermod.core.PrecipitationKind;
import java.util.Objects;

/**
 * What the weather looks like at the camera this tick, combined over every storm in range: the vanilla rain and
 * thunder levels and the haze the client should show, what is falling, how much of the point any one storm covers
 * and which storm decides the precipitation kind.
 *
 * @param rain            combined rain level in {@code [0, 1]}
 * @param thunder         combined thunder level in {@code [0, 1]}
 * @param haze            combined haze strength in {@code [0, 1]}
 * @param kind            what falls at the camera, {@link PrecipitationKind#NONE} when nothing precipitating covers it
 * @param coverage        the largest single-storm coverage at the camera, {@code [0, 1]}
 * @param dominantStormId id of the precipitating storm that chose the kind, {@code -1} when there is none
 */
public record LocalConditions(float rain, float thunder, float haze, PrecipitationKind kind, float coverage,
                              int dominantStormId) {
    /** No weather at all: every level zero, nothing falling, no dominant storm. */
    public static final LocalConditions NONE = new LocalConditions(0.0F, 0.0F, 0.0F, PrecipitationKind.NONE, 0.0F, -1);

    public LocalConditions {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * True when something visible is falling: a positive rain level and a kind other than
     * {@link PrecipitationKind#NONE}.
     */
    public boolean isPrecipitating() {
        return rain > 0.0F && kind != PrecipitationKind.NONE;
    }
}
