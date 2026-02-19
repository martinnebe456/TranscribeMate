package com.transcribemate.v2.fx;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * Detached monitoring window polling backend metrics and rendering short rolling
 * CPU/RAM/GPU/VRAM trends plus GPU diagnostics.
 */
public final class SystemMonitorWindow {
    private static final int MAX_POINTS = 120;
    private static final DecimalFormat DF = new DecimalFormat("0.0");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BackendClient backendClient;
    private final Stage stage;
    private final NumberAxis xAxis;

    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> ramSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> gpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> vramSeries = new XYChart.Series<>();

    private final Label cpuLabel = new Label("CPU: n/a");
    private final Label ramLabel = new Label("RAM: n/a");
    private final Label gpuLabel = new Label("GPU: n/a");
    private final Label vramLabel = new Label("VRAM: n/a");
    private final Label gpuMemoryLabel = new Label("GPU Mem: n/a");
    private final Label gpuTempLabel = new Label("GPU Temp: n/a");
    private final Label gpuPowerLabel = new Label("GPU Power: n/a");
    private final Label gpuDriverModelLabel = new Label("Driver Model: n/a");
    private final Label gpuAppsLabel = new Label("GPU Apps: n/a");
    private final Label gpuWarningLabel = new Label();
    private final Label updatedLabel = new Label("Updated: -");
    private final VBox root;

    private final Timeline pollingTimeline;
    private int tick = 0;

    /**
     * Builds monitor UI and polling timer. Actual polling starts in {@link #show()}.
     */
    public SystemMonitorWindow(BackendClient backendClient) {
        this.backendClient = backendClient;
        this.stage = new Stage();

        xAxis = new NumberAxis(0, MAX_POINTS, 10);
        NumberAxis yAxis = new NumberAxis(0, 100, 10);
        xAxis.setForceZeroInRange(false);
        xAxis.setLabel("Samples");
        yAxis.setLabel("Usage (%)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);

        cpuSeries.setName("CPU %");
        ramSeries.setName("RAM %");
        gpuSeries.setName("GPU %");
        vramSeries.setName("VRAM %");

        chart.getData().add(cpuSeries);
        chart.getData().add(ramSeries);
        chart.getData().add(gpuSeries);
        chart.getData().add(vramSeries);

        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(12.0);
        statsGrid.setVgap(6.0);
        statsGrid.add(cpuLabel, 0, 0);
        statsGrid.add(ramLabel, 1, 0);
        statsGrid.add(gpuLabel, 2, 0);
        statsGrid.add(vramLabel, 3, 0);
        statsGrid.add(gpuMemoryLabel, 0, 1);
        statsGrid.add(gpuTempLabel, 1, 1);
        statsGrid.add(gpuPowerLabel, 2, 1);
        statsGrid.add(gpuDriverModelLabel, 3, 1);
        statsGrid.add(gpuAppsLabel, 0, 2, 4, 1);
        statsGrid.add(gpuWarningLabel, 0, 3, 4, 1);
        statsGrid.add(updatedLabel, 0, 4, 4, 1);
        gpuAppsLabel.setWrapText(true);
        gpuWarningLabel.setWrapText(true);
        gpuWarningLabel.getStyleClass().add("warning-label");
        gpuWarningLabel.setVisible(false);
        gpuWarningLabel.setManaged(false);

        root = new VBox(10.0, statsGrid, chart);
        root.setPadding(new Insets(12.0));
        root.getStyleClass().addAll("root", "card-pane", "theme-light");
        VBox.setVgrow(chart, Priority.ALWAYS);

        Scene scene = new Scene(root, 980, 580);
        scene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());

        pollingTimeline = new Timeline(new KeyFrame(Duration.seconds(2), event -> pollMetrics()));
        pollingTimeline.setCycleCount(Animation.INDEFINITE);

        stage.setTitle("System Monitor - CPU / RAM / GPU / VRAM");
        stage.setScene(scene);
        stage.setOnHidden(event -> pollingTimeline.stop());
    }

    /**
     * Applies one of known app theme classes to this window root.
     */
    public void applyThemeClass(String themeClass) {
        root.getStyleClass().removeAll("theme-light", "theme-dark", "theme-dracula");
        if (themeClass != null && !themeClass.isBlank()) {
            root.getStyleClass().add(themeClass);
        } else {
            root.getStyleClass().add("theme-light");
        }
    }

    /**
     * Shows window and ensures polling loop is active.
     */
    public void show() {
        stage.show();
        stage.toFront();
        if (pollingTimeline.getStatus() != Animation.Status.RUNNING) {
            pollingTimeline.play();
            pollMetrics();
        }
    }

    /**
     * Stops polling and hides window.
     */
    public void close() {
        pollingTimeline.stop();
        stage.hide();
    }

    /**
     * Requests latest system metrics from backend and routes update to FX thread.
     */
    private void pollMetrics() {
        if (backendClient == null) {
            return;
        }
        backendClient.sendRequest("get_system_metrics")
                .thenAccept(metrics -> Platform.runLater(() -> updateMetrics(metrics)))
                .exceptionally(ex -> {
                    AppFileLogger.logException("system_monitor.poll", ex);
                    Platform.runLater(() -> updatedLabel.setText("Updated: metrics error - " + ex.getMessage()));
                    return null;
                });
    }

    /**
     * Applies one metrics snapshot to charts and labels.
     */
    private void updateMetrics(JsonNode metrics) {
        double cpu = optionalDouble(metrics.path("cpu_percent"));
        double ram = optionalDouble(metrics.path("ram_percent"));
        double gpu = optionalDouble(metrics.path("gpu_percent"));
        double gpuMem = optionalDouble(metrics.path("gpu_memory_percent"));

        double vramUsed = optionalDouble(metrics.path("vram_used_gb"));
        double vramFree = optionalDouble(metrics.path("vram_free_gb"));
        double vramTotal = optionalDouble(metrics.path("vram_total_gb"));
        double gpuTemp = optionalDouble(metrics.path("gpu_temp_c"));
        double gpuPower = optionalDouble(metrics.path("gpu_power_w"));
        double vramPct = (Double.isNaN(vramUsed) || Double.isNaN(vramTotal) || vramTotal <= 0.0)
                ? Double.NaN
                : (vramUsed * 100.0 / vramTotal);

        addPoint(cpuSeries, tick, cpu);
        addPoint(ramSeries, tick, ram);
        addPoint(gpuSeries, tick, gpu);
        addPoint(vramSeries, tick, vramPct);

        tick += 1;
        if (tick > MAX_POINTS) {
            xAxis.setLowerBound(tick - MAX_POINTS);
            xAxis.setUpperBound(tick);
        }

        cpuLabel.setText("CPU: " + percentText(cpu));
        String ramAbs = (Double.isNaN(optionalDouble(metrics.path("ram_used_gb"))) || Double.isNaN(optionalDouble(metrics.path("ram_total_gb"))))
                ? ""
                : " (" + DF.format(optionalDouble(metrics.path("ram_used_gb"))) + " / " + DF.format(optionalDouble(metrics.path("ram_total_gb"))) + " GB)";
        ramLabel.setText("RAM: " + percentText(ram) + ramAbs);

        String gpuName = metrics.path("gpu_name").asText("");
        if (gpuName.isBlank()) {
            gpuLabel.setText("GPU: " + percentText(gpu));
        } else {
            gpuLabel.setText("GPU: " + percentText(gpu) + " (" + gpuName + ")");
        }

        String vramAbs = (Double.isNaN(vramUsed) || Double.isNaN(vramTotal))
                ? ""
                : " (" + DF.format(vramUsed) + " / " + DF.format(vramTotal) + " GB"
                + (Double.isNaN(vramFree) ? "" : ", free " + DF.format(vramFree) + " GB")
                + ")";
        vramLabel.setText("VRAM: " + percentText(vramPct) + vramAbs);
        gpuMemoryLabel.setText("GPU Mem: " + percentText(gpuMem));
        gpuTempLabel.setText("GPU Temp: " + metricText(gpuTemp, " C"));
        gpuPowerLabel.setText("GPU Power: " + metricText(gpuPower, " W"));

        String driverModel = metrics.path("nvidia_driver_model").asText("");
        gpuDriverModelLabel.setText("Driver Model: " + (driverModel.isBlank() ? "n/a" : driverModel));
        gpuAppsLabel.setText(formatGpuApps(metrics.path("gpu_compute_apps")));

        double computeAppsPct = computeAppsVramPercent(metrics.path("gpu_compute_apps"), vramTotal);
        if (!Double.isNaN(computeAppsPct) && computeAppsPct >= 80.0) {
            gpuWarningLabel.setText(
                    "GPU occupied by other processes: compute apps are using "
                            + DF.format(computeAppsPct)
                            + "% of VRAM."
            );
            gpuWarningLabel.setVisible(true);
            gpuWarningLabel.setManaged(true);
        } else {
            gpuWarningLabel.setText("");
            gpuWarningLabel.setVisible(false);
            gpuWarningLabel.setManaged(false);
        }

        updatedLabel.setText("Updated: " + LocalDateTime.now().format(TS_FMT));
    }

    /**
     * Appends a point to bounded history series (max {@link #MAX_POINTS} samples).
     */
    private static void addPoint(XYChart.Series<Number, Number> series, int x, double value) {
        double safeValue = Double.isNaN(value) ? 0.0 : Math.max(0.0, Math.min(100.0, value));
        series.getData().add(new XYChart.Data<>(x, safeValue));
        while (series.getData().size() > MAX_POINTS) {
            series.getData().remove(0);
        }
    }

    private static double optionalDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Double.NaN;
        }
        return node.asDouble(Double.NaN);
    }

    private static String percentText(double value) {
        return Double.isNaN(value) ? "n/a" : DF.format(value) + "%";
    }

    private static String metricText(double value, String suffix) {
        return Double.isNaN(value) ? "n/a" : DF.format(value) + suffix;
    }

    /**
     * Formats active GPU process summaries for quick triage.
     */
    private static String formatGpuApps(JsonNode appsNode) {
        if (appsNode == null || !appsNode.isArray() || appsNode.isEmpty()) {
            return "GPU Apps: none";
        }

        StringJoiner joiner = new StringJoiner(" | ", "GPU Apps: ", "");
        int shown = 0;
        for (JsonNode item : appsNode) {
            if (shown >= 3) {
                break;
            }

            String processName = item.path("process_name").asText("");
            int pid = item.path("pid").asInt(-1);
            double usedGb = optionalDouble(item.path("used_memory_gb"));

            StringBuilder chunk = new StringBuilder();
            chunk.append(processName.isBlank() ? "process" : processName);
            if (pid >= 0) {
                chunk.append(" [").append(pid).append("]");
            }
            if (!Double.isNaN(usedGb)) {
                chunk.append(" ").append(DF.format(usedGb)).append(" GB");
            }
            joiner.add(chunk.toString());
            shown += 1;
        }

        int remaining = appsNode.size() - shown;
        if (remaining > 0) {
            joiner.add("+" + remaining + " more");
        }
        return joiner.toString();
    }

    /**
     * Computes percentage of total VRAM currently consumed by compute processes.
     */
    private static double computeAppsVramPercent(JsonNode appsNode, double vramTotalGb) {
        if (appsNode == null || !appsNode.isArray() || appsNode.isEmpty() || Double.isNaN(vramTotalGb) || vramTotalGb <= 0.0) {
            return Double.NaN;
        }

        double usedByAppsGb = 0.0;
        for (JsonNode item : appsNode) {
            double usedGb = optionalDouble(item.path("used_memory_gb"));
            if (!Double.isNaN(usedGb) && usedGb > 0.0) {
                usedByAppsGb += usedGb;
            }
        }
        if (usedByAppsGb <= 0.0) {
            return Double.NaN;
        }
        return (usedByAppsGb * 100.0 / vramTotalGb);
    }
}
