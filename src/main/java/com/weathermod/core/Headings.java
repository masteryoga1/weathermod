package com.weathermod.core;

/**
 * Compass heading arithmetic shared by the server simulation and the client prediction: 0 degrees is north
 * ({@code -Z}), 90 is east ({@code +X}), 180 is south ({@code +Z}) and 270 is west ({@code -X}). Both sides use
 * these helpers so a storm predicted on the client moves exactly as it does on the server.
 */
public final class Headings {
    private Headings() {
    }

    /** Wraps an angle into {@code [0, 360)}. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped < 0.0F) {
            wrapped += 360.0F;
        }
        return wrapped >= 360.0F ? 0.0F : wrapped;
    }

    /** Unit {@code X} component of a compass heading. */
    public static double directionX(float degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    /** Unit {@code Z} component of a compass heading. */
    public static double directionZ(float degrees) {
        return -Math.cos(Math.toRadians(degrees));
    }
}
