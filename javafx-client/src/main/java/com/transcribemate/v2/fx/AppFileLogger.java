package com.transcribemate.v2.fx;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

final class AppFileLogger {
    private static final String APP_NAME = "TranscribeMate";
    private static final String LOG_PREFIX = "frontend";
    private static final int MAX_LOG_FILES = 30;
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private static boolean initialized;
    private static Path logFilePath;

    private AppFileLogger() {
    }

    static void initialize() {
        synchronized (LOCK) {
            if (initialized) {
                return;
            }

            initialized = true;
            try {
                Path logsDir = resolveLogsDir();
                Files.createDirectories(logsDir);
                logFilePath = createLogFileLocked(logsDir);
                pruneOldLogsLocked(logsDir);
                writeLineLocked("[INFO] Logger initialized: " + logFilePath);
            } catch (Exception ignored) {
                logFilePath = null;
            }
        }
    }

    static Path currentLogPath() {
        initialize();
        synchronized (LOCK) {
            return logFilePath;
        }
    }

    static void log(String level, String source, String message) {
        String safeMessage = trimToEmpty(message);
        if (safeMessage.isBlank()) {
            return;
        }

        initialize();
        String normalizedLevel = trimToEmpty(level).toUpperCase(Locale.ROOT);
        if (normalizedLevel.isBlank()) {
            normalizedLevel = "INFO";
        }
        String normalizedSource = trimToEmpty(source);
        if (normalizedSource.isBlank()) {
            normalizedSource = "app";
        }

        synchronized (LOCK) {
            for (String line : safeMessage.split("\\R", -1)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                writeLineLocked("[" + normalizedLevel + "] [" + normalizedSource + "] " + line);
            }
        }
    }

    static void logException(String source, Throwable throwable) {
        if (throwable == null) {
            log("ERROR", source, "Unknown exception.");
            return;
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log("ERROR", source, sw.toString());
    }

    private static void writeLineLocked(String line) {
        if (logFilePath == null) {
            return;
        }
        String stamped = "[" + LocalDateTime.now().format(LINE_TS_FMT) + "] " + trimToEmpty(line) + System.lineSeparator();
        try {
            Files.writeString(
                    logFilePath,
                    stamped,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private static Path createLogFileLocked(Path logsDir) {
        String stamp = LocalDateTime.now().format(FILE_TS_FMT);
        Path candidate = logsDir.resolve(LOG_PREFIX + "-" + stamp + ".log");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = logsDir.resolve(LOG_PREFIX + "-" + stamp + "-" + suffix + ".log");
            suffix += 1;
        }
        return candidate;
    }

    private static void pruneOldLogsLocked(Path logsDir) {
        try (Stream<Path> stream = Files.list(logsDir)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(LOG_PREFIX + "-"))
                    .sorted(Comparator.comparingLong(AppFileLogger::safeMtime).reversed())
                    .skip(MAX_LOG_FILES)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best effort only.
                        }
                    });
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private static long safeMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static Path resolveLogsDir() {
        String localAppData = trimToEmpty(System.getenv("LOCALAPPDATA"));
        if (!localAppData.isBlank()) {
            return Path.of(localAppData, APP_NAME, "Logs");
        }

        String userHome = trimToEmpty(System.getProperty("user.home"));
        if (userHome.isBlank()) {
            return Path.of(System.getProperty("user.dir"), APP_NAME, "Logs");
        }

        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return Path.of(userHome, "AppData", "Local", APP_NAME, "Logs");
        }
        return Path.of(userHome, ".local", "share", APP_NAME, "Logs");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
