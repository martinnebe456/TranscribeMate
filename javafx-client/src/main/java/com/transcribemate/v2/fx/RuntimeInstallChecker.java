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

    private record RuntimeMarkerMetadata(
            Path pythonPath,
            Path ffmpegPath,
            Path ffprobePath,
            List<String> requiredPaths
    ) {
    }

    public static RuntimeCheckResult evaluate() {
        Path projectRoot = AppRuntimePaths.resolveProjectRoot();
        Path appDataDir = resolveRuntimeAppDataDir();
        if (projectRoot != null) {
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
        RuntimeMarkerMetadata marker = loadMarker(markerPath, appDataDir);
        Path pythonExe = marker != null && marker.pythonPath() != null
                ? marker.pythonPath()
                : resolveManagedRuntimePython(runtimeRoot);
        Path ffmpegPath = marker != null && marker.ffmpegPath() != null
                ? marker.ffmpegPath()
                : resolveExpectedBinary(appDataDir, "ffmpeg.exe", "ffmpeg");
        Path ffprobePath = marker != null && marker.ffprobePath() != null
                ? marker.ffprobePath()
                : resolveExpectedBinary(appDataDir, "ffprobe.exe", "ffprobe");
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
            if (marker == null) {
                missing.add(toRelativeLabel(appDataDir, markerPath) + " (invalid JSON)");
            } else {
                for (String rawPath : marker.requiredPaths()) {
                    boolean present = isRequiredPathAvailable(appDataDir, rawPath);
                    if (!present) {
                        missing.add("marker_required:" + rawPath);
                    }
                }
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
        return AppRuntimePaths.resolveAppDataDir();
    }

    private static Path resolveManagedRuntimePython(Path runtimeRoot) {
        return AppRuntimePaths.preferredManagedRuntimePython(runtimeRoot == null ? null : runtimeRoot.getParent());
    }

    private static Path resolveExpectedBinary(Path appDataDir, String windowsName, String unixName) {
        return AppRuntimePaths.resolveExpectedBinary(appDataDir, windowsName, unixName);
    }

    private static RuntimeMarkerMetadata loadMarker(Path markerPath, Path appDataDir) {
        if (markerPath == null || appDataDir == null || !Files.isRegularFile(markerPath)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(markerPath.toFile());
            List<String> requiredPaths = new ArrayList<>();
            JsonNode requiredPathsNode = root.path("components").path("required_paths");
            if (requiredPathsNode.isArray()) {
                for (JsonNode item : requiredPathsNode) {
                    String rawPath = trimToEmpty(item.asText(""));
                    if (!rawPath.isBlank()) {
                        requiredPaths.add(rawPath);
                    }
                }
            }
            Path pythonPath = resolveOptionalMarkerPath(appDataDir, root.path("python_relpath").asText(""));
            Path ffmpegPath = resolveOptionalMarkerPath(appDataDir, root.path("ffmpeg_relpath").asText(""));
            Path ffprobePath = resolveOptionalMarkerPath(appDataDir, root.path("ffprobe_relpath").asText(""));
            return new RuntimeMarkerMetadata(pythonPath, ffmpegPath, ffprobePath, List.copyOf(requiredPaths));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveOptionalMarkerPath(Path appDataDir, String rawPath) {
        String normalized = trimToEmpty(rawPath);
        if (normalized.isBlank()) {
            return null;
        }
        return resolveRequiredPath(appDataDir, normalized);
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
