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
    // This is a simple file logger for the application. It is not intended to be a full-featured logging framework, but rather a best-effort solution to capture logs in a file for troubleshooting purposes.
    private static final String LOG_PREFIX = "frontend";
    private static final int MAX_LOG_FILES = 10;    // Maximum number of log files to keep. Older files will be deleted on initialization.
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private static boolean initialized;
    private static Path logFilePath;

    private AppFileLogger() {
    }
    // Initializes the logger by creating a new log file and pruning old log files if necessary. This method is idempotent and thread-safe.
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
    // Returns the current log file path, or null if the logger is not initialized or failed to initialize. This method is thread-safe.
    static Path currentLogPath() {
        initialize();
        synchronized (LOCK) {
            return logFilePath;
        }
    }
    // Logs a message to the log file with the specified level and source. The message can be multi-line, and each line will be prefixed with the level and source. This method is thread-safe.
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
    // Logs an exception to the log file with the specified source. The stack trace of the exception will be included in the log. This method is thread-safe.
    static void logException(String source, Throwable throwable) {
        if (throwable == null) {
            log("ERROR", source, "Unknown exception.");
            return;
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log("ERROR", source, sw.toString());
    }
    // Writes a single line to the log file with a timestamp. This method assumes the caller has already acquired the lock and initialized the logger. This method is not thread-safe and should only be called from within a synchronized block in the initialize() or log() methods.
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
    // Creates a new log file with a unique name based on the current timestamp. If a file with the same name already exists, a numeric suffix will be added to the filename until a unique name is found. This method assumes the caller has already acquired the lock and initialized the logger. This method is not thread-safe and should only be called from within a synchronized block in the initialize() method.
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
    // Prunes old log files in the logs directory, keeping only the most recent MAX_LOG_FILES files that match the log file naming pattern. This method assumes the caller has already acquired the lock and initialized the logger. This method is not thread-safe and should only be called from within a synchronized block in the initialize() method.
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
    // Safely retrieves the last modified time of a file in milliseconds since the epoch. If the file does not exist or an error occurs, returns 0. This method is used for sorting log files by their modification time when pruning old logs.
    private static long safeMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }
    // Resolves the directory where log files should be stored based on the operating system and environment variables. The method first checks the LOCALAPPDATA environment variable (common on Windows), then falls back to the user's home directory with OS-specific subdirectories. If all else fails, it uses the current working directory. This method is used during logger initialization to determine where to create log files.
    private static Path resolveLogsDir() {
        return AppRuntimePaths.resolveLogsDir();
    }
    // Trims the input string and returns an empty string if the input is null. This is a utility method used to safely handle potentially null or blank strings when logging messages and determining log file paths.
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
