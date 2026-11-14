package com.weathermod.client;

/**
 * A strike the client was told about, stamped with the client tick it arrived on.
 *
 * @param x          strike position x in blocks
 * @param y          strike position y in blocks
 * @param z          strike position z in blocks
 * @param power      strength of the strike, 0 to 1
 * @param clientTick value of {@link ClientWeatherState#ticks()} when the strike arrived
 */
public record ClientStrike(double x, double y, double z, float power, long clientTick) {
}
