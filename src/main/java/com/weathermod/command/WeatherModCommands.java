package com.weathermod.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.weathermod.WeatherMod;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.PrecipitationKind;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.WeatherSimulator;
import com.weathermod.server.WindRegime;
import com.weathermod.server.WindState;
import com.weathermod.server.climate.ClimateField;
import com.weathermod.server.climate.ClimateSample;
import com.weathermod.server.climate.SeasonInfo;
import com.weathermod.server.director.DirectorState;
import com.weathermod.server.director.StormDirector;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code /weathermod} test commands: spawn, list, remove, clear, intensify and steer storms, set the wind, query
 * the climate at a position, drive the storm director and print the dimension's weather state. Every subcommand
 * needs permission level 2 and acts on the dimension the command source is in.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherModCommands {
    /** Root literal of the command tree. */
    public static final String ROOT = "weathermod";
    /** Vanilla permission level required for every subcommand; 2 is the game master level that {@code /weather} uses. */
    public static final int PERMISSION_LEVEL = 2;

    private static final String TRANSLATION_PREFIX = "command." + WeatherMod.MOD_ID + ".";
    private static final String KIND_PREFIX = TRANSLATION_PREFIX + "climate.kind.";
    private static final String REGIME_PREFIX = TRANSLATION_PREFIX + "wind.regime.";

    private static final String ARG_TYPE = "type";
    private static final String ARG_POS = "pos";
    private static final String ARG_INTENSITY = "intensity";
    private static final String ARG_ID = "id";
    private static final String ARG_HEADING = "headingDegrees";
    private static final String ARG_DIRECTION = "directionDegrees";
    private static final String ARG_SPEED = "speed";

    private static final DynamicCommandExceptionType ERROR_STORM_NOT_FOUND = new DynamicCommandExceptionType(
        id -> Component.translatable(TRANSLATION_PREFIX + "storm.not_found", id));
    private static final DynamicCommandExceptionType ERROR_DIMENSION_DISABLED = new DynamicCommandExceptionType(
        dimension -> Component.translatable(TRANSLATION_PREFIX + "dimension_disabled", dimension));
    private static final DynamicCommandExceptionType ERROR_DIMENSION_FULL = new DynamicCommandExceptionType(
        max -> Component.translatable(TRANSLATION_PREFIX + "storm.spawn_failed_full", max));
    private static final DynamicCommandExceptionType ERROR_NO_FITTING_TYPE = new DynamicCommandExceptionType(
        player -> Component.translatable(TRANSLATION_PREFIX + "director.spawn_failed_no_type", player));

    private WeatherModCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
        WeatherMod.LOGGER.debug("Registered /{} commands", ROOT);
    }

    /** Builds the {@code /weathermod} tree on the dispatcher. Split out of the event handler so it can be reused. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal(ROOT)
            .requires(source -> source.hasPermission(PERMISSION_LEVEL))
            .then(Commands.literal("storm")
                .then(Commands.literal("spawn")
                    .then(Commands.argument(ARG_TYPE, ResourceArgument.resource(context, WeatherRegistries.WEATHER_TYPE))
                        .executes(ctx -> spawn(ctx, ctx.getSource().getPosition(), null))
                        .then(Commands.argument(ARG_POS, Vec3Argument.vec3())
                            .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, ARG_POS), null))
                            .then(Commands.argument(ARG_INTENSITY, FloatArgumentType.floatArg(0.0F, 1.0F))
                                .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, ARG_POS),
                                    FloatArgumentType.getFloat(ctx, ARG_INTENSITY)))))))
                .then(Commands.literal("list")
                    .executes(WeatherModCommands::list))
                .then(Commands.literal("remove")
                    .then(Commands.argument(ARG_ID, IntegerArgumentType.integer(1))
                        .executes(ctx -> remove(ctx, IntegerArgumentType.getInteger(ctx, ARG_ID)))))
                .then(Commands.literal("clear")
                    .executes(WeatherModCommands::clear))
                .then(Commands.literal("intensify")
                    .then(Commands.argument(ARG_ID, IntegerArgumentType.integer(1))
                        .then(Commands.argument(ARG_INTENSITY, FloatArgumentType.floatArg(0.0F, 1.0F))
                            .executes(ctx -> intensify(ctx, IntegerArgumentType.getInteger(ctx, ARG_ID),
                                FloatArgumentType.getFloat(ctx, ARG_INTENSITY))))))
                .then(Commands.literal("steer")
                    .then(Commands.argument(ARG_ID, IntegerArgumentType.integer(1))
                        .then(Commands.argument(ARG_HEADING, FloatArgumentType.floatArg())
                            .then(Commands.argument(ARG_SPEED, FloatArgumentType.floatArg(0.0F, StormInstance.MAX_STEER_SPEED))
                                .executes(ctx -> steer(ctx, IntegerArgumentType.getInteger(ctx, ARG_ID),
                                    FloatArgumentType.getFloat(ctx, ARG_HEADING),
                                    FloatArgumentType.getFloat(ctx, ARG_SPEED)))))))
                .then(Commands.literal("release")
                    .then(Commands.argument(ARG_ID, IntegerArgumentType.integer(1))
                        .executes(ctx -> release(ctx, IntegerArgumentType.getInteger(ctx, ARG_ID))))))
            .then(Commands.literal("wind")
                .then(Commands.literal("set")
                    .then(Commands.argument(ARG_DIRECTION, FloatArgumentType.floatArg())
                        .then(Commands.argument(ARG_SPEED, FloatArgumentType.floatArg(WindState.MIN_SPEED, WindState.MAX_SPEED))
                            .executes(ctx -> setWind(ctx, FloatArgumentType.getFloat(ctx, ARG_DIRECTION),
                                FloatArgumentType.getFloat(ctx, ARG_SPEED)))))))
            .then(Commands.literal("climate")
                .executes(ctx -> climate(ctx, BlockPos.containing(ctx.getSource().getPosition())))
                .then(Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                    .executes(ctx -> climate(ctx, BlockPosArgument.getBlockPos(ctx, ARG_POS)))))
            .then(Commands.literal("director")
                .then(Commands.literal("status")
                    .executes(WeatherModCommands::directorStatus))
                .then(Commands.literal("pause")
                    .executes(ctx -> setDirectorPaused(ctx, true)))
                .then(Commands.literal("resume")
                    .executes(ctx -> setDirectorPaused(ctx, false)))
                .then(Commands.literal("spawn")
                    .executes(ctx -> directorSpawn(ctx, null))
                    .then(Commands.argument(ARG_TYPE, ResourceArgument.resource(context, WeatherRegistries.WEATHER_TYPE))
                        .executes(ctx -> directorSpawn(ctx,
                            ResourceArgument.getResource(ctx, ARG_TYPE, WeatherRegistries.WEATHER_TYPE))))))
            .then(Commands.literal("info")
                .executes(WeatherModCommands::info)));
    }

    /**
     * {@code /weathermod storm spawn <type> [<pos>] [<intensity>]}: spawns a storm at the horizontal position,
     * optionally pinning its peak intensity, sends it to nearby players at once and replies with the new id.
     */
    private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 pos, @Nullable Float intensity)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        Holder.Reference<WeatherType> type = ResourceArgument.getResource(ctx, ARG_TYPE, WeatherRegistries.WEATHER_TYPE);

        StormInstance storm = WeatherManager.spawn(level, type, pos.x(), pos.z(), intensity);
        if (storm == null) {
            throw ERROR_DIMENSION_FULL.create(WeatherManager.maxStormsPerDimension());
        }

        int id = storm.id();
        String typeId = type.key().location().toString();
        String x = formatDecimal(storm.x());
        String z = formatDecimal(storm.z());
        int peakPercent = percent(storm.peakIntensity());
        int durationSeconds = seconds(storm.maxAgeTicks());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.spawned",
            typeId, highlight(id), x, z, peakPercent, durationSeconds), true);
        return id;
    }

    /** {@code /weathermod storm list}: one line per storm in the source's dimension. Returns the storm count. */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        String dimension = dimensionName(level);
        List<StormInstance> storms = WeatherManager.get(level).storms();
        if (storms.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.list.empty", dimension), false);
            return 0;
        }

        int count = storms.size();
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.list.header", count, dimension), false);
        for (StormInstance storm : storms) {
            MutableComponent line = describe(storm);
            source.sendSuccess(() -> line, false);
        }
        return count;
    }

    /** {@code /weathermod storm remove <id>}: removes one storm and tells every player in the dimension. */
    private static int remove(CommandContext<CommandSourceStack> ctx, int id) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!WeatherManager.remove(source.getLevel(), id)) {
            throw ERROR_STORM_NOT_FOUND.create(id);
        }
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.removed", highlight(id)), true);
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /weathermod storm clear}: removes every storm in the dimension. Returns how many were removed. */
    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int removed = WeatherManager.clear(source.getLevel());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.cleared", removed), true);
        return removed;
    }

    /** {@code /weathermod storm intensify <id> <intensity>}: sets a storm's peak intensity and syncs it at once. */
    private static int intensify(CommandContext<CommandSourceStack> ctx, int id, float intensity)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);
        StormInstance storm = findStorm(data, id);

        storm.setPeakIntensity(intensity);
        WeatherManager.sync(level, storm);

        int percent = percent(storm.peakIntensity());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.intensified", highlight(id), percent), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /weathermod storm steer <id> <headingDegrees> <speed>}: pins a storm's heading and speed so it ignores
     * the wind until released.
     */
    private static int steer(CommandContext<CommandSourceStack> ctx, int id, float headingDegrees, float speed)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);
        StormInstance storm = findStorm(data, id);

        storm.steer(headingDegrees, speed);
        WeatherManager.sync(level, storm);

        String heading = formatDecimal(storm.headingDegrees());
        String blocksPerSecond = formatDecimal(storm.speed());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.steered",
            highlight(id), heading, blocksPerSecond), true);
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /weathermod storm release <id>}: lets a steered storm follow the wind again. */
    private static int release(CommandContext<CommandSourceStack> ctx, int id) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);
        StormInstance storm = findStorm(data, id);

        storm.releaseSteering();
        WeatherManager.sync(level, storm);

        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "storm.released", highlight(id)), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /weathermod wind set <directionDegrees> <speed>}: sets the dimension's wind, which becomes the new
     * starting point and prevailing direction of the random walk, and sends the new value to every player in it.
     */
    private static int setWind(CommandContext<CommandSourceStack> ctx, float directionDegrees, float speed)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);
        WindState wind = data.wind();

        wind.set(directionDegrees, speed);
        data.setDirty();
        WeatherNetwork.sendToDimension(level, WeatherSimulator.windPayload(wind));

        String dimension = dimensionName(level);
        String direction = formatDecimal(wind.directionDegrees());
        String blocksPerSecond = formatDecimal(wind.speed());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "wind.set",
            dimension, direction, blocksPerSecond), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /weathermod climate [<pos>]}: the temperature, humidity, what would fall and the season at a block
     * position, the caller's own by default. Sampling never loads a chunk, so any position may be asked for.
     */
    private static int climate(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        ClimateField climate = WeatherManager.climate(level);
        ClimateSample sample = climate.sample(level, pos);
        SeasonInfo season = climate.season(level, level.getBiome(pos));
        String provider = climate.seasons().id();

        String dimension = dimensionName(level);
        String temperature = formatHundredths(sample.temperature());
        String humidity = formatHundredths(sample.humidity());
        Component kind = precipitationKindName(sample.precipitationKind());
        int seasonPercent = percent(season.progress());

        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "climate.header",
            pos.getX(), pos.getY(), pos.getZ(), dimension).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "climate.sample",
            temperature, humidity, kind), false);
        if (season.isNone()) {
            source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "climate.season_none", provider), false);
        } else {
            source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "climate.season",
                season.name(), seasonPercent, provider), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /weathermod director status}: whether the director is paused, the seconds until its next spawn attempt,
     * the natural storm count against the cap, when it last spawned and the frequency multiplier. Returns the
     * seconds until the next attempt.
     */
    private static int directorStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        DimensionWeatherData data = WeatherManager.get(level);
        DirectorState state = data.director();

        String dimension = dimensionName(level);
        boolean enabled = WeatherManager.isEnabled(level);
        boolean paused = StormDirector.isPaused(level);
        int secondsUntilAttempt = StormDirector.secondsUntilNextAttempt(level);
        int naturalStorms = data.naturalStormCount();
        int maxStorms = WeatherManager.maxStormsPerDimension();
        int lastSpawnSecondsAgo = secondsSince(level, state.lastSpawnGameTime);
        int naturalSpawns = state.naturalSpawns;
        double frequency = WeatherManager.stormFrequency();
        String frequencyText = formatHundredths(frequency);

        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "director.status.header", dimension)
            .withStyle(ChatFormatting.GOLD), false);
        if (!enabled) {
            source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.disabled")
                .withStyle(ChatFormatting.RED), false);
        }
        source.sendSuccess(() -> paused
            ? Component.translatable(TRANSLATION_PREFIX + "director.status.paused", secondsUntilAttempt)
                .withStyle(ChatFormatting.YELLOW)
            : Component.translatable(TRANSLATION_PREFIX + "director.status.running", secondsUntilAttempt)
                .withStyle(ChatFormatting.GREEN), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "director.status.storms",
            naturalStorms, maxStorms), false);
        source.sendSuccess(() -> state.hasSpawned()
            ? Component.translatable(TRANSLATION_PREFIX + "director.status.last_spawn", lastSpawnSecondsAgo, naturalSpawns)
            : Component.translatable(TRANSLATION_PREFIX + "director.status.no_spawn"), false);
        source.sendSuccess(() -> frequency > 0.0D
            ? Component.translatable(TRANSLATION_PREFIX + "director.status.frequency", frequencyText)
            : Component.translatable(TRANSLATION_PREFIX + "director.status.frequency_off").withStyle(ChatFormatting.RED),
            false);
        return secondsUntilAttempt;
    }

    /**
     * {@code /weathermod director pause} and {@code /weathermod director resume}: stops or restarts natural
     * spawning, dissipation and heading bias in the dimension. Repeating the current state only reports it.
     */
    private static int setDirectorPaused(CommandContext<CommandSourceStack> ctx, boolean paused)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        String dimension = dimensionName(level);

        boolean wasPaused = StormDirector.isPaused(level);
        if (wasPaused == paused) {
            String key = paused ? "director.pause.already" : "director.resume.already";
            source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + key, dimension), false);
            return Command.SINGLE_SUCCESS;
        }

        StormDirector.setPaused(level, paused);
        String key = paused ? "director.paused" : "director.resumed";
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + key, dimension), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /weathermod director spawn [<type>]}: spawns a storm through the director's upwind planner, anchored on
     * the calling player, ignoring the spawn timer but not the storm cap. Without a type the director picks one that
     * fits the climate there, which may find nothing. Returns the new storm's id.
     */
    private static int directorSpawn(CommandContext<CommandSourceStack> ctx, @Nullable Holder.Reference<WeatherType> type)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        requireEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);

        int maxStorms = WeatherManager.maxStormsPerDimension();
        if (data.isFull() || data.naturalStormCount() >= maxStorms) {
            throw ERROR_DIMENSION_FULL.create(maxStorms);
        }
        String anchor = player.getScoreboardName();
        Optional<StormInstance> spawned = StormDirector.forceSpawn(level, player, type);
        StormInstance storm = spawned.orElseThrow(() -> ERROR_NO_FITTING_TYPE.create(anchor));

        int id = storm.id();
        String typeId = storm.typeId().toString();
        String x = formatDecimal(storm.x());
        String z = formatDecimal(storm.z());
        int peakPercent = percent(storm.peakIntensity());
        int durationSeconds = seconds(storm.maxAgeTicks());
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "director.spawned",
            typeId, highlight(id), x, z, anchor, peakPercent, durationSeconds), true);
        return id;
    }

    /**
     * {@code /weathermod info}: whether the dimension is enabled, its storm count against the cap, the wind with its
     * regime and gust, the season at the caller, the vanilla weather bridge and the vanilla flags, the director and
     * the configured destruction mode. Returns the storm count.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = WeatherManager.isEnabled(level);
        DimensionWeatherData data = WeatherManager.get(level);
        WindState wind = data.wind();
        ClimateField climate = WeatherManager.climate(level);
        BlockPos here = BlockPos.containing(source.getPosition());
        SeasonInfo season = climate.season(level, level.getBiome(here));
        LevelData levelData = level.getLevelData();

        String dimension = dimensionName(level);
        int stormCount = data.storms().size();
        int maxStorms = WeatherManager.maxStormsPerDimension();
        String direction = formatDecimal(wind.directionDegrees());
        String blocksPerSecond = formatDecimal(wind.speed());
        Component regime = regimeName(wind.regime());
        String prevailing = formatDecimal(wind.prevailingDirectionDegrees());
        String gust = formatDecimal(wind.gustSpeed());
        Component gusting = wind.isGusting()
            ? Component.translatable(TRANSLATION_PREFIX + "info.gusting").withStyle(ChatFormatting.YELLOW)
            : Component.empty();
        String seasonProvider = climate.seasons().id();
        int seasonPercent = percent(season.progress());
        boolean bridge = WeatherManager.vanillaWeatherBridge();
        Component raining = yesNo(levelData.isRaining());
        Component thundering = yesNo(levelData.isThundering());
        boolean directorPaused = StormDirector.isPaused(level);
        int secondsUntilAttempt = StormDirector.secondsUntilNextAttempt(level);
        Component destructionMode = destructionModeName();

        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.header", dimension)
            .withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> enabled
            ? Component.translatable(TRANSLATION_PREFIX + "info.enabled").withStyle(ChatFormatting.GREEN)
            : Component.translatable(TRANSLATION_PREFIX + "info.disabled").withStyle(ChatFormatting.RED), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.storms", stormCount, maxStorms), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.wind", direction, blocksPerSecond), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.wind_regime",
            regime, prevailing, gust, gusting), false);
        source.sendSuccess(() -> season.isNone()
            ? Component.translatable(TRANSLATION_PREFIX + "info.season_none", seasonProvider)
            : Component.translatable(TRANSLATION_PREFIX + "info.season", seasonProvider, season.name(), seasonPercent),
            false);
        source.sendSuccess(() -> bridge
            ? Component.translatable(TRANSLATION_PREFIX + "info.bridge_on", raining, thundering)
            : Component.translatable(TRANSLATION_PREFIX + "info.bridge_off", raining, thundering), false);
        source.sendSuccess(() -> directorPaused
            ? Component.translatable(TRANSLATION_PREFIX + "info.director_paused").withStyle(ChatFormatting.YELLOW)
            : Component.translatable(TRANSLATION_PREFIX + "info.director_running", secondsUntilAttempt), false);
        source.sendSuccess(() -> Component.translatable(TRANSLATION_PREFIX + "info.destruction_mode", destructionMode), false);
        return stormCount;
    }

    /** Fails the command when the weather simulation is disabled in the level's dimension. */
    private static void requireEnabled(ServerLevel level) throws CommandSyntaxException {
        if (!WeatherManager.isEnabled(level)) {
            throw ERROR_DIMENSION_DISABLED.create(dimensionName(level));
        }
    }

    /** Looks up a storm by id or fails the command with the not-found message. */
    private static StormInstance findStorm(DimensionWeatherData data, int id) throws CommandSyntaxException {
        return data.find(id).orElseThrow(() -> ERROR_STORM_NOT_FOUND.create(id));
    }

    /**
     * One chat line for {@code storm list}: id, type, position, heading and speed, intensity, age, and whether the
     * storm is steered by a command or was spawned by the director.
     */
    private static MutableComponent describe(StormInstance storm) {
        Component steered = storm.isSteered()
            ? Component.translatable(TRANSLATION_PREFIX + "storm.list.steered").withStyle(ChatFormatting.YELLOW)
            : Component.empty();
        Component natural = storm.isNatural()
            ? Component.translatable(TRANSLATION_PREFIX + "storm.list.natural").withStyle(ChatFormatting.GRAY)
            : Component.empty();
        return Component.translatable(TRANSLATION_PREFIX + "storm.list.entry",
            highlight(storm.id()),
            storm.typeId().toString(),
            formatDecimal(storm.x()),
            formatDecimal(storm.z()),
            formatDecimal(storm.headingDegrees()),
            formatDecimal(storm.speed()),
            percent(storm.intensity()),
            seconds(storm.ageTicks()),
            seconds(storm.maxAgeTicks()),
            steered,
            natural);
    }

    /** The configured destruction mode's display name, or a placeholder before the server config has loaded. */
    private static Component destructionModeName() {
        if (!ServerConfig.SPEC.isLoaded()) {
            return Component.translatable(TRANSLATION_PREFIX + "info.config_not_loaded");
        }
        return ServerConfig.destructionMode.get().getTranslatedName();
    }

    /** The chat name of a precipitation kind, for example "snow". */
    private static Component precipitationKindName(PrecipitationKind kind) {
        return Component.translatable(KIND_PREFIX + kind.name().toLowerCase(Locale.ROOT));
    }

    /** The chat name of a wind regime, for example "breeze". */
    private static Component regimeName(WindRegime regime) {
        return Component.translatable(REGIME_PREFIX + regime.getSerializedName());
    }

    /** "yes" or "no" as a translatable component. */
    private static Component yesNo(boolean value) {
        return Component.translatable(TRANSLATION_PREFIX + (value ? "yes" : "no"));
    }

    /** The dimension id of a level as text, for example {@code minecraft:overworld}. */
    private static String dimensionName(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** A storm id styled so it stands out in chat. */
    private static MutableComponent highlight(int id) {
        return Component.literal(Integer.toString(id)).withStyle(ChatFormatting.AQUA);
    }

    /** Formats a value with one decimal place, independent of the server's locale. */
    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Formats a value with two decimal places, independent of the server's locale. */
    private static String formatHundredths(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Converts a {@code [0, 1]} fraction to a whole percentage. */
    private static int percent(float fraction) {
        return Math.round(fraction * 100.0F);
    }

    /** Converts game ticks to whole real seconds. */
    private static int seconds(int ticks) {
        return ticks / SharedConstants.TICKS_PER_SECOND;
    }

    /** Whole real seconds between a past game time and the level's current game time, never negative. */
    private static int secondsSince(ServerLevel level, long gameTime) {
        long elapsedTicks = Math.max(0L, level.getGameTime() - gameTime);
        return (int) Math.min(Integer.MAX_VALUE, elapsedTicks / SharedConstants.TICKS_PER_SECOND);
    }
}
