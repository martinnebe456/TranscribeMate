package com.transcribemate.v2.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;

public class TranscribeMateApp extends Application {
    private BackendClient backendClient;
    private MainController controller;

    @Override
    public void start(Stage stage) throws Exception {
        AppFileLogger.log("INFO", "app.start", "Application start requested.");
        try {
            RuntimeInstallChecker.RuntimeCheckResult runtimeCheck = RuntimeInstallChecker.evaluate();
            if (runtimeCheck.isReady()) {
                try {
                    backendClient = BackendClient.auto();
                    backendClient.start();
                } catch (Exception ex) {
                    backendClient = null;
                    AppFileLogger.logException("backend.start", ex);
                    String reason = ex.getMessage() == null ? "Backend process failed to start." : ex.getMessage();
                    runtimeCheck = RuntimeInstallChecker.repairRequired(
                            runtimeCheck.appDataDir(),
                            "backend_start_failed",
                            "Backend startup failed. Runtime repair is required before continuing.",
                            List.of("backend_start_error: " + reason)
                    );
                }
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/transcribemate/v2/fx/main-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            controller.initBackend(backendClient);

            if (!runtimeCheck.isReady()) {
                controller.reportBackendStartupFailure(runtimeCheck.reasonMessage());
            }

            Scene scene = new Scene(root, 1460, 920);
            scene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());

            stage.setTitle("TranscribeMate - JavaFX + Python Backend");
            stage.setMinWidth(1200);
            stage.setMinHeight(760);
            stage.setScene(scene);
            controller.installShortcuts(scene);
            stage.show();
            stage.setMaximized(true);
            stage.setFullScreen(false);
            AppFileLogger.log("INFO", "app.start", "Primary stage displayed.");

            RuntimeInstallChecker.RuntimeCheckResult startupRuntimeCheck = runtimeCheck;
            if (!startupRuntimeCheck.isReady()) {
                Platform.runLater(() -> controller.startMandatoryRuntimeSetupOnLaunch(startupRuntimeCheck));
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
}
