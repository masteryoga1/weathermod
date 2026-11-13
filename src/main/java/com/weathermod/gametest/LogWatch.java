package com.weathermod.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Remembers every warning and error the server logs about the mod while the game tests run, so a test can fail on
 * a log line nobody would otherwise read. A line counts when it comes from one of the mod's own loggers, names the
 * mod id in its text, or carries an exception that names the mod id or passed through the mod's code. That covers
 * the datapack loaders, which report a broken tag, recipe or loot table by its id, and the simulation. Lines of
 * the game test framework itself are left out: it reports failed tests there, and those are counted elsewhere.
 *
 * <p>The watch is attached to the root logger once, when the tests are registered, which is before the datapacks
 * load. Without the logging backend the server ships with it stays inactive and {@link #isActive()} says so.
 */
final class LogWatch extends AbstractAppender {
    private static final String NAME = "WeatherModGameTestLogWatch";
    private static final String MOD_MARKER = "weathermod";
    private static final String MOD_PACKAGE = "com.weathermod";
    private static final String FRAMEWORK_PACKAGE = "net.minecraft.gametest";
    private static final int MAX_LINES = 2000;

    @Nullable
    private static LogWatch instance;

    private final List<String> lines = new ArrayList<>();

    private LogWatch() {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    /** Attaches the watch to the root logger; later calls do nothing. */
    static synchronized void install() {
        if (instance != null) {
            return;
        }
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return;
        }
        LogWatch watch = new LogWatch();
        watch.start();
        Configuration configuration = context.getConfiguration();
        configuration.addAppender(watch);
        configuration.getRootLogger().addAppender(watch, Level.WARN, null);
        context.updateLoggers();
        instance = watch;
    }

    /** Whether the watch is attached and sees the log. */
    static synchronized boolean isActive() {
        return instance != null;
    }

    /** The number of lines remembered so far, to pass to {@link #since(int)} later. */
    static synchronized int mark() {
        return instance == null ? 0 : instance.count();
    }

    /** The lines remembered after the given mark, oldest first. */
    static synchronized List<String> since(int mark) {
        return instance == null ? List.of() : instance.linesFrom(mark);
    }

    @Override
    public void append(LogEvent event) {
        if (!event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return;
        }
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        if (logger.startsWith(FRAMEWORK_PACKAGE)) {
            return;
        }
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        Throwable thrown = event.getThrown();
        if (!logger.startsWith(MOD_PACKAGE) && !mentionsMod(message) && !concernsMod(thrown)) {
            return;
        }
        String line = event.getLevel() + " [" + logger + "] " + message + (thrown == null ? "" : " : " + thrown);
        synchronized (lines) {
            if (lines.size() < MAX_LINES) {
                lines.add(line);
            }
        }
    }

    private int count() {
        synchronized (lines) {
            return lines.size();
        }
    }

    private List<String> linesFrom(int mark) {
        synchronized (lines) {
            int from = Math.max(0, Math.min(mark, lines.size()));
            return List.copyOf(lines.subList(from, lines.size()));
        }
    }

    private static boolean mentionsMod(@Nullable String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(MOD_MARKER);
    }

    private static boolean concernsMod(@Nullable Throwable thrown) {
        int depth = 0;
        for (Throwable cause = thrown; cause != null && depth < 8; cause = cause.getCause(), depth++) {
            if (mentionsMod(cause.getMessage())) {
                return true;
            }
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().startsWith(MOD_PACKAGE)) {
                    return true;
                }
            }
        }
        return false;
    }
}
