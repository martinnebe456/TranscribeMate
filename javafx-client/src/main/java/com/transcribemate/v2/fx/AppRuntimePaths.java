package com.transcribemate.v2.fx;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppRuntimePaths {
    static final String APP_NAME = "TranscribeMate";

    private AppRuntimePaths() {
    }

    static boolean isWindows() {
        return osName().contains("win");
    }

    static boolean isMac() {
        return osName().contains("mac");
    }

    static boolean isLinux() {
        String os = osName();
        return os.contains("linux") || os.contains("nux");
    }

    static boolean defaultGpuPreferenceSelected() {
        return isWindows();
    }

    static boolean isGpuPreferenceEditable() {
        return !isLinux();
    }

    static boolean normalizeGpuPreference(boolean requested) {
        return !isLinux() && requested;
    }

    static String gpuPreferenceLabelText() {
        if (isMac()) {
            return "Prefer GPU (CUDA/NVIDIA only; macOS uses CPU-only in this release)";
        }
        if (isLinux()) {
            return "Prefer GPU (Linux build is CPU-only in this release)";
        }
        return "Prefer GPU (CUDA/NVIDIA)";
    }

    static String runtimeProfileSetupLine() {
        if (isMac()) {
            return "- CPU-focused runtime profile for Apple Silicon (CUDA/NVIDIA path disabled)";
        }
        if (isLinux()) {
            return "- CPU-only runtime profile for Linux (GPU provisioning disabled in this release)";
        }
        return "- NVIDIA GPU detection and CUDA Torch provisioning (fallback to CPU runtime)";
    }

    static Path resolveAppDataDir() {
        Path override = normalizePath(firstNonBlank(
                System.getProperty("tm.appDataDir"),
                System.getenv("TM_APP_DATA_DIR")
        ));
        if (override != null) {
            return override;
        }

        String localAppData = trimToEmpty(System.getenv("LOCALAPPDATA"));
        if (!localAppData.isBlank()) {
            return Path.of(localAppData, APP_NAME);
        }

        String userHome = trimToEmpty(System.getProperty("user.home"));
        if (userHome.isBlank()) {
            return Path.of(System.getProperty("user.dir", "."), APP_NAME);
        }

        if (isWindows()) {
            return Path.of(userHome, "AppData", "Local", APP_NAME);
        }
        if (isMac()) {
            return Path.of(userHome, "Library", "Application Support", APP_NAME);
        }

        String xdgDataHome = trimToEmpty(System.getenv("XDG_DATA_HOME"));
        if (!xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, APP_NAME);
        }
        return Path.of(userHome, ".local", "share", APP_NAME);
    }

    static Path resolveLogsDir() {
        return resolveAppDataDir().resolve("Logs");
    }

    static Path resolveDefaultWorkspaceRoot(Path appDataDir) {
        Path normalizedAppData = appDataDir == null ? resolveAppDataDir() : appDataDir;
        if (isMac() && !hasExplicitAppDataOverride()) {
            String userHome = trimToEmpty(System.getProperty("user.home"));
            if (!userHome.isBlank()) {
                Path preferred = Path.of(userHome, "Documents", APP_NAME, "workspace");
                Path legacy = normalizedAppData.resolve("workspace");
                if (Files.exists(preferred) || !Files.exists(legacy)) {
                    return preferred;
                }
                return legacy;
            }
        }
        return normalizedAppData.resolve("workspace");
    }

    static Path resolveProjectRoot() {
        Path envRoot = normalizePath(firstNonBlank(
                System.getProperty("tm.project.root"),
                System.getenv("TM_PROJECT_ROOT")
        ));
        if (containsRepositoryLayout(envRoot)) {
            return envRoot;
        }

        for (Path candidate : searchRootCandidates()) {
            if (containsRepositoryLayout(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static Path resolveBackendRoot() {
        Path envRoot = normalizePath(firstNonBlank(
                System.getProperty("tm.project.root"),
                System.getenv("TM_PROJECT_ROOT")
        ));
        Path resolvedFromEnv = resolveBackendRootUnder(envRoot);
        if (resolvedFromEnv != null) {
            return resolvedFromEnv;
        }

        for (Path candidate : searchRootCandidates()) {
            Path resolved = resolveBackendRootUnder(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    static Path resolveRuntimeRoot(Path appDataDir) {
        Path normalized = appDataDir == null ? resolveAppDataDir() : appDataDir;
        return normalized.resolve("runtime");
    }

    static List<Path> managedRuntimePythonCandidates(Path appDataDir) {
        Path runtimeRoot = resolveRuntimeRoot(appDataDir);
        List<Path> candidates = new ArrayList<>();
        if (isWindows()) {
            candidates.add(runtimeRoot.resolve("python").resolve("python.exe"));
            candidates.add(runtimeRoot.resolve("venv").resolve("Scripts").resolve("python.exe"));
            return candidates;
        }

        candidates.add(runtimeRoot.resolve("python").resolve("bin").resolve("python3"));
        candidates.add(runtimeRoot.resolve("python").resolve("bin").resolve("python"));
        candidates.add(runtimeRoot.resolve("venv").resolve("bin").resolve("python3"));
        candidates.add(runtimeRoot.resolve("venv").resolve("bin").resolve("python"));
        return candidates;
    }

    static Path preferredManagedRuntimePython(Path appDataDir) {
        List<Path> candidates = managedRuntimePythonCandidates(appDataDir);
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? resolveRuntimeRoot(appDataDir).resolve("python") : candidates.get(0);
    }

    static Path resolveExpectedBinary(Path appDataDir, String windowsName, String unixName) {
        Path normalized = appDataDir == null ? resolveAppDataDir() : appDataDir;
        return normalized.resolve("assets").resolve(isWindows() ? windowsName : unixName);
    }

    static String bootstrapScriptFileName() {
        return isWindows() ? "bootstrap_runtime.ps1" : "bootstrap_runtime.sh";
    }

    static boolean isUiTestMode() {
        String raw = firstNonBlank(
                System.getProperty("tm.uiTestMode"),
                System.getenv("TM_UI_TEST_MODE")
        );
        if (raw == null) {
            return false;
        }
        String normalized = trimToEmpty(raw).toLowerCase(Locale.ROOT);
        return normalized.equals("1")
                || normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("on");
    }

    private static boolean hasExplicitAppDataOverride() {
        return firstNonBlank(
                System.getProperty("tm.appDataDir"),
                System.getenv("TM_APP_DATA_DIR")
        ) != null;
    }

    private static List<Path> searchRootCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addSelfAndParents(candidates, codeSourceDir());
        addSelfAndParents(candidates, normalizePath(System.getProperty("user.dir")));
        return new ArrayList<>(candidates);
    }

    private static void addSelfAndParents(Set<Path> sink, Path start) {
        Path current = start;
        while (current != null) {
            sink.add(current);
            current = current.getParent();
        }
    }

    private static Path codeSourceDir() {
        try {
            URI uri = AppRuntimePaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codePath = Path.of(uri).toAbsolutePath().normalize();
            return Files.isDirectory(codePath) ? codePath : codePath.getParent();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveBackendRootUnder(Path candidateRoot) {
        if (candidateRoot == null) {
            return null;
        }

        List<Path> candidates = List.of(
                candidateRoot.resolve("Contents").resolve("app").resolve("backend"),
                candidateRoot.resolve("lib").resolve("app").resolve("backend"),
                candidateRoot.resolve("app").resolve("backend"),
                candidateRoot.resolve("backend"),
                candidateRoot
        );
        for (Path candidate : candidates) {
            if (containsBackendPackage(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean containsBackendPackage(Path root) {
        if (root == null) {
            return false;
        }
        return Files.isRegularFile(
                root.resolve("transcribemate").resolve("v2").resolve("backend").resolve("server.py")
        );
    }

    private static boolean containsRepositoryLayout(Path root) {
        if (root == null) {
            return false;
        }
        return containsBackendPackage(root)
                && Files.isRegularFile(root.resolve("javafx-client").resolve("pom.xml"));
    }

    private static Path normalizePath(String rawPath) {
        String normalized = trimToEmpty(rawPath);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return Path.of(normalized).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String osName() {
        return trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToEmpty(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
