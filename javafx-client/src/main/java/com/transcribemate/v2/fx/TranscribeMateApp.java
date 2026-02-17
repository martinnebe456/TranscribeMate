package com.transcribemate.v2.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class TranscribeMateApp extends Application {
    private static final String APP_NAME = "TranscribeMate";
    private static final String RUNTIME_READY_MARKER_NAME = "runtime-ready.json";

    private BackendClient backendClient;
    private MainController controller;

    @Override
    public void start(Stage stage) throws Exception {
        AppFileLogger.log("INFO", "app.start", "Application start requested.");
        try {
            boolean mandatoryRuntimeSetup = isMandatoryRuntimeSetupRequiredOnLaunch();
            String backendStartupError = null;
            if (!mandatoryRuntimeSetup) {
                try {
                    backendClient = BackendClient.auto();
                    backendClient.start();
                } catch (Exception ex) {
                    backendClient = null;
                    backendStartupError = ex.getMessage();
                    AppFileLogger.logException("backend.start", ex);
                }
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/transcribemate/v2/fx/main-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            controller.initBackend(backendClient);

            if (mandatoryRuntimeSetup) {
                controller.reportBackendStartupFailure("Mandatory first-launch runtime setup is required.");
            } else if (backendStartupError != null && !backendStartupError.isBlank()) {
                controller.reportBackendStartupFailure(backendStartupError);
            }

            Scene scene = new Scene(root, 1460, 920);
            scene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());

            stage.setTitle("TranscribeMate - JavaFX + Python Backend");
            stage.setMinWidth(1200);
            stage.setMinHeight(760);
            stage.setScene(scene);
            controller.installShortcuts(scene);
            stage.show();
            AppFileLogger.log("INFO", "app.start", "Primary stage displayed.");

            if (mandatoryRuntimeSetup) {
                Platform.runLater(() -> controller.startMandatoryRuntimeSetupOnLaunch());
            }
        } catch (Exception ex) {
            AppFileLogger.logException("app.start", ex);
            throw ex;
        }
    }

    @Override
    public void stop() {
        AppFileLogger.log("INFO", "app.stop", "Application shutdown requested.");
        try {
            if (controller != null) {
                controller.dispose();
            }
        } catch (Exception ex) {
            AppFileLogger.logException("app.stop.controller", ex);
        }
        try {
            if (backendClient != null) {
                backendClient.close();
            }
        } catch (Exception ex) {
            AppFileLogger.logException("app.stop.backend", ex);
        }
    }

    public static void main(String[] args) {
        AppFileLogger.initialize();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String threadName = thread == null ? "unknown" : thread.getName();
            AppFileLogger.logException("uncaught." + threadName, throwable);
        });
        AppFileLogger.log("INFO", "app.main", "Launching JavaFX runtime.");
        launch(args);
    }

    private static boolean isMandatoryRuntimeSetupRequiredOnLaunch() {
        String projectRoot = System.getenv("TM_PROJECT_ROOT");
        if (projectRoot != null && !projectRoot.isBlank()) {
            // Dev mode: do not enforce first-launch bootstrap automatically.
            return false;
        }

        Path runtimeRoot = resolveRuntimeRoot();
        Path markerPath = runtimeRoot.resolve(RUNTIME_READY_MARKER_NAME);
        if (!Files.isRegularFile(markerPath)) {
            return true;
        }

        Path pythonExe = resolveManagedRuntimePython(runtimeRoot);
        return !Files.isRegularFile(pythonExe);
    }

    private static Path resolveManagedRuntimePython(Path runtimeRoot) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return runtimeRoot.resolve("python").resolve("python.exe");
        }
        return runtimeRoot.resolve("python").resolve("bin").resolve("python");
    }

    private static Path resolveRuntimeRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, APP_NAME, "runtime");
        }

        String userHome = System.getProperty("user.home", "");
        if (userHome == null || userHome.isBlank()) {
            return Path.of(System.getProperty("user.dir", "."), APP_NAME, "runtime");
        }

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return Path.of(userHome, "AppData", "Local", APP_NAME, "runtime");
        }
        return Path.of(userHome, ".local", "share", APP_NAME, "runtime");
    }
}
