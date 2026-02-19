package com.transcribemate.v2.fx;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Window dedicated to a single long-running backend job.
 * <p>
 * It tracks:
 * - overall progress + ETA
 * - current step progress + derived ETA
 * - activity timeline (ordered in first-seen order)
 * - last log line
 * <p>
 * This class owns only UI state. It does not start jobs; callers feed it with
 * progress events from {@code MainController}/{@code BackendClient}.
 */
public final class JobProgressWindow {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Stage stage;
    private final VBox root;
    private final Label statusLabel = new Label("Status: Waiting");
    private final Label jobLabel = new Label("Job: -");
    private final Label activityLabel = new Label("Activity: -");
    private final Label itemLabel = new Label("Item: -");
    private final ProgressBar overallProgressBar = new ProgressBar(0.0);
    private final Label overallProgressLabel = new Label("0%");
    private final Label overallEtaLabel = new Label("Overall ETA: --:--");
    private final ProgressBar stepProgressBar = new ProgressBar(0.0);
    private final Label stepLabel = new Label("Current step: -");
    private final Label stepEtaLabel = new Label("Step ETA: --:--");
    private final Label lastLogLabel = new Label("Last log: -");
    private final TextArea activitiesArea = new TextArea();
    private final Label updatedLabel = new Label("Updated: -");
    private final Button cancelButton = new Button("Cancel job");
    private final Button closeButton = new Button("Close");
    // LinkedHashMap preserves insertion order so timeline lines stay stable for users.
    private final Map<String, ActivityState> activities = new LinkedHashMap<>();

    private Runnable cancelAction = () -> { };
    private String currentStep = "";
    // Anchor point for incremental step ETA estimation between progress updates.
    private long stepAnchorMillis = -1L;
    private double stepAnchorPercent = -1.0;

    /**
     * Per-step display state used by the timeline text area.
     *
     * @param percent        0-100 when determinate
     * @param indeterminate  true when backend cannot provide numeric step progress
     * @param eta            already formatted ETA text for the row
     * @param done           true once step is considered complete
     */
    private record ActivityState(double percent, boolean indeterminate, String eta, boolean done) {
    }

    /**
     * Builds the stage and static controls once. Dynamic values are reset in
     * {@link #showForJob(String, String, String, Runnable)} for each new job.
     */
    public JobProgressWindow() {
        this.stage = new Stage();
        this.stage.setTitle("TranscribeMate - Processing");

        Label titleLabel = new Label("Processing Progress");
        titleLabel.getStyleClass().add("section-title");

        overallProgressBar.setPrefWidth(500.0);
        overallProgressBar.setMaxWidth(Double.MAX_VALUE);
        stepProgressBar.setPrefWidth(500.0);
        stepProgressBar.setMaxWidth(Double.MAX_VALUE);

        activitiesArea.setEditable(false);
        activitiesArea.setWrapText(true);
        activitiesArea.setPrefRowCount(8);
        activitiesArea.setText("No activity progress yet.");
        activitiesArea.getStyleClass().add("job-progress-activities-area");

        lastLogLabel.setWrapText(true);
        lastLogLabel.setMaxWidth(Double.MAX_VALUE);
        lastLogLabel.getStyleClass().add("small-label");

        HBox overallRow = new HBox(8.0, new Label("Overall"), overallProgressBar, overallProgressLabel);
        HBox.setHgrow(overallProgressBar, Priority.ALWAYS);
        overallRow.setFillHeight(true);

        HBox stepRow = new HBox(8.0, new Label("Step"), stepProgressBar);
        HBox.setHgrow(stepProgressBar, Priority.ALWAYS);
        stepRow.setFillHeight(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        closeButton.setDisable(true);
        cancelButton.setOnAction(event -> cancelAction.run());
        closeButton.setOnAction(event -> stage.hide());
        HBox actions = new HBox(8.0, cancelButton, spacer, closeButton);

        root = new VBox(
                10.0,
                titleLabel,
                statusLabel,
                jobLabel,
                activityLabel,
                itemLabel,
                overallRow,
                overallEtaLabel,
                stepLabel,
                stepRow,
                stepEtaLabel,
                new Label("Activity timeline"),
                activitiesArea,
                new Label("Last log line"),
                lastLogLabel,
                updatedLabel,
                actions
        );
        root.setPadding(new Insets(12.0));
        root.getStyleClass().addAll("app-shell", "card-pane", "job-progress-window", "theme-light");
        VBox.setVgrow(activitiesArea, Priority.ALWAYS);

        Scene scene = new Scene(root, 760, 560);
        scene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            // While a job is running, force explicit cancel flow instead of silent close.
            if (closeButton.isDisabled()) {
                event.consume();
            }
        });
    }

    /**
     * Applies one of the global theme classes used by the frontend stylesheet.
     * Falls back to light theme when value is null/blank.
     */
    public void applyThemeClass(String themeClass) {
        root.getStyleClass().removeAll("theme-light", "theme-dark", "theme-dracula");
        if (themeClass == null || themeClass.isBlank()) {
            root.getStyleClass().add("theme-light");
        } else {
            root.getStyleClass().add(themeClass);
        }
    }

    /**
     * Resets the window to initial running state and brings it to foreground.
     * This method is expected to be called exactly once per new job.
     */
    public void showForJob(String jobId, String activityName, String sourceMode, Runnable onCancelAction) {
        cancelAction = onCancelAction == null ? () -> { } : onCancelAction;
        activities.clear();
        currentStep = "";
        stepAnchorMillis = -1L;
        stepAnchorPercent = -1.0;

        statusLabel.setText("Status: Running");
        jobLabel.setText("Job: " + normalizeText(jobId));
        activityLabel.setText("Activity: " + normalizeText(activityName) + " | Source: " + normalizeText(sourceMode));
        itemLabel.setText("Item: 0/0");
        overallProgressBar.setProgress(0.0);
        overallProgressLabel.setText("0%");
        overallEtaLabel.setText("Overall ETA: --:--");
        stepProgressBar.setProgress(0.0);
        stepLabel.setText("Current step: -");
        stepEtaLabel.setText("Step ETA: --:--");
        lastLogLabel.setText("Last log: -");
        activitiesArea.setText("Waiting for first progress event...");
        cancelButton.setDisable(false);
        closeButton.setDisable(true);
        touchUpdatedStamp();

        stage.show();
        stage.toFront();
    }

    /**
     * Applies one progress event to all relevant UI fields.
     *
     * @param step          backend step name (raw value, later humanized)
     * @param stepPct       step percent in [0..100] when available, null otherwise
     * @param overallPct    overall percent in [0..100] (values are clamped)
     * @param indeterminate true when overall/step progress is not reliably determinate
     * @param itemIndex     currently processed item index (1-based from backend in practice)
     * @param totalItems    total item count for batch-like operations
     * @param overallEta    preformatted ETA text from backend
     */
    public void updateProgress(
            String step,
            Double stepPct,
            double overallPct,
            boolean indeterminate,
            int itemIndex,
            int totalItems,
            String overallEta
    ) {
        // Defensive clamp keeps UI sane even if backend reports out-of-range values.
        double clampedOverall = Math.max(0.0, Math.min(100.0, overallPct));
        if (indeterminate) {
            overallProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            overallProgressLabel.setText(String.format(Locale.ROOT, "%.0f%% (estimating)", clampedOverall));
        } else {
            overallProgressBar.setProgress(clampedOverall / 100.0);
            overallProgressLabel.setText(String.format(Locale.ROOT, "%.0f%%", clampedOverall));
        }

        overallEtaLabel.setText("Overall ETA: " + normalizeEta(overallEta));

        if (totalItems > 0) {
            itemLabel.setText("Item: " + Math.max(1, itemIndex) + "/" + totalItems);
        }

        String normalizedStep = normalizeText(step);
        if (!normalizedStep.isBlank() && !normalizedStep.equals("-")) {
            if (!currentStep.equals(normalizedStep)) {
                // Step boundary: close previous timeline row and reset ETA anchor.
                markPreviousStepDone();
                currentStep = normalizedStep;
                stepAnchorMillis = -1L;
                stepAnchorPercent = -1.0;
            }
            stepLabel.setText("Current step: " + formatStepTitle(normalizedStep));
        }

        String stepEta = estimateStepEta(stepPct, indeterminate);
        stepEtaLabel.setText("Step ETA: " + stepEta);

        if (stepPct == null || indeterminate) {
            stepProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            // Keep row present even when percent is unknown, so users see active step name.
            activities.put(
                    currentStep,
                    new ActivityState(0.0, true, stepEta, false)
            );
        } else {
            double clampedStep = Math.max(0.0, Math.min(100.0, stepPct));
            stepProgressBar.setProgress(clampedStep / 100.0);
            boolean done = clampedStep >= 100.0;
            activities.put(
                    currentStep,
                    // Keep timeline consistent: a done step always displays "0s" ETA.
                    new ActivityState(clampedStep, false, done ? "0s" : stepEta, done)
            );
        }

        renderActivities();
        touchUpdatedStamp();
    }

    /**
     * Updates the single-line "last log" field shown under the timeline.
     */
    public void updateLastLog(String line) {
        String normalized = normalizeText(line);
        if (normalized.isBlank()) {
            return;
        }
        lastLogLabel.setText("Last log: " + normalized);
        touchUpdatedStamp();
    }

    /**
     * Marks successful completion and unlocks manual close.
     */
    public void markCompleted(String outputDir) {
        markPreviousStepDone();
        statusLabel.setText("Status: Completed");
        overallProgressBar.setProgress(1.0);
        overallProgressLabel.setText("100%");
        overallEtaLabel.setText("Overall ETA: 0s");
        stepProgressBar.setProgress(1.0);
        stepEtaLabel.setText("Step ETA: 0s");
        if (outputDir != null && !outputDir.isBlank()) {
            lastLogLabel.setText("Last log: Output ready at " + outputDir);
        }
        cancelButton.setDisable(true);
        closeButton.setDisable(false);
        touchUpdatedStamp();
    }

    /**
     * Marks terminal failure state and keeps last error message visible.
     */
    public void markFailed(String message) {
        statusLabel.setText("Status: Failed");
        stepEtaLabel.setText("Step ETA: --:--");
        overallEtaLabel.setText("Overall ETA: --:--");
        updateLastLog(message);
        cancelButton.setDisable(true);
        closeButton.setDisable(false);
        touchUpdatedStamp();
    }

    /**
     * Marks terminal cancellation state.
     */
    public void markCancelled() {
        statusLabel.setText("Status: Cancelled");
        stepEtaLabel.setText("Step ETA: --:--");
        overallEtaLabel.setText("Overall ETA: --:--");
        cancelButton.setDisable(true);
        closeButton.setDisable(false);
        touchUpdatedStamp();
    }

    /**
     * Intermediate state after user clicks cancel and before backend confirms cancellation.
     */
    public void markCancelRequested() {
        statusLabel.setText("Status: Cancelling...");
        cancelButton.setDisable(true);
        touchUpdatedStamp();
    }

    /**
     * Lightweight proxy to the underlying stage visibility.
     */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /**
     * Hides stage immediately (used by owner controller during teardown).
     */
    public void close() {
        stage.hide();
    }

    /**
     * Renders ordered timeline rows into the text area.
     * The caret is moved to the end so newest updates stay visible.
     */
    private void renderActivities() {
        if (activities.isEmpty()) {
            activitiesArea.setText("No activity progress yet.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ActivityState> entry : activities.entrySet()) {
            String key = formatStepTitle(entry.getKey());
            ActivityState state = entry.getValue();
            sb.append("• ").append(key).append(" - ");
            if (state.done()) {
                sb.append("done");
            } else if (state.indeterminate()) {
                sb.append("running (indeterminate)");
            } else {
                sb.append(String.format(Locale.ROOT, "%.0f%%", state.percent()));
            }
            if (state.eta() != null && !state.eta().isBlank() && !"--:--".equals(state.eta())) {
                sb.append(" | ETA ").append(state.eta());
            }
            sb.append('\n');
        }
        activitiesArea.setText(sb.toString().trim());
        activitiesArea.positionCaret(activitiesArea.getLength());
    }

    /**
     * Ensures the previously active step is marked as done in the timeline.
     * Called when switching to a new step and when finishing the whole job.
     */
    private void markPreviousStepDone() {
        if (currentStep.isBlank()) {
            return;
        }
        ActivityState existing = activities.get(currentStep);
        if (existing == null) {
            activities.put(currentStep, new ActivityState(100.0, false, "0s", true));
            return;
        }
        if (!existing.done()) {
            activities.put(currentStep, new ActivityState(100.0, false, "0s", true));
        }
    }

    /**
     * Estimates remaining time for the current step from recent percent delta.
     *
     * The algorithm is intentionally conservative:
     * - it resets anchor when progress becomes non-monotonic
     * - it waits for both sufficient percent delta and elapsed time
     * - it returns "Estimating..." until estimate is stable enough
     */
    private String estimateStepEta(Double stepPct, boolean indeterminate) {
        if (indeterminate || stepPct == null) {
            stepAnchorMillis = -1L;
            stepAnchorPercent = -1.0;
            return "--:--";
        }

        double clamped = Math.max(0.0, Math.min(100.0, stepPct));
        if (clamped >= 100.0) {
            stepAnchorMillis = -1L;
            stepAnchorPercent = -1.0;
            return "0s";
        }

        long now = System.currentTimeMillis();
        if (stepAnchorMillis < 0L || stepAnchorPercent < 0.0 || clamped <= stepAnchorPercent) {
            stepAnchorMillis = now;
            stepAnchorPercent = clamped;
            return "Estimating...";
        }

        double percentDelta = clamped - stepAnchorPercent;
        long elapsedMillis = now - stepAnchorMillis;
        if (percentDelta < 0.5 || elapsedMillis < 3000L) {
            return "Estimating...";
        }

        double millisPerPercent = elapsedMillis / percentDelta;
        double remainingPercent = 100.0 - clamped;
        long remainingMillis = Math.round(Math.max(0.0, millisPerPercent * remainingPercent));

        stepAnchorMillis = now;
        stepAnchorPercent = clamped;
        return formatDurationShort(remainingMillis);
    }

    /**
     * Updates "Updated: HH:mm:ss" label so user can confirm window is alive.
     */
    private void touchUpdatedStamp() {
        updatedLabel.setText("Updated: " + LocalDateTime.now().format(TS_FMT));
    }

    /**
     * Normalizes nullable strings for display labels.
     */
    private static String normalizeText(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? "-" : normalized;
    }

    /**
     * Normalizes ETA text from backend to a safe fallback.
     */
    private static String normalizeEta(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "--:--";
        }
        return normalized;
    }

    /**
     * Converts technical step ids (e.g. "download_audio") to user-facing title case.
     */
    private static String formatStepTitle(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank() || "-".equals(normalized)) {
            return "-";
        }
        String[] parts = normalized.split("[_\\-\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.isEmpty() ? normalized : sb.toString();
    }

    /**
     * Formats ETA into compact human-readable form used by this window.
     */
    private static String formatDurationShort(long millis) {
        long totalSeconds = Math.max(1L, Math.round(millis / 1000.0));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        }
        return String.format(Locale.ROOT, "%ds", seconds);
    }
}
