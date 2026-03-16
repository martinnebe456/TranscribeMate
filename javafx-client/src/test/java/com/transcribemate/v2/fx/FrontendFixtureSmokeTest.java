package com.transcribemate.v2.fx;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testfx.api.FxToolkit;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "tm.runFrontendSmoke", matches = "(?i:true|1)")
class FrontendFixtureSmokeTest extends ApplicationTest {
    private static final String OFFLINE_PROJECT_NAME = "fixture-smoke";
    private static final String VIDEO_PROJECT_NAME = "video-smoke";

    private static TranscribeMateApp application;
    private static Path repoRoot;
    private static Path appDataDir;
    private static Path fixturesDir;
    private static Path manifestPath;
    private static Path artifactDir;
    private static Path pythonBin;

    @BeforeAll
    static void beforeAll() throws Exception {
        repoRoot = resolveProjectRoot();
        appDataDir = AppRuntimePaths.resolveAppDataDir();
        fixturesDir = resolvePathProperty("tm.fixtureDir", repoRoot.resolve("tests/test_files"));
        manifestPath = repoRoot.resolve("tests/test_files/manifest.json");
        artifactDir = resolvePathProperty("tm.frontendSmokeArtifactDir", repoRoot.resolve("build/tm-frontend-smoke-artifacts"));
        pythonBin = resolvePython(repoRoot);

        Files.createDirectories(appDataDir);
        Files.createDirectories(artifactDir);
        System.setProperty("tm.uiTestMode", "true");
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (application != null) {
            FxToolkit.setupFixture(() -> {
                try {
                    application.stop();
                } catch (Exception ignored) {
                    // Best effort only.
                }
            });
        }
        FxToolkit.cleanupStages();
        System.clearProperty("tm.uiTestMode");
    }

    @Override
    public void start(Stage stage) throws Exception {
        application = new TranscribeMateApp();
        application.start(stage);
    }

    @Test
    void fullFrontendSmokeOverFixtures() throws Exception {
        waitForCondition(Duration.ofSeconds(30), () -> lookup("#appTitleLabel").queryAs(javafx.scene.control.Label.class).isVisible());
        waitForCondition(Duration.ofSeconds(30), () -> lookup("#navProjectsButton").queryAs(Button.class).isVisible());

        triggerButton("#navDashboardButton");
        triggerButton("#navProjectsButton");
        triggerButton("#navFilesButton");
        triggerButton("#navModulesButton");
        triggerButton("#navOperationsButton");
        triggerButton("#navSettingsButton");
        triggerButton("#navProjectsButton");

        Path offlineProjectRoot = createProject(OFFLINE_PROJECT_NAME);
        seedOfflineFixtures(offlineProjectRoot);
        configureOfflineFixtureRun(offlineProjectRoot);

        triggerButton("#navSettingsButton");
        enableSummarySettings();
        triggerButton("#navModulesButton");

        startAndWaitForCompletion(Duration.ofMinutes(30));
        runPythonValidator(
                offlineProjectRoot.resolve("output"),
                artifactDir.resolve("frontend-offline-validation.json")
        );

        Path videoProjectRoot = createProject(VIDEO_PROJECT_NAME);
        Path smokeVideo = seedVideoSmokeFixture(videoProjectRoot);
        configureLocalVideoSmokeRun(videoProjectRoot);

        triggerButton("#navModulesButton");
        startAndWaitForCompletion(Duration.ofMinutes(20));
        assertVideoOutputsExist(videoProjectRoot.resolve("output"), fileStem(smokeVideo));

        Path reportPath = artifactDir.resolve("frontend-smoke-report.json");
        String reportJson = "{\n"
                + "  \"offline_project\": \"" + escapeJson(offlineProjectRoot.toString()) + "\",\n"
                + "  \"video_project\": \"" + escapeJson(videoProjectRoot.toString()) + "\",\n"
                + "  \"artifact_dir\": \"" + escapeJson(artifactDir.toString()) + "\"\n"
                + "}\n";
        Files.writeString(reportPath, reportJson, StandardCharsets.UTF_8);
    }

    private static Path resolveProjectRoot() {
        String property = System.getProperty("tm.project.root", "").trim();
        if (!property.isBlank()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize().getParent();
    }

    private static Path resolvePathProperty(String key, Path fallback) {
        String raw = System.getProperty(key, "").trim();
        if (!raw.isBlank()) {
            return Path.of(raw).toAbsolutePath().normalize();
        }
        return fallback.toAbsolutePath().normalize();
    }

    private static Path resolvePython(Path root) {
        List<Path> candidates = List.of(
                root.resolve(".venv/bin/python3"),
                root.resolve(".venv/bin/python"),
                root.resolve(".venv/Scripts/python.exe")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Frontend smoke test requires a repository .venv Python interpreter.");
    }

    private Path createProject(String projectName) throws Exception {
        triggerButton("#navProjectsButton");
        waitForCondition(Duration.ofSeconds(10), () -> lookup("#projectCreateButton").queryAs(Button.class).isVisible());
        waitForCondition(Duration.ofSeconds(10), () -> !lookup("#projectCreateButton").queryAs(Button.class).isDisabled());

        System.setProperty("tm.uiTestProjectName", projectName);
        try {
            interact(() -> lookup("#projectCreateButton").queryAs(Button.class).fire());
            Path projectRoot = appDataDir.resolve("workspace").resolve("projects").resolve(projectName);
            waitForCondition(Duration.ofSeconds(20), () -> Files.isDirectory(projectRoot));
            waitForCondition(Duration.ofSeconds(20), () -> lookup("#activeProjectLabel")
                    .queryAs(javafx.scene.control.Label.class)
                    .getText()
                    .toLowerCase(Locale.ROOT)
                    .contains(projectName.toLowerCase(Locale.ROOT)));
            return projectRoot;
        } finally {
            System.clearProperty("tm.uiTestProjectName");
        }
    }

    private void seedOfflineFixtures(Path projectRoot) throws IOException {
        Path inputDir = projectRoot.resolve("input");
        Files.createDirectories(inputDir);
        List<Path> sources = Files.list(fixturesDir)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))
                .sorted()
                .toList();
        for (Path source : sources) {
            Files.copy(source, inputDir.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path seedVideoSmokeFixture(Path projectRoot) throws Exception {
        Path inputDir = projectRoot.resolve("input");
        Files.createDirectories(inputDir);
        List<Path> audioFixtures = Files.list(fixturesDir)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3"))
                .sorted(Comparator.comparingLong(FrontendFixtureSmokeTest::safeSize))
                .toList();
        Path sourceAudio = audioFixtures.get(0);
        Path ffmpeg = appDataDir.resolve("assets").resolve("ffmpeg");
        if (!Files.isRegularFile(ffmpeg)) {
            throw new IllegalStateException("Bundled ffmpeg not found under TM_APP_DATA_DIR/assets.");
        }

        Path outputVideo = inputDir.resolve(sourceAudio.getFileName().toString().replace(".mp3", "-video-smoke.mp4"));
        ProcessBuilder builder = new ProcessBuilder(
                ffmpeg.toString(),
                "-y",
                "-f", "lavfi",
                "-i", "color=c=black:s=1280x720:r=30:d=600",
                "-i", sourceAudio.toString(),
                "-shortest",
                "-c:v", "mpeg4",
                "-q:v", "5",
                "-c:a", "aac",
                "-movflags", "+faststart",
                outputVideo.toString()
        );
        builder.directory(repoRoot.toFile());
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Failed to generate local video smoke fixture.\nstdout:\n"
                            + readStream(process.getInputStream())
                            + "\nstderr:\n"
                            + readStream(process.getErrorStream())
            );
        }
        return outputVideo;
    }

    private void configureOfflineFixtureRun(Path projectRoot) throws Exception {
        triggerButton("#navModulesButton");
        interact(() -> {
            ComboBox<String> moduleBox = lookup("#moduleSwitcherBox").queryAs(ComboBox.class);
            moduleBox.getSelectionModel().select("Offline A/V Transcript");

            TextField localPathField = lookup("#localPathField").queryAs(TextField.class);
            localPathField.setText(projectRoot.resolve("input").toString());

            TextField outputDirField = lookup("#outputDirField").queryAs(TextField.class);
            outputDirField.setText(projectRoot.resolve("output").toString());

            ComboBox<String> modelField = lookup("#modelField").queryAs(ComboBox.class);
            modelField.getSelectionModel().select("small");

            ComboBox<String> outputModeBox = lookup("#outputModeBox").queryAs(ComboBox.class);
            outputModeBox.getSelectionModel().select("txt_only");
        });
    }

    private void configureLocalVideoSmokeRun(Path projectRoot) throws Exception {
        triggerButton("#navSettingsButton");
        interact(() -> {
            CheckBox summaryAiBox = lookup("#summaryAiBox").queryAs(CheckBox.class);
            summaryAiBox.setSelected(false);
        });

        triggerButton("#navModulesButton");
        interact(() -> {
            ComboBox<String> moduleBox = lookup("#moduleSwitcherBox").queryAs(ComboBox.class);
            moduleBox.getSelectionModel().select("Offline A/V Transcript");

            TextField localPathField = lookup("#localPathField").queryAs(TextField.class);
            localPathField.setText(projectRoot.resolve("input").toString());

            TextField outputDirField = lookup("#outputDirField").queryAs(TextField.class);
            outputDirField.setText(projectRoot.resolve("output").toString());

            ComboBox<String> modelField = lookup("#modelField").queryAs(ComboBox.class);
            modelField.getSelectionModel().select("small");

            ComboBox<String> outputModeBox = lookup("#outputModeBox").queryAs(ComboBox.class);
            outputModeBox.getSelectionModel().select("video_subs");

            ComboBox<String> subtitleModeBox = lookup("#subtitleModeBox").queryAs(ComboBox.class);
            subtitleModeBox.getSelectionModel().select("soft");
        });
    }

    private void enableSummarySettings() {
        interact(() -> {
            CheckBox summaryAiBox = lookup("#summaryAiBox").queryAs(CheckBox.class);
            summaryAiBox.setSelected(true);

            ComboBox<String> summaryLangBox = lookup("#summaryLangBox").queryAs(ComboBox.class);
            summaryLangBox.getSelectionModel().select("cs");

            ComboBox<String> summaryTierBox = lookup("#summaryModelTierBox").queryAs(ComboBox.class);
            summaryTierBox.getSelectionModel().select("low");
        });
    }

    private void startAndWaitForCompletion(Duration timeout) throws Exception {
        Button startButton = lookup("#startButton").queryAs(Button.class);
        waitForCondition(Duration.ofSeconds(10), () -> startButton.isVisible() && !startButton.isDisabled());
        interact(startButton::fire);
        waitForCondition(Duration.ofSeconds(30), startButton::isDisabled);
        waitForCondition(timeout, () -> !startButton.isDisabled());
    }

    private void triggerButton(String query) throws Exception {
        waitForCondition(Duration.ofSeconds(10), () -> {
            Button button = lookup(query).queryAs(Button.class);
            return button.isVisible() && !button.isDisabled();
        });
        interact(() -> lookup(query).queryAs(Button.class).fire());
    }

    private void runPythonValidator(Path outputRoot, Path reportPath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                pythonBin.toString(),
                "tests/full_suite/validate_fixture_outputs.py",
                "--output-root", outputRoot.toString(),
                "--fixtures-dir", fixturesDir.toString(),
                "--manifest", manifestPath.toString(),
                "--report-file", reportPath.toString()
        );
        builder.directory(repoRoot.toFile());
        builder.environment().put("TM_APP_DATA_DIR", appDataDir.toString());
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Fixture output validation failed.\nstdout:\n"
                            + readStream(process.getInputStream())
                            + "\nstderr:\n"
                            + readStream(process.getErrorStream())
            );
        }
    }

    private void assertVideoOutputsExist(Path outputRoot, String sourceStem) throws Exception {
        waitForCondition(Duration.ofMinutes(5), () -> {
            try {
                return Files.walk(outputRoot)
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                        .anyMatch(name -> name.contains(sourceStem.toLowerCase(Locale.ROOT)) && name.endsWith(".mp4"));
            } catch (IOException ignored) {
                return false;
            }
        });

        boolean srtFound;
        boolean mp4Found;
        try (var stream = Files.walk(outputRoot)) {
            List<String> names = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .toList();
            srtFound = names.stream().anyMatch(name -> name.contains(sourceStem.toLowerCase(Locale.ROOT)) && name.endsWith(".srt"));
            mp4Found = names.stream().anyMatch(name -> name.contains(sourceStem.toLowerCase(Locale.ROOT)) && name.endsWith(".mp4"));
        }
        assertTrue(srtFound, "Expected a generated SRT artifact for the local video smoke.");
        assertTrue(mp4Found, "Expected a generated MP4 artifact for the local video smoke.");
    }

    private static void waitForCondition(Duration timeout, Condition condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Throwable lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.test()) {
                    return;
                }
            } catch (Throwable ex) {
                lastError = ex;
            }
            Thread.sleep(250L);
        }
        if (lastError != null) {
            throw new AssertionError("Timed out waiting for condition.", lastError);
        }
        assertFalse(true, "Timed out waiting for condition after " + timeout);
    }

    private static String readStream(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            input.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String fileStem(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface Condition {
        boolean test() throws Exception;
    }
}
