package com.transcribemate.v2.fx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Validates local managed runtime installation before backend startup.
 */
public final class RuntimeInstallChecker {
    static final String APP_NAME = "TranscribeMate";
    static final String RUNTIME_READY_MARKER_NAME = "runtime-ready.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_WHISPER_MODEL_PATH = "cache/whisper/models/large-v3";
    private static final String DEFAULT_TRANSLATION_MODEL_PATH = "cache/huggingface/hub/models--Helsinki-NLP--opus-mt-en-cs";

    public enum RuntimeCheckStatus {
        READY,
        FIRST_RUN_REQUIRED,
        REPAIR_REQUIRED
    }

    public record RuntimeCheckResult(
            RuntimeCheckStatus status,
            String reasonCode,
            String reasonMessage,
            Path appDataDir,
            List<String> missingArtifacts
    ) {
        public RuntimeCheckResult {
            reasonCode = trimToEmpty(reasonCode);
            reasonMessage = trimToEmpty(reasonMessage);
            if (appDataDir == null) {
                appDataDir = resolveRuntimeAppDataDir();
            }
            appDataDir = appDataDir.toAbsolutePath().normalize();
            missingArtifacts = List.copyOf(missingArtifacts == null ? List.of() : missingArtifacts);
        }

        public boolean isReady() {
            return status == RuntimeCheckStatus.READY;
        }

        public boolean isFirstRunRequired() {
            return status == RuntimeCheckStatus.FIRST_RUN_REQUIRED;
        }

        public boolean isRepairRequired() {
            return status == RuntimeCheckStatus.REPAIR_REQUIRED;
        }
    }

    private RuntimeInstallChecker() {
    }

    public static RuntimeCheckResult evaluate() {
        String projectRoot = trimToEmpty(System.getenv("TM_PROJECT_ROOT"));
        Path appDataDir = resolveRuntimeAppDataDir();
        if (!projectRoot.isBlank()) {
            return new RuntimeCheckResult(
                    RuntimeCheckStatus.READY,
                    "dev_bypass",
                    "Development mode runtime bootstrap bypass is active.",
                    appDataDir,
                    List.of()
            );
        }

        Path runtimeRoot = appDataDir.resolve("runtime");
        Path markerPath = runtimeRoot.resolve(RUNTIME_READY_MARKER_NAME);
        Path pythonExe = resolveManagedRuntimePython(runtimeRoot);
        Path ffmpegPath = resolveExpectedBinary(appDataDir, "ffmpeg.exe", "ffmpeg");
        Path ffprobePath = resolveExpectedBinary(appDataDir, "ffprobe.exe", "ffprobe");
        Path whisperModelPath = appDataDir.resolve(DEFAULT_WHISPER_MODEL_PATH);
        Path translationModelPath = appDataDir.resolve(DEFAULT_TRANSLATION_MODEL_PATH);

        Set<String> missing = new LinkedHashSet<>();
        if (!Files.isRegularFile(pythonExe)) {
            missing.add(toRelativeLabel(appDataDir, pythonExe));
        }
        if (!Files.isRegularFile(ffmpegPath)) {
            missing.add(toRelativeLabel(appDataDir, ffmpegPath));
        }
        if (!Files.isRegularFile(ffprobePath)) {
            missing.add(toRelativeLabel(appDataDir, ffprobePath));
        }
        if (!isWhisperModelAvailable(appDataDir)) {
            missing.add(toRelativeLabel(appDataDir, whisperModelPath));
        }
        if (!isTranslationModelAvailable(appDataDir)) {
            missing.add(toRelativeLabel(appDataDir, translationModelPath));
        }

        boolean markerExists = Files.isRegularFile(markerPath);
        if (!markerExists) {
            missing.add(toRelativeLabel(appDataDir, markerPath));
        }

        if (markerExists) {
            try {
                JsonNode root = MAPPER.readTree(markerPath.toFile());
                JsonNode requiredPaths = root.path("components").path("required_paths");
                if (requiredPaths.isArray()) {
                    for (JsonNode item : requiredPaths) {
                        String rawPath = trimToEmpty(item.asText(""));
                        if (rawPath.isBlank()) {
                            continue;
                        }
                        boolean present = isRequiredPathAvailable(appDataDir, rawPath);
                        if (!present) {
                            missing.add("marker_required:" + rawPath);
                        }
                    }
                }
            } catch (Exception ex) {
                missing.add(toRelativeLabel(appDataDir, markerPath) + " (invalid JSON)");
            }
        }

        List<String> missingArtifacts = new ArrayList<>(missing);
        boolean runtimeMissing = !Files.isRegularFile(pythonExe);
        if (!markerExists && runtimeMissing) {
            return new RuntimeCheckResult(
                    RuntimeCheckStatus.FIRST_RUN_REQUIRED,
                    "first_run_missing_runtime",
                    "Managed runtime is not installed yet.",
                    appDataDir,
                    missingArtifacts
            );
        }

        if (!missingArtifacts.isEmpty()) {
            return new RuntimeCheckResult(
                    RuntimeCheckStatus.REPAIR_REQUIRED,
                    "runtime_incomplete",
                    "Runtime installation is incomplete or damaged.",
                    appDataDir,
                    missingArtifacts
            );
        }

        return new RuntimeCheckResult(
                RuntimeCheckStatus.READY,
                "runtime_ready",
                "Runtime installation is complete.",
                appDataDir,
                List.of()
        );
    }

    public static RuntimeCheckResult repairRequired(
            Path appDataDir,
            String reasonCode,
            String reasonMessage,
            List<String> missingArtifacts
    ) {
        return new RuntimeCheckResult(
                RuntimeCheckStatus.REPAIR_REQUIRED,
                reasonCode,
                reasonMessage,
                appDataDir,
                missingArtifacts
        );
    }

    static Path resolveRuntimeAppDataDir() {
        String localAppData = trimToEmpty(System.getenv("LOCALAPPDATA"));
        if (!localAppData.isBlank()) {
            return Path.of(localAppData, APP_NAME);
        }

        String userHome = trimToEmpty(System.getProperty("user.home"));
        if (userHome.isBlank()) {
            return Path.of(System.getProperty("user.dir", "."), APP_NAME);
        }

        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return Path.of(userHome, "AppData", "Local", APP_NAME);
        }
        return Path.of(userHome, ".local", "share", APP_NAME);
    }

    private static Path resolveManagedRuntimePython(Path runtimeRoot) {
        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return runtimeRoot.resolve("python").resolve("python.exe");
        }
        return runtimeRoot.resolve("python").resolve("bin").resolve("python");
    }

    private static Path resolveExpectedBinary(Path appDataDir, String windowsName, String unixName) {
        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return appDataDir.resolve("assets").resolve(windowsName);
        }
        return appDataDir.resolve("assets").resolve(unixName);
    }

    private static Path resolveRequiredPath(Path appDataDir, String rawPath) {
        try {
            Path parsed = Path.of(rawPath);
            if (parsed.isAbsolute()) {
                return parsed.toAbsolutePath().normalize();
            }
            return appDataDir.resolve(parsed).normalize();
        } catch (Exception ignored) {
            return appDataDir.resolve(rawPath).normalize();
        }
    }

    private static boolean isRequiredPathAvailable(Path appDataDir, String rawPath) {
        String normalized = trimToEmpty(rawPath).replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains("cache/whisper/models/large-v3")) {
            return isWhisperModelAvailable(appDataDir);
        }
        if (normalized.contains("models--helsinki-nlp--opus-mt-en-cs")) {
            return isTranslationModelAvailable(appDataDir);
        }
        Path resolved = resolveRequiredPath(appDataDir, rawPath);
        return Files.exists(resolved);
    }

    private static boolean isWhisperModelAvailable(Path appDataDir) {
        Path exact = appDataDir.resolve(DEFAULT_WHISPER_MODEL_PATH);
        if (Files.isDirectory(exact)) {
            return true;
        }

        Path modelsRoot = appDataDir.resolve("cache").resolve("whisper").resolve("models");
        if (!Files.isDirectory(modelsRoot)) {
            return false;
        }

        try (Stream<Path> stream = Files.list(modelsRoot)) {
            return stream.anyMatch(path -> {
                if (!Files.isDirectory(path)) {
                    return false;
                }
                String name = trimToEmpty(path.getFileName().toString()).toLowerCase(Locale.ROOT);
                return "large-v3".equals(name) || name.contains("large-v3");
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isTranslationModelAvailable(Path appDataDir) {
        Path exact = appDataDir.resolve(DEFAULT_TRANSLATION_MODEL_PATH);
        if (Files.isDirectory(exact)) {
            return true;
        }

        Path hubRoot = appDataDir.resolve("cache").resolve("huggingface").resolve("hub");
        if (!Files.isDirectory(hubRoot)) {
            return false;
        }

        try (Stream<Path> stream = Files.list(hubRoot)) {
            return stream.anyMatch(path -> {
                if (!Files.isDirectory(path)) {
                    return false;
                }
                String name = trimToEmpty(path.getFileName().toString()).toLowerCase(Locale.ROOT);
                return name.startsWith("models--helsinki-nlp--opus-mt-en-cs");
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String toRelativeLabel(Path appDataDir, Path target) {
        try {
            Path normalizedAppData = appDataDir.toAbsolutePath().normalize();
            Path normalizedTarget = target.toAbsolutePath().normalize();
            if (normalizedTarget.startsWith(normalizedAppData)) {
                return normalizedAppData.relativize(normalizedTarget).toString().replace('\\', '/');
            }
            return normalizedTarget.toString();
        } catch (Exception ignored) {
            return target.toString();
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
