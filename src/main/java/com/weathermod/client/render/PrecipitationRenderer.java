package com.weathermod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.weathermod.WeatherMod;
import com.weathermod.client.ClientWeatherState;
import com.weathermod.client.LocalConditions;
import com.weathermod.client.LocalWeather;
import com.weathermod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Draws rain, snow and sleet inside the vanilla rain hook: the vanilla column algorithm with the biome gate removed
 * (the mod's climate already chose what falls), the mod's own textures, a wind lean on the top of every streak and
 * the smoothed rain level from {@link WeatherLevelDriver}. Everything is drawn with the particle shader and the
 * particle vertex format exactly as vanilla does, which is what routes it to a shader pack's weather program.
 */
public final class PrecipitationRenderer {
    /** The rain streak texture, tiling vertically. */
    public static final ResourceLocation RAIN_TEXTURE = WeatherMod.id("textures/environment/rain.png");
    /** The snow flake texture, tiling vertically. */
    public static final ResourceLocation SNOW_TEXTURE = WeatherMod.id("textures/environment/snow.png");
    /** Value of {@code ClientConfig.precipitationRenderer} assumed while the client config has not been loaded. */
    public static final boolean DEFAULT_PRECIPITATION_RENDERER = true;

    /** Side of the square table of column directions; the camera column sits at its centre (vanilla). */
    private static final int TABLE_SIZE = 32;
    private static final int TABLE_CENTRE = TABLE_SIZE / 2;
    /** Unit vector from the camera to each column, x component, so every quad faces the camera (vanilla). */
    private static final float[] RAIN_SIZE_X = new float[TABLE_SIZE * TABLE_SIZE];
    /** Unit vector from the camera to each column, z component (vanilla). */
    private static final float[] RAIN_SIZE_Z = new float[TABLE_SIZE * TABLE_SIZE];
    /** Rain falls at this many texture repeats per 32 ticks plus a per-column random part (vanilla). */
    private static final float RAIN_BASE_SPEED = 3.0F;
    /** Snow light is lifted three quarters of the way toward full brightness (vanilla). */
    private static final int LIGHT_BOOST_NUMERATOR = 3;
    private static final int LIGHT_BOOST_OFFSET = 240;
    private static final int LIGHT_BOOST_DENOMINATOR = 4;
    private static final int LIGHT_MASK = 65535;

    static {
        for (int i = 0; i < TABLE_SIZE; i++) {
            for (int j = 0; j < TABLE_SIZE; j++) {
                float f = (float) (j - TABLE_CENTRE);
                float f1 = (float) (i - TABLE_CENTRE);
                float f2 = Mth.sqrt(f * f + f1 * f1);
                RAIN_SIZE_X[i << 5 | j] = -f1 / f2;
                RAIN_SIZE_Z[i << 5 | j] = f / f2;
            }
        }
    }

    private PrecipitationRenderer() {
    }

    /** Whether the client config asks for the renderer; {@link #DEFAULT_PRECIPITATION_RENDERER} while it is not loaded. */
    public static boolean isEnabledByConfig() {
        return ClientConfig.SPEC.isLoaded() ? ClientConfig.precipitationRenderer.get() : DEFAULT_PRECIPITATION_RENDERER;
    }

    /**
     * True when the mod manages the weather here ({@link LocalWeather#active()}), the mod's effects instance is
     * installed for the level and {@code ClientConfig.precipitationRenderer} is on, so this renderer rather than
     * vanilla draws the rain there.
     */
    public static boolean isActive(ClientLevel level) {
        return level != null && LocalWeather.INSTANCE.active() && WeatherEffectsRegistration.isInstalled(level)
            && isEnabledByConfig();
    }

    /**
     * The rain hook body. Draws the columns around the camera for the kind at the camera at the driver's rain level
     * and returns true so vanilla draws nothing, even when there was nothing to draw. {@link WeatherDimensionEffects}
     * only calls it while the mod manages the weather here; in an unmanaged dimension it returns false itself so
     * vanilla draws its own rain.
     *
     * @param level        the client level
     * @param ticks        the level renderer's tick counter
     * @param partialTick  the frame's partial tick
     * @param lightTexture the light map to bind while drawing
     * @param camX         camera x
     * @param camY         camera y
     * @param camZ         camera z
     * @return true when this renderer handled the frame, false to let vanilla draw
     */
    public static boolean render(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture,
                                 double camX, double camY, double camZ) {
        float rain = WeatherLevelDriver.INSTANCE.rain(partialTick);
        LocalConditions conditions = LocalWeather.INSTANCE.conditions();
        if (rain <= 0.0F || !conditions.isPrecipitating()) {
            return true;
        }

        ClientWeatherState wind = ClientWeatherState.INSTANCE;
        float windDirection = 0.0F;
        float windSpeed = 0.0F;
        if (wind.hasWind()) {
            windDirection = wind.windDirectionDegrees();
            windSpeed = wind.isWindGusting() ? wind.windGustSpeed() : wind.windSpeed();
        }
        int radius = Minecraft.useFancyGraphics() ? PrecipitationGeometry.FANCY_RADIUS : PrecipitationGeometry.FAST_RADIUS;

        lightTexture.turnOnLightLayer();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.setShader(GameRenderer::getParticleShader);
        Tesselator tesselator = Tesselator.getInstance();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        switch (conditions.kind()) {
            case RAIN -> drawRain(level, tesselator, pos, ticks, partialTick, camX, camY, camZ, radius, rain, 1.0F,
                windDirection, windSpeed);
            case SNOW -> drawSnow(level, tesselator, pos, ticks, partialTick, camX, camY, camZ, radius, rain, 1.0F,
                windDirection, windSpeed);
            case SLEET -> {
                drawRain(level, tesselator, pos, ticks, partialTick, camX, camY, camZ, radius, rain,
                    PrecipitationGeometry.SLEET_RAIN_ALPHA, windDirection, windSpeed);
                drawSnow(level, tesselator, pos, ticks, partialTick, camX, camY, camZ, radius, rain,
                    PrecipitationGeometry.SLEET_SNOW_ALPHA, windDirection, windSpeed);
            }
            case NONE -> {
            }
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        lightTexture.turnOffLightLayer();
        return true;
    }

    /** One rain pass over every column in the radius: the vanilla rain branch with the wind lean on the top vertices. */
    private static void drawRain(ClientLevel level, Tesselator tesselator, BlockPos.MutableBlockPos pos, int ticks,
                                 float partialTick, double camX, double camY, double camZ, int radius, float rainLevel,
                                 float alphaScale, float windDirection, float windSpeed) {
        int camBlockX = Mth.floor(camX);
        int camBlockY = Mth.floor(camY);
        int camBlockZ = Mth.floor(camZ);
        double[] lean = PrecipitationGeometry.topOffset(windDirection, PrecipitationGeometry.rainTilt(windSpeed), 1.0F);
        double leanPerBlockX = lean[0];
        double leanPerBlockZ = lean[1];

        RenderSystem.setShaderTexture(0, RAIN_TEXTURE);
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        for (int z = camBlockZ - radius; z <= camBlockZ + radius; z++) {
            for (int x = camBlockX - radius; x <= camBlockX + radius; x++) {
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = PrecipitationGeometry.columnBottom(camBlockY, ground, radius);
                int top = PrecipitationGeometry.columnTop(camBlockY, ground, radius);
                if (bottom == top) {
                    continue;
                }
                int lightY = PrecipitationGeometry.columnLightY(camBlockY, ground);
                int index = (z - camBlockZ + TABLE_CENTRE) * TABLE_SIZE + x - camBlockX + TABLE_CENTRE;
                double halfX = (double) RAIN_SIZE_X[index] * 0.5D;
                double halfZ = (double) RAIN_SIZE_Z[index] * 0.5D;

                RandomSource random = RandomSource.create(PrecipitationGeometry.columnSeed(x, z));
                float fallSpeed = RAIN_BASE_SPEED + random.nextFloat();
                float scroll = PrecipitationGeometry.rainScroll(ticks, PrecipitationGeometry.columnHash(x, z),
                    partialTick, fallSpeed);
                double dx = (double) x + 0.5D - camX;
                double dz = (double) z + 0.5D - camZ;
                float distance = (float) Math.sqrt(dx * dx + dz * dz) / (float) radius;
                float alpha = PrecipitationGeometry.rainAlpha(distance, rainLevel) * alphaScale;
                pos.set(x, lightY, z);
                int light = LevelRenderer.getLightColor(level, pos);

                float height = (float) (top - bottom);
                float leanX = (float) (leanPerBlockX * height);
                float leanZ = (float) (leanPerBlockZ * height);
                float x0 = (float) ((double) x - camX - halfX + 0.5D);
                float x1 = (float) ((double) x - camX + halfX + 0.5D);
                float z0 = (float) ((double) z - camZ - halfZ + 0.5D);
                float z1 = (float) ((double) z - camZ + halfZ + 0.5D);
                float yTop = (float) ((double) top - camY);
                float yBottom = (float) ((double) bottom - camY);
                float vTop = (float) bottom * PrecipitationGeometry.RAIN_UV_PER_BLOCK + scroll;
                float vBottom = (float) top * PrecipitationGeometry.RAIN_UV_PER_BLOCK + scroll;

                buffer.addVertex(x0 + leanX, yTop, z0 + leanZ)
                    .setUv(0.0F, vTop)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setLight(light);
                buffer.addVertex(x1 + leanX, yTop, z1 + leanZ)
                    .setUv(1.0F, vTop)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setLight(light);
                buffer.addVertex(x1, yBottom, z1)
                    .setUv(1.0F, vBottom)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setLight(light);
                buffer.addVertex(x0, yBottom, z0)
                    .setUv(0.0F, vBottom)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setLight(light);
            }
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    /** One snow pass over every column in the radius: the vanilla snow branch with the wind lean on the top vertices. */
    private static void drawSnow(ClientLevel level, Tesselator tesselator, BlockPos.MutableBlockPos pos, int ticks,
                                 float partialTick, double camX, double camY, double camZ, int radius, float rainLevel,
                                 float alphaScale, float windDirection, float windSpeed) {
        int camBlockX = Mth.floor(camX);
        int camBlockY = Mth.floor(camY);
        int camBlockZ = Mth.floor(camZ);
        float time = (float) ticks + partialTick;
        float scroll = PrecipitationGeometry.snowScroll(ticks, partialTick);
        double[] lean = PrecipitationGeometry.topOffset(windDirection, PrecipitationGeometry.snowTilt(windSpeed), 1.0F);
        double leanPerBlockX = lean[0];
        double leanPerBlockZ = lean[1];

        RenderSystem.setShaderTexture(0, SNOW_TEXTURE);
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        for (int z = camBlockZ - radius; z <= camBlockZ + radius; z++) {
            for (int x = camBlockX - radius; x <= camBlockX + radius; x++) {
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int bottom = PrecipitationGeometry.columnBottom(camBlockY, ground, radius);
                int top = PrecipitationGeometry.columnTop(camBlockY, ground, radius);
                if (bottom == top) {
                    continue;
                }
                int lightY = PrecipitationGeometry.columnLightY(camBlockY, ground);
                int index = (z - camBlockZ + TABLE_CENTRE) * TABLE_SIZE + x - camBlockX + TABLE_CENTRE;
                double halfX = (double) RAIN_SIZE_X[index] * 0.5D;
                double halfZ = (double) RAIN_SIZE_Z[index] * 0.5D;

                RandomSource random = RandomSource.create(PrecipitationGeometry.columnSeed(x, z));
                float driftU = (float) (random.nextDouble()
                    + (double) time * 0.01D * (double) ((float) random.nextGaussian()));
                float driftV = (float) (random.nextDouble() + (double) (time * (float) random.nextGaussian()) * 0.001D);
                double dx = (double) x + 0.5D - camX;
                double dz = (double) z + 0.5D - camZ;
                float distance = (float) Math.sqrt(dx * dx + dz * dz) / (float) radius;
                float alpha = PrecipitationGeometry.snowAlpha(distance, rainLevel) * alphaScale;
                pos.set(x, lightY, z);
                int light = LevelRenderer.getLightColor(level, pos);
                int sky = light >> 16 & LIGHT_MASK;
                int block = light & LIGHT_MASK;
                int boostedSky = (sky * LIGHT_BOOST_NUMERATOR + LIGHT_BOOST_OFFSET) / LIGHT_BOOST_DENOMINATOR;
                int boostedBlock = (block * LIGHT_BOOST_NUMERATOR + LIGHT_BOOST_OFFSET) / LIGHT_BOOST_DENOMINATOR;

                float height = (float) (top - bottom);
                float leanX = (float) (leanPerBlockX * height);
                float leanZ = (float) (leanPerBlockZ * height);
                float x0 = (float) ((double) x - camX - halfX + 0.5D);
                float x1 = (float) ((double) x - camX + halfX + 0.5D);
                float z0 = (float) ((double) z - camZ - halfZ + 0.5D);
                float z1 = (float) ((double) z - camZ + halfZ + 0.5D);
                float yTop = (float) ((double) top - camY);
                float yBottom = (float) ((double) bottom - camY);
                float vTop = (float) bottom * PrecipitationGeometry.RAIN_UV_PER_BLOCK + scroll + driftV;
                float vBottom = (float) top * PrecipitationGeometry.RAIN_UV_PER_BLOCK + scroll + driftV;

                buffer.addVertex(x0 + leanX, yTop, z0 + leanZ)
                    .setUv(0.0F + driftU, vTop)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setUv2(boostedBlock, boostedSky);
                buffer.addVertex(x1 + leanX, yTop, z1 + leanZ)
                    .setUv(1.0F + driftU, vTop)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setUv2(boostedBlock, boostedSky);
                buffer.addVertex(x1, yBottom, z1)
                    .setUv(1.0F + driftU, vBottom)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setUv2(boostedBlock, boostedSky);
                buffer.addVertex(x0, yBottom, z0)
                    .setUv(0.0F + driftU, vBottom)
                    .setColor(1.0F, 1.0F, 1.0F, alpha)
                    .setUv2(boostedBlock, boostedSky);
            }
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }
}
