package com.transcribemate.v2.fx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.ModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.registry.ModuleRegistry;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class MainController {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_ENTRIES = 5000;
    private static final int BOOTSTRAP_LOG_MAX_CHARS = 200000;
    private static final String APP_NAME = "TranscribeMate";
    private static final String RUNTIME_READY_MARKER_NAME = "runtime-ready.json";
    private static final String WINDOW_TITLE_SUFFIX = "JavaFX + Python Backend";
    private static final String PREF_THEME = "ui.theme";
    private static final String PREF_SIMPLE_MODE = "ui.simple_mode";
    private static final String PREF_AUTO_PREFLIGHT = "ui.auto_preflight";
    private static final String PREF_ACTIVE_MODULE = "ui.active_module";
    private static final String PREF_MODULE_PREFIX = "ui.module.";
    private static final String PREF_SOURCE_MODE = "ui.source_mode";
    private static final String PREF_OUTPUT_MODE = "ui.output_mode";
    private static final String PREF_MODEL = "ui.model";
    private static final String PREF_USE_GPU = "ui.use_gpu";
    private static final String PREF_DIARIZATION_ENABLED = "ui.diarization.enabled";
    private static final String PREF_DIARIZATION_BACKEND = "ui.diarization.backend";
    private static final String THEME_LIGHT = "Light";
    private static final String THEME_DARK = "Dark";
    private static final String THEME_DRACULA = "Dracula";
    private static final String MODULE_OFFLINE = "offline_transcribe";
    private static final String MODULE_YOUTUBE = "youtube_transcribe";
    private static final String MODULE_SPEAKER = "speaker_transcribe";
    private static final String MODULE_CONFERENCE = "conference_mode";
    private static final String MODULE_YOUTUBE_SUBS = "youtube_subtitles";
    private static final String MODULE_YOUTUBE_DUB = "youtube_dub";
    private static final Set<String> SUPPORTED_MODULES = ModuleRegistry.supportedModuleIds();
    private static final List<String> WHISPER_MODELS = List.of(
            "tiny",
            "tiny.en",
            "base",
            "base.en",
            "small",
            "small.en",
            "medium",
            "medium.en",
            "large",
            "large-v1",
            "large-v2",
            "large-v3",
            "distil-large-v3",
            "turbo"
    );

    private enum LogCategory {
        USER,
        TECHNICAL
    }

    private record LogEntry(LocalDateTime timestamp, String level, LogCategory category, String message) {
        String format() {
            return "[" + timestamp.format(TS_FMT) + "] [" + level + "] " + message;
        }
    }

    private record RuntimeBootstrapTarget(
            Path scriptPath,
            Path backendRoot,
            Path appDataDir,
            Path progressFilePath,
            Path logFilePath
    ) {
    }

    private record RuntimeBootstrapProgress(int percent, String message) {
    }

    @FXML
    private BorderPane rootPane;

    @FXML
    private CheckBox simpleModeBox;

    @FXML
    private CheckBox autoPreflightBox;

    @FXML
    private Button monitorButton;

    @FXML
    private Label appTitleLabel;

    @FXML
    private Label statusLabel;

    @FXML
    private Label stepLabel;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label etaLabel;

    @FXML
    private TabPane mainTabs;

    @FXML
    private Tab runTab;

    @FXML
    private Tab advancedTab;

    @FXML
    private Tab diarizationTab;

    @FXML
    private Tab logsTab;

    @FXML
    private Tab jobsTab;

    @FXML
    private Tab settingsTab;

    @FXML
    private VBox simpleHintCard;

    @FXML
    private ComboBox<String> sourceModeBox;

    @FXML
    private TextField localPathField;

    @FXML
    private TextField youtubeUrlField;

    @FXML
    private CheckBox playlistBox;

    @FXML
    private ComboBox<String> qualityBox;

    @FXML
    private TextField outputDirField;

    @FXML
    private ComboBox<String> outputModeBox;

    @FXML
    private TextField outputPrefixField;

    @FXML
    private CheckBox keepOriginalsBox;

    @FXML
    private ComboBox<String> modelField;

    @FXML
    private CheckBox autoModelBox;

    @FXML
    private CheckBox useGpuBox;

    @FXML
    private ComboBox<String> sourceLangBox;

    @FXML
    private ComboBox<String> summaryLangBox;

    @FXML
    private ComboBox<String> targetLangBox;

    @FXML
    private Spinner<Integer> batchSizeSpinner;

    @FXML
    private CheckBox cleanTextBox;

    @FXML
    private CheckBox exportMdBox;

    @FXML
    private CheckBox summaryPackBox;

    @FXML
    private CheckBox notifyDoneBox;

    @FXML
    private Spinner<Integer> splitMinutesSpinner;

    @FXML
    private ComboBox<String> subtitleModeBox;

    @FXML
    private TextField subtitleFontField;

    @FXML
    private Spinner<Integer> subtitleSizeSpinner;

    @FXML
    private ColorPicker subtitleColorPicker;

    @FXML
    private ColorPicker subtitleOutlineColorPicker;

    @FXML
    private Spinner<Integer> subtitleOutlineWidthSpinner;

    @FXML
    private CheckBox translateSubtitlesBox;

    @FXML
    private TextField speakerField;

    @FXML
    private TextField topicField;

    @FXML
    private TextField conferenceTitleField;

    @FXML
    private TextField conferenceDateField;

    @FXML
    private CheckBox diarizationEnabledBox;

    @FXML
    private ComboBox<String> diarizationBackendBox;

    @FXML
    private Spinner<Integer> diarizationMinSpinner;

    @FXML
    private Spinner<Integer> diarizationMaxSpinner;

    @FXML
    private CheckBox diarizationReviewBox;

    @FXML
    private CheckBox diarizationIncludeUnmappedBox;

    @FXML
    private CheckBox diarizationPrefixSrtBox;

    @FXML
    private CheckBox diarizationProfilePrefillBox;

    @FXML
    private CheckBox diarizationFailOnErrorBox;

    @FXML
    private TextField hfTokenField;

    @FXML
    private TableView<SpeakerProfileRow> speakerProfilesTable;

    @FXML
    private TableColumn<SpeakerProfileRow, String> speakerLabelColumn;

    @FXML
    private TableColumn<SpeakerProfileRow, String> speakerNameColumn;

    @FXML
    private TextArea profilePreviewArea;

    @FXML
    private TableView<ConferenceFileRow> conferenceFilesTable;

    @FXML
    private TableColumn<ConferenceFileRow, String> conferenceFilePathColumn;

    @FXML
    private TableColumn<ConferenceFileRow, String> conferenceSpeakerColumn;

    @FXML
    private TableColumn<ConferenceFileRow, String> conferenceDescriptionColumn;

    @FXML
    private TableColumn<ConferenceFileRow, String> conferenceDateColumn;

    @FXML
    private TextField logSearchField;

    @FXML
    private ComboBox<String> logLevelFilterBox;

    @FXML
    private CheckBox showUserLogsBox;

    @FXML
    private CheckBox showTechnicalLogsBox;

    @FXML
    private TextArea filteredLogArea;

    @FXML
    private TextArea userLogArea;

    @FXML
    private TextArea technicalLogArea;

    @FXML
    private TableView<JobRow> jobHistoryTable;

    @FXML
    private TableColumn<JobRow, String> jobIdColumn;

    @FXML
    private TableColumn<JobRow, String> jobStatusColumn;

    @FXML
    private TableColumn<JobRow, String> jobModeColumn;

    @FXML
    private TableColumn<JobRow, String> jobSourceColumn;

    @FXML
    private TableColumn<JobRow, String> jobCreatedColumn;

    @FXML
    private TextArea jobDetailArea;

    @FXML
    private Button startButton;

    @FXML
    private Button cancelButton;

    @FXML
    private Button preflightButton;

    @FXML
    private Button refreshModelsButton;

    @FXML
    private Button installRuntimeButton;

    @FXML
    private Button settingsRepairRuntimeButton;

    @FXML
    private Button openOutputButton;

    @FXML
    private Button openDataButton;

    @FXML
    private Button healthButton;

    @FXML
    private Button refreshJobsButton;

    @FXML
    private Button removeJobButton;

    @FXML
    private Button runModuleButton;

    @FXML
    private Button advancedModuleButton;

    @FXML
    private Button diarizationModuleButton;

    @FXML
    private Button logsModuleButton;

    @FXML
    private Button jobsModuleButton;

    @FXML
    private Button youtubeDubModuleButton;

    @FXML
    private Button settingsModuleButton;

    @FXML
    private ComboBox<String> themeBox;

    @FXML
    private ComboBox<String> settingsModuleBox;

    @FXML
    private Button settingsLoadModuleButton;

    @FXML
    private Button settingsResetModuleDefaultsButton;

    @FXML
    private Label moduleFlowTitleLabel;

    @FXML
    private Label moduleFlowLabel;

    @FXML
    private Button moduleFlowActionButton;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonPreferences preferences = new JsonPreferences(mapper, resolveRuntimeAppDataDir().resolve("config.json"));
    private final ObservableList<JobRow> jobRows = FXCollections.observableArrayList();
    private final Map<String, JobRow> jobsById = new LinkedHashMap<>();
    private final ObservableList<SpeakerProfileRow> speakerProfiles = FXCollections.observableArrayList();
    private final ObservableList<ConferenceFileRow> conferenceFiles = FXCollections.observableArrayList();
    private final List<LogEntry> allLogs = new ArrayList<>();
    private final Map<String, ModuleComponent> moduleComponents = ModuleRegistry.byId();
    private final Map<String, String> moduleLabelsToId = ModuleRegistry.labelToId();
    private final ModuleComponent.ModuleUiContext moduleUiContext = new ControllerModuleUiContext();

    private BackendClient backendClient;
    private String currentJobId;
    private String lastOutputDir;
    private String appDataDir;
    private String appVersion;
    private String currentTheme = "";
    private boolean themeInitializing;
    private boolean restoringPreferences;
    private boolean runtimeBootstrapRunning;
    private boolean settingsModuleSelectorSync;
    private String moduleFlowActionKey = "";
    private String activeModule = MODULE_OFFLINE;
    private long etaAnchorMillis = -1L;
    private double etaAnchorPercent = -1.0;

    private Timeline jobsRefreshTimeline;
    private SystemMonitorWindow monitorWindow;

    private final Consumer<JsonNode> eventListener = this::handleBackendEvent;

    @FXML
    private void initialize() {
        setupCombosAndDefaults();
        setupTables();
        setupFilters();
        setupModuleNavigation();
        setupThemeSelector();
        setupSettingsModuleSelector();

        sourceModeBox.valueProperty().addListener((obs, oldVal, newVal) -> updateSourceModeUi());
        translateSubtitlesBox.selectedProperty().addListener((obs, oldVal, newVal) -> updateTranslationUi());
        jobHistoryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> onJobSelectionChanged(newItem));

        statusLabel.setText("Ready");
        stepLabel.setText("-");
        progressBar.setProgress(0.0);
        resetEtaDisplay();
        setAppVersion(resolveLocalVersion());

        restoreUiPreferences();
        updateSourceModeUi();
        applyUiMode();
        activateModule(activeModule, false);
        registerPreferenceListeners();
        setRunning(false);
        refreshProfilePreview();
    }

    public void initBackend(BackendClient client) {
        this.backendClient = client;
        if (this.backendClient == null) {
            addTechnicalLog("WARN", "Backend is not connected.");
            return;
        }
        this.backendClient.addEventListener(eventListener);

        if (jobsRefreshTimeline == null) {
            jobsRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(8), event -> refreshJobsSilently()));
            jobsRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
            jobsRefreshTimeline.play();
        }

        backendClient.sendRequest("get_capabilities")
                .thenAccept(result -> Platform.runLater(() -> {
                    String backendVersion = trimToEmpty(result.path("version").asText(""));
                    if (!backendVersion.isBlank()) {
                        setAppVersion(backendVersion);
                    }
                    updateTargetsFromCapabilities(result.path("translation_targets"));
                    addUserLog(
                            "INFO",
                            backendVersion.isBlank()
                                    ? "Backend connected."
                                    : "Backend connected (version " + backendVersion + ")."
                    );
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("ERROR", "Backend capability check failed: " + ex.getMessage()));
                    return null;
                });

        backendClient.sendRequest("get_paths")
                .thenAccept(result -> Platform.runLater(() -> {
                    appDataDir = result.path("user_data_dir").asText("");
                    String cfgPathRaw = trimToEmpty(result.path("config_path").asText(""));
                    if (!cfgPathRaw.isBlank()) {
                        try {
                            boolean changed = preferences.setPath(Path.of(cfgPathRaw));
                            if (changed) {
                                restoreUiPreferences();
                                applyUiMode();
                                activateModule(activeModule, false, true);
                            }
                        } catch (Exception ignored) {
                            // Keep default config path fallback.
                        }
                    }
                    addUserLog("INFO", "App data: " + appDataDir);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("WARN", "Could not fetch app data path: " + ex.getMessage()));
                    return null;
                });

        refreshJobsSilently();
    }

    public void reportBackendStartupFailure(String errorMessage) {
        String details = trimToEmpty(errorMessage);
        statusLabel.setText("Backend offline");
        stepLabel.setText("runtime setup required");
        progressBar.setProgress(0.0);
        resetEtaDisplay();
        if (details.isBlank()) {
            addTechnicalLog("ERROR", "Backend process failed to start.");
        } else {
            addTechnicalLog("ERROR", "Backend process failed to start: " + details);
        }
        addUserLog("WARN", "Use 'Repair runtime' and restart the app.");
    }

    public void startMandatoryRuntimeSetupOnLaunch() {
        if (runtimeBootstrapRunning) {
            return;
        }

        RuntimeBootstrapTarget target = resolveRuntimeBootstrapTarget();
        if (target == null) {
            addTechnicalLog(
                    "ERROR",
                    "Mandatory online runtime setup is unavailable. Missing bootstrap_runtime.ps1 or backend bundle."
            );
            return;
        }

        statusLabel.setText("Runtime setup required");
        stepLabel.setText("first launch");
        progressBar.setProgress(0.0);
        resetEtaDisplay();
        addUserLog("WARN", "First launch requires online runtime setup before the app can be used.");

        ButtonType startSetupButton = new ButtonType("Start setup", ButtonBar.ButtonData.OK_DONE);
        ButtonType notNowButton = new ButtonType("Not now", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert firstLaunchDialog = new Alert(Alert.AlertType.CONFIRMATION);
        firstLaunchDialog.setTitle("First Launch Setup Required");
        firstLaunchDialog.setHeaderText("Online runtime setup is required");
        firstLaunchDialog.setContentText(
                "This is the first launch of TranscribeMate.\n\n"
                        + "The application now needs to download and install:\n"
                        + "- Embedded Python runtime\n"
                        + "- Backend dependencies (including AI packages)\n"
                        + "- Default AI models\n"
                        + "- FFmpeg tools\n\n"
                        + "Without this step, TranscribeMate will not work correctly.\n\n"
                        + "Start online setup now?"
        );
        firstLaunchDialog.getButtonTypes().setAll(startSetupButton, notNowButton);
        Stage owner = getStage();
        if (owner != null) {
            firstLaunchDialog.initOwner(owner);
        }

        Optional<ButtonType> choice = firstLaunchDialog.showAndWait();
        if (choice.isPresent() && choice.get() == startSetupButton) {
            showRuntimeBootstrapWindow(target, true, false);
            return;
        }

        addUserLog("WARN", "Online runtime setup was not started. The app will not function correctly until setup is completed.");
        addUserLog("INFO", "Use the 'Repair runtime' button to continue setup when ready.");
    }

    public void installShortcuts(Scene scene) {
        if (scene == null) {
            return;
        }
        updateWindowTitle();

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN),
                this::runStartShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                this::runCancelShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN),
                this::runPreflightShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN),
                this::runMonitorShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                this::runRefreshModelShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN),
                this::runClearLogsShortcut
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(runTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(advancedTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(diarizationTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(logsTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT5, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(jobsTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT6, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(settingsTab))
        );
    }

    public void dispose() {
        saveActiveModuleState();
        if (jobsRefreshTimeline != null) {
            jobsRefreshTimeline.stop();
            jobsRefreshTimeline = null;
        }
        if (monitorWindow != null) {
            monitorWindow.close();
            monitorWindow = null;
        }
        if (backendClient != null) {
            backendClient.removeEventListener(eventListener);
        }
    }

    @FXML
    private void onToggleSimpleMode() {
        applyUiMode();
    }

    @FXML
    private void onModuleOffline() {
        activateModule(MODULE_OFFLINE, true);
    }

    @FXML
    private void onModuleYoutube() {
        activateModule(MODULE_YOUTUBE, true);
    }

    @FXML
    private void onModuleSpeaker() {
        activateModule(MODULE_SPEAKER, true);
    }

    @FXML
    private void onModuleConference() {
        activateModule(MODULE_CONFERENCE, true);
    }

    @FXML
    private void onModuleYoutubeSubtitles() {
        activateModule(MODULE_YOUTUBE_SUBS, true);
    }

    @FXML
    private void onModuleYoutubeDub() {
        activateModule(MODULE_YOUTUBE_DUB, true);
    }

    @FXML
    private void onModuleSettings() {
        selectModule(settingsTab);
    }

    @FXML
    private void onThemeChanged() {
        if (themeInitializing) {
            return;
        }
        applyTheme(themeBox.getValue(), true);
    }

    @FXML
    private void onModuleFlowAction() {
        switch (moduleFlowActionKey) {
            case "run_preflight" -> {
                if (!preflightButton.isDisabled()) {
                    onRunPreflight();
                }
            }
            case "open_run" -> selectModule(runTab);
            case "open_advanced" -> selectModule(advancedTab);
            case "open_diarization" -> selectModule(diarizationTab);
            case "open_monitor" -> onOpenMonitor();
            default -> {
                // No action mapped.
            }
        }
    }

    @FXML
    private void onBrowseLocalPath() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose media file");
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            localPathField.setText(file.getAbsolutePath());
            return;
        }

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Choose media folder");
        File folder = directoryChooser.showDialog(stage);
        if (folder != null) {
            localPathField.setText(folder.getAbsolutePath());
        }
    }

    @FXML
    private void onBrowseOutputDir() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output folder");

        String current = outputDirField.getText().trim();
        if (!current.isBlank()) {
            File dir = new File(current);
            if (dir.exists() && dir.isDirectory()) {
                chooser.setInitialDirectory(dir);
            }
        }

        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            outputDirField.setText(chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onStart() {
        if (runtimeBootstrapRunning) {
            addUserLog("WARN", "Online runtime setup is in progress. Wait until it completes.");
            return;
        }
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }

        saveActiveModuleState();
        String validationError = validateInputs();
        if (validationError != null) {
            addUserLog("ERROR", validationError);
            return;
        }

        ObjectNode params = buildPipelineParams();
        setRunning(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        stepLabel.setText("prepare");
        statusLabel.setText(autoPreflightBox.isSelected() ? "Preflight" : "Starting");
        resetEtaDisplay();

        CompletableFuture<Boolean> preflightFuture = autoPreflightBox.isSelected()
                ? runPreflightAsync(params, true)
                : CompletableFuture.completedFuture(true);

        preflightFuture
                .thenCompose(ok -> {
                    if (!ok) {
                        CompletableFuture<JsonNode> failed = new CompletableFuture<>();
                        failed.completeExceptionally(new IllegalStateException("Preflight failed. Resolve errors and retry."));
                        return failed;
                    }
                    Platform.runLater(() -> {
                        statusLabel.setText("Starting");
                        stepLabel.setText("run_pipeline");
                    });
                    return backendClient.sendRequest("run_pipeline", params);
                })
                .thenAccept(result -> Platform.runLater(() -> {
                    currentJobId = result.path("job_id").asText("");
                    statusLabel.setText("Running");
                    addUserLog("INFO", "Job started: " + shortJobId(currentJobId));
                    upsertJobRow(currentJobId, "running", moduleLabel(activeModule), sourceModeBox.getValue(), nowStamp());
                    refreshJobsSilently();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setRunning(false);
                        statusLabel.setText("Error");
                        stepLabel.setText("-");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                        addTechnicalLog("ERROR", "Failed to start job: " + rootMessage(ex));
                    });
                    return null;
                });
    }

    @FXML
    private void onRunPreflight() {
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }

        saveActiveModuleState();
        String validationError = validateInputs();
        if (validationError != null) {
            addUserLog("ERROR", validationError);
            return;
        }

        ObjectNode params = buildPipelineParams();
        statusLabel.setText("Preflight");
        stepLabel.setText("preflight_check");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        resetEtaDisplay();

        runPreflightAsync(params, true)
                .thenAccept(ok -> Platform.runLater(() -> {
                    statusLabel.setText(ok ? "Preflight OK" : "Preflight issues");
                    stepLabel.setText("-");
                    progressBar.setProgress(0.0);
                    resetEtaDisplay();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("Preflight error");
                        stepLabel.setText("-");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                        addTechnicalLog("ERROR", "Preflight failed: " + rootMessage(ex));
                    });
                    return null;
                });
    }

    @FXML
    private void onCancel() {
        if (backendClient == null || currentJobId == null || currentJobId.isBlank()) {
            return;
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("job_id", currentJobId);

        backendClient.sendRequest("cancel_job", params)
                .thenAccept(result -> Platform.runLater(() -> addUserLog("INFO", "Cancel requested for " + shortJobId(currentJobId))))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("ERROR", "Cancel failed: " + rootMessage(ex)));
                    return null;
                });
    }

    @FXML
    private void onRefreshModels() {
        if (backendClient == null) {
            return;
        }

        refreshModelsButton.setDisable(true);
        backendClient.sendRequest("refresh_model_cache")
                .thenAccept(result -> Platform.runLater(() -> {
                    addUserLog("INFO", "Model cache refresh completed.");
                    JsonNode messages = result.path("messages");
                    if (messages.isArray()) {
                        for (JsonNode node : messages) {
                            addUserLog(detectLevel(node.asText("")), node.asText(""));
                        }
                    }
                    refreshModelsButton.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        addTechnicalLog("ERROR", "Model cache refresh failed: " + rootMessage(ex));
                        refreshModelsButton.setDisable(false);
                    });
                    return null;
                });
    }

    @FXML
    private void onRepairRuntime() {
        if (runtimeBootstrapRunning) {
            addUserLog("WARN", "Runtime repair is already running.");
            return;
        }

        RuntimeBootstrapTarget target = resolveRuntimeBootstrapTarget();
        if (target == null) {
            addTechnicalLog(
                    "ERROR",
                    "Runtime repair is unavailable. Missing bootstrap_runtime.ps1 or backend bundle."
            );
            return;
        }

        ButtonType startRepairButton = new ButtonType("Start repair", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert repairDialog = new Alert(Alert.AlertType.CONFIRMATION);
        repairDialog.setTitle("Repair Runtime");
        repairDialog.setHeaderText("Reinstall managed runtime components?");
        repairDialog.setContentText(
                "This will re-run runtime bootstrap and may re-download dependencies, models and FFmpeg.\n\n"
                        + "Use this when backend runtime is broken or CUDA/AI dependencies are inconsistent.\n\n"
                        + "A restart is recommended after successful repair.\n\n"
                        + "Continue with runtime repair?"
        );
        repairDialog.getButtonTypes().setAll(startRepairButton, cancelButtonType);
        Stage owner = getStage();
        if (owner != null) {
            repairDialog.initOwner(owner);
        }

        Optional<ButtonType> choice = repairDialog.showAndWait();
        if (choice.isEmpty() || choice.get() != startRepairButton) {
            addUserLog("INFO", "Runtime repair cancelled.");
            return;
        }

        showRuntimeBootstrapWindow(target, false, true);
    }

    @FXML
    private void onInstallOnlineRuntime() {
        onRepairRuntime();
    }

    @FXML
    private void onOpenOutput() {
        String path = (lastOutputDir != null && !lastOutputDir.isBlank()) ? lastOutputDir : outputDirField.getText().trim();
        if (path.isBlank()) {
            addUserLog("WARN", "Output path is empty.");
            return;
        }
        openPath(path);
    }

    @FXML
    private void onOpenData() {
        if (appDataDir != null && !appDataDir.isBlank()) {
            openPath(appDataDir);
            return;
        }

        if (backendClient == null) {
            addTechnicalLog("WARN", "Backend not connected.");
            return;
        }

        backendClient.sendRequest("get_paths")
                .thenAccept(result -> Platform.runLater(() -> {
                    appDataDir = result.path("user_data_dir").asText("");
                    if (appDataDir.isBlank()) {
                        addTechnicalLog("WARN", "App data directory is unknown.");
                        return;
                    }
                    openPath(appDataDir);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("ERROR", "Could not fetch app data path: " + rootMessage(ex)));
                    return null;
                });
    }

    @FXML
    private void onHealth() {
        if (backendClient == null) {
            return;
        }

        healthButton.setDisable(true);
        backendClient.sendRequest("health")
                .thenAccept(result -> Platform.runLater(() -> {
                    String gpuName = result.path("nvidia_gpu_name").asText("");
                    String driverModel = result.path("nvidia_driver_model").asText("");
                    String cudaRuntime = result.path("nvidia_cuda_runtime_version").asText("");
                    String line = "Health: torch_ok=" + result.path("torch_ok").asBoolean(false)
                            + ", torch_version=" + result.path("torch_version").asText("")
                            + ", cuda=" + result.path("torch_cuda").asBoolean(false)
                            + ", gpu=" + (gpuName.isBlank() ? "n/a" : gpuName)
                            + ", driver_model=" + (driverModel.isBlank() ? "n/a" : driverModel)
                            + ", cuda_runtime=" + (cudaRuntime.isBlank() ? "n/a" : cudaRuntime)
                            + ", active_jobs=" + result.path("active_jobs").asInt(0);
                    addUserLog("INFO", line);
                    healthButton.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        addTechnicalLog("ERROR", "Health check failed: " + rootMessage(ex));
                        healthButton.setDisable(false);
                    });
                    return null;
                });
    }

    @FXML
    private void onOpenMonitor() {
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }

        if (monitorWindow == null) {
            monitorWindow = new SystemMonitorWindow(backendClient);
        }
        monitorWindow.applyThemeClass(themeClassFromName(currentTheme));
        monitorWindow.show();
    }

    @FXML
    private void onRefreshJobs() {
        refreshJobs(true);
    }

    @FXML
    private void onRemoveSelectedJob() {
        if (backendClient == null) {
            return;
        }

        JobRow selected = jobHistoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select a job first.");
            return;
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("job_id", selected.getJobId());

        backendClient.sendRequest("remove_job", params)
                .thenAccept(result -> Platform.runLater(() -> {
                    jobsById.remove(selected.getJobId());
                    rebuildJobTable();
                    addUserLog("INFO", "Removed job " + shortJobId(selected.getJobId()));
                    if (Objects.equals(currentJobId, selected.getJobId())) {
                        currentJobId = null;
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("ERROR", "Remove failed: " + rootMessage(ex)));
                    return null;
                });
    }

    @FXML
    private void onAddSpeakerProfile() {
        speakerProfiles.add(new SpeakerProfileRow("SPEAKER_00", ""));
        refreshProfilePreview();
    }

    @FXML
    private void onRemoveSpeakerProfile() {
        SpeakerProfileRow selected = speakerProfilesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        speakerProfiles.remove(selected);
        refreshProfilePreview();
    }

    @FXML
    private void onImportSpeakerProfiles() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import speaker profiles");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Profiles", "*.json", "*.txt", "*.cfg"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }

        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Map<String, String> parsed = parseProfilesFromText(content, file.getName().toLowerCase(Locale.ROOT).endsWith(".json"));
            speakerProfiles.clear();
            parsed.forEach((label, name) -> speakerProfiles.add(new SpeakerProfileRow(label, name)));
            addUserLog("INFO", "Imported " + parsed.size() + " speaker profiles.");
            refreshProfilePreview();
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Import failed: " + ex.getMessage());
        }
    }

    @FXML
    private void onExportSpeakerProfiles() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export speaker profiles");
        chooser.setInitialFileName("speaker_profiles.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }

        ObjectNode root = mapper.createObjectNode();
        collectSpeakerProfiles().forEach(root::put);

        try {
            Files.writeString(file.toPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            addUserLog("INFO", "Exported speaker profiles to: " + file.getAbsolutePath());
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Export failed: " + ex.getMessage());
        }
    }

    @FXML
    private void onPreviewSpeakerProfiles() {
        refreshProfilePreview();
        selectModule(diarizationTab);
    }

    @FXML
    private void onAddConferenceFile() {
        conferenceFiles.add(new ConferenceFileRow("", "", "", ""));
    }

    @FXML
    private void onRemoveConferenceFile() {
        ConferenceFileRow selected = conferenceFilesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        conferenceFiles.remove(selected);
    }

    @FXML
    private void onSyncConferenceFiles() {
        String sourceMode = safeValue(sourceModeBox);
        if (!"local".equals(sourceMode)) {
            addUserLog("WARN", "Conference file sync requires local source mode.");
            return;
        }

        String localPath = trimToEmpty(localPathField.getText());
        if (localPath.isBlank()) {
            addUserLog("WARN", "Select local file/folder first.");
            return;
        }

        Path source = Path.of(localPath).toAbsolutePath();
        if (!Files.exists(source)) {
            addUserLog("WARN", "Source path does not exist: " + source);
            return;
        }

        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(source)) {
            if (isMediaFile(source)) {
                files.add(source);
            }
        } else {
            try (var stream = Files.list(source)) {
                stream
                        .filter(Files::isRegularFile)
                        .filter(MainController::isMediaFile)
                        .sorted()
                        .forEach(files::add);
            } catch (Exception ex) {
                addTechnicalLog("ERROR", "Cannot scan source directory: " + ex.getMessage());
                return;
            }
        }

        if (files.isEmpty()) {
            addUserLog("WARN", "No files found in source.");
            return;
        }

        Map<String, ConferenceFileRow> existing = new LinkedHashMap<>();
        for (ConferenceFileRow row : conferenceFiles) {
            String key = trimToEmpty(row.getFilePath());
            if (!key.isBlank()) {
                existing.put(key, row);
            }
        }

        List<ConferenceFileRow> refreshed = new ArrayList<>();
        for (Path file : files) {
            String key = file.toString();
            ConferenceFileRow row = existing.getOrDefault(key, new ConferenceFileRow(key, "", "", ""));
            if (trimToEmpty(row.getFilePath()).isBlank()) {
                row.setFilePath(key);
            }
            refreshed.add(row);
        }

        conferenceFiles.setAll(refreshed);
        addUserLog("INFO", "Conference files synchronized: " + refreshed.size());
    }

    @FXML
    private void onClearLogs() {
        allLogs.clear();
        userLogArea.clear();
        technicalLogArea.clear();
        filteredLogArea.clear();
        addUserLog("INFO", "Logs cleared.");
    }

    private void setupCombosAndDefaults() {
        sourceModeBox.setItems(FXCollections.observableArrayList("local", "youtube"));
        sourceModeBox.getSelectionModel().select("local");

        qualityBox.setItems(FXCollections.observableArrayList("best", "1080p", "720p", "480p", "360p"));
        qualityBox.getSelectionModel().select("best");

        outputModeBox.setItems(FXCollections.observableArrayList("txt_only", "conference", "srt_only", "video_subs", "video_dub"));
        outputModeBox.getSelectionModel().select("txt_only");

        sourceLangBox.setItems(FXCollections.observableArrayList(
                "auto", "en", "cs", "sk", "de", "pl", "fr", "es", "it", "ru", "uk", "pt", "ja", "ko", "zh"
        ));
        sourceLangBox.getSelectionModel().select("auto");

        summaryLangBox.setItems(FXCollections.observableArrayList(
                "auto", "en", "cs", "sk", "de", "pl", "fr", "es", "it", "ru", "uk", "pt", "ja", "ko", "zh"
        ));
        summaryLangBox.getSelectionModel().select("auto");

        targetLangBox.setItems(FXCollections.observableArrayList(
                "en->cs", "en->sk", "en->de", "en->pl", "en->fr", "en->es", "en->it", "en->ru", "en->uk", "en->pt"
        ));
        targetLangBox.getSelectionModel().select("en->cs");

        subtitleModeBox.setItems(FXCollections.observableArrayList("soft", "hard"));
        subtitleModeBox.getSelectionModel().select("soft");

        diarizationBackendBox.setItems(FXCollections.observableArrayList("stable_local", "advanced_pyannote"));
        diarizationBackendBox.getSelectionModel().select("stable_local");

        modelField.setItems(FXCollections.observableArrayList(WHISPER_MODELS));
        selectModel("large-v3");

        batchSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 128, 16));
        splitMinutesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 720, 0));
        subtitleSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 96, 24));
        subtitleOutlineWidthSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 12, 2));
        diarizationMinSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, 0));
        diarizationMaxSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, 0));

        autoModelBox.setSelected(false);
        useGpuBox.setSelected(true);
        cleanTextBox.setSelected(false);
        exportMdBox.setSelected(false);
        summaryPackBox.setSelected(false);
        notifyDoneBox.setSelected(true);
        keepOriginalsBox.setSelected(false);
        translateSubtitlesBox.setSelected(true);

        diarizationEnabledBox.setSelected(false);
        diarizationReviewBox.setSelected(false);
        diarizationIncludeUnmappedBox.setSelected(true);
        diarizationPrefixSrtBox.setSelected(true);
        diarizationProfilePrefillBox.setSelected(true);
        diarizationFailOnErrorBox.setSelected(false);

        subtitleFontField.setText("Arial");
        outputDirField.setText(Path.of(System.getProperty("user.home"), "Downloads").toString());
        conferenceTitleField.setText("");
        conferenceDateField.setText("");

        subtitleColorPicker.setValue(javafx.scene.paint.Color.WHITE);
        subtitleOutlineColorPicker.setValue(javafx.scene.paint.Color.BLACK);
    }

    private void setupTables() {
        speakerProfilesTable.setItems(speakerProfiles);
        speakerProfilesTable.setEditable(true);

        speakerLabelColumn.setCellValueFactory(new PropertyValueFactory<>("label"));
        speakerLabelColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        speakerLabelColumn.setOnEditCommit(event -> {
            SpeakerProfileRow row = event.getRowValue();
            row.setLabel(normalizeSpeakerLabel(event.getNewValue()));
            refreshProfilePreview();
        });

        speakerNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        speakerNameColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        speakerNameColumn.setOnEditCommit(event -> {
            SpeakerProfileRow row = event.getRowValue();
            row.setName(trimToEmpty(event.getNewValue()));
            refreshProfilePreview();
        });

        conferenceFilesTable.setItems(conferenceFiles);
        conferenceFilesTable.setEditable(true);

        conferenceFilePathColumn.setCellValueFactory(new PropertyValueFactory<>("filePath"));
        conferenceFilePathColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        conferenceFilePathColumn.setOnEditCommit(event -> {
            ConferenceFileRow row = event.getRowValue();
            row.setFilePath(trimToEmpty(event.getNewValue()));
        });

        conferenceSpeakerColumn.setCellValueFactory(new PropertyValueFactory<>("speaker"));
        conferenceSpeakerColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        conferenceSpeakerColumn.setOnEditCommit(event -> {
            ConferenceFileRow row = event.getRowValue();
            row.setSpeaker(trimToEmpty(event.getNewValue()));
        });

        conferenceDescriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        conferenceDescriptionColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        conferenceDescriptionColumn.setOnEditCommit(event -> {
            ConferenceFileRow row = event.getRowValue();
            row.setDescription(trimToEmpty(event.getNewValue()));
        });

        conferenceDateColumn.setCellValueFactory(new PropertyValueFactory<>("lectureDate"));
        conferenceDateColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        conferenceDateColumn.setOnEditCommit(event -> {
            ConferenceFileRow row = event.getRowValue();
            row.setLectureDate(trimToEmpty(event.getNewValue()));
        });

        jobHistoryTable.setItems(jobRows);
        jobIdColumn.setCellValueFactory(new PropertyValueFactory<>("jobId"));
        jobStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        jobModeColumn.setCellValueFactory(new PropertyValueFactory<>("mode"));
        jobSourceColumn.setCellValueFactory(new PropertyValueFactory<>("source"));
        jobCreatedColumn.setCellValueFactory(new PropertyValueFactory<>("created"));
    }

    private void setupFilters() {
        logLevelFilterBox.setItems(FXCollections.observableArrayList("ALL", "INFO", "WARN", "ERROR", "SUCCESS", "DEBUG"));
        logLevelFilterBox.getSelectionModel().select("ALL");

        logSearchField.textProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        logLevelFilterBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        showUserLogsBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        showTechnicalLogsBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
    }

    private void setupModuleNavigation() {
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> syncModuleButtons());
    }

    private void setupThemeSelector() {
        themeInitializing = true;
        themeBox.setItems(FXCollections.observableArrayList(THEME_LIGHT, THEME_DARK, THEME_DRACULA));
        String storedTheme = normalizeThemeName(preferences.get(PREF_THEME, THEME_LIGHT));
        themeBox.getSelectionModel().select(storedTheme);
        applyTheme(storedTheme, false);
        themeInitializing = false;
    }

    private void setupSettingsModuleSelector() {
        if (settingsModuleBox == null) {
            return;
        }

        settingsModuleSelectorSync = true;
        settingsModuleBox.setItems(FXCollections.observableArrayList(moduleLabelsToId.keySet()));
        String label = moduleLabel(activeModule);
        if (settingsModuleBox.getItems().contains(label)) {
            settingsModuleBox.getSelectionModel().select(label);
        } else if (!settingsModuleBox.getItems().isEmpty()) {
            settingsModuleBox.getSelectionModel().selectFirst();
        }
        settingsModuleSelectorSync = false;
    }

    @FXML
    private void onSettingsModuleChanged() {
        if (settingsModuleSelectorSync) {
            return;
        }
        String selectedLabel = safeValue(settingsModuleBox);
        String moduleId = moduleLabelsToId.get(selectedLabel);
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        activateModule(moduleId, false, true);
    }

    @FXML
    private void onLoadSettingsModule() {
        onSettingsModuleChanged();
        addUserLog("INFO", "Loaded settings scope: " + moduleLabel(activeModule));
    }

    @FXML
    private void onResetSettingsModuleDefaults() {
        String selectedLabel = safeValue(settingsModuleBox);
        String moduleId = moduleLabelsToId.get(selectedLabel);
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        activateModule(moduleId, false, true);
        applyModuleDefaults(activeModule);
        enforceModuleConstraints(activeModule, true);
        saveActiveModuleState();
        addUserLog("INFO", "Module defaults restored: " + moduleLabel(activeModule));
    }

    private void restoreUiPreferences() {
        restoringPreferences = true;
        try {
            String storedTheme = normalizeThemeName(preferences.get(PREF_THEME, THEME_LIGHT));
            themeInitializing = true;
            themeBox.getSelectionModel().select(storedTheme);
            themeInitializing = false;
            applyTheme(storedTheme, false);

            simpleModeBox.setSelected(preferences.getBoolean(PREF_SIMPLE_MODE, true));
            autoPreflightBox.setSelected(preferences.getBoolean(PREF_AUTO_PREFLIGHT, true));
            activeModule = normalizeModuleId(preferences.get(PREF_ACTIVE_MODULE, MODULE_OFFLINE));

            String savedSourceMode = trimToEmpty(preferences.get(PREF_SOURCE_MODE, safeValue(sourceModeBox)));
            if (!savedSourceMode.isBlank() && sourceModeBox.getItems().contains(savedSourceMode)) {
                sourceModeBox.getSelectionModel().select(savedSourceMode);
            }

            String savedOutputMode = trimToEmpty(preferences.get(PREF_OUTPUT_MODE, safeValue(outputModeBox)));
            if (!savedOutputMode.isBlank() && outputModeBox.getItems().contains(savedOutputMode)) {
                outputModeBox.getSelectionModel().select(savedOutputMode);
            }

            String savedModel = trimToEmpty(preferences.get(PREF_MODEL, safeValue(modelField)));
            if (!savedModel.isBlank()) {
                selectModel(savedModel);
            }

            useGpuBox.setSelected(preferences.getBoolean(PREF_USE_GPU, useGpuBox.isSelected()));
            diarizationEnabledBox.setSelected(preferences.getBoolean(PREF_DIARIZATION_ENABLED, diarizationEnabledBox.isSelected()));

            String savedDiarizationBackend = trimToEmpty(preferences.get(PREF_DIARIZATION_BACKEND, safeValue(diarizationBackendBox)));
            if (!savedDiarizationBackend.isBlank() && diarizationBackendBox.getItems().contains(savedDiarizationBackend)) {
                diarizationBackendBox.getSelectionModel().select(savedDiarizationBackend);
            }
        } finally {
            restoringPreferences = false;
        }
    }

    private void registerPreferenceListeners() {
        simpleModeBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences) {
                preferences.putBoolean(PREF_SIMPLE_MODE, newVal);
                preferences.flush();
            }
        });
        autoPreflightBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences) {
                preferences.putBoolean(PREF_AUTO_PREFLIGHT, newVal);
                preferences.flush();
            }
        });
        sourceModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences && newVal != null) {
                preferences.put(PREF_SOURCE_MODE, newVal);
                preferences.flush();
            }
        });
        outputModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences && newVal != null) {
                preferences.put(PREF_OUTPUT_MODE, newVal);
                preferences.flush();
            }
        });
        modelField.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences && newVal != null) {
                preferences.put(PREF_MODEL, trimToEmpty(newVal));
                preferences.flush();
            }
        });
        useGpuBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences) {
                preferences.putBoolean(PREF_USE_GPU, newVal);
                preferences.flush();
            }
        });
        diarizationEnabledBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences) {
                preferences.putBoolean(PREF_DIARIZATION_ENABLED, newVal);
                preferences.flush();
            }
        });
        diarizationBackendBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences && newVal != null) {
                preferences.put(PREF_DIARIZATION_BACKEND, newVal);
                preferences.flush();
            }
        });
    }

    private void selectModule(Tab tab) {
        if (tab == null) {
            return;
        }
        if (tab.isDisable()) {
            addUserLog("WARN", "Module is unavailable in Simple mode.");
            return;
        }
        mainTabs.getSelectionModel().select(tab);
    }

    private void syncModuleButtons() {
        setModuleButtonActive(runModuleButton, MODULE_OFFLINE.equals(activeModule));
        setModuleButtonActive(advancedModuleButton, MODULE_YOUTUBE.equals(activeModule));
        setModuleButtonActive(diarizationModuleButton, MODULE_SPEAKER.equals(activeModule));
        setModuleButtonActive(logsModuleButton, MODULE_CONFERENCE.equals(activeModule));
        setModuleButtonActive(jobsModuleButton, MODULE_YOUTUBE_SUBS.equals(activeModule));
        setModuleButtonActive(youtubeDubModuleButton, MODULE_YOUTUBE_DUB.equals(activeModule));
        setModuleButtonActive(settingsModuleButton, mainTabs.getSelectionModel().getSelectedItem() == settingsTab);
        updateModuleFlow();
    }

    private void setModuleButtonActive(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.getStyleClass().remove("module-btn-active");
        if (active) {
            button.getStyleClass().add("module-btn-active");
        }
    }

    private void updateModuleFlow() {
        Tab selectedTab = mainTabs.getSelectionModel().getSelectedItem();
        if (selectedTab == settingsTab) {
            configureModuleFlow(
                    "Flow: Settings",
                    "1) Configure module-specific model/language defaults.\n2) Set theme and conference metadata defaults.\n3) Use monitor for runtime diagnostics.",
                    "open_monitor",
                    "Open Monitor"
            );
            return;
        }

        ModuleFlowSpec flowSpec = getActiveModuleComponent().flowSpec();
        configureModuleFlow(
                flowSpec.title(),
                flowSpec.details(),
                flowSpec.actionKey(),
                flowSpec.actionText()
        );
    }

    private void configureModuleFlow(String title, String details, String actionKey, String actionText) {
        moduleFlowTitleLabel.setText(title);
        moduleFlowLabel.setText(details);
        moduleFlowActionKey = actionKey == null ? "" : actionKey;
        moduleFlowActionButton.setText(actionText == null ? "Action" : actionText);
        moduleFlowActionButton.setDisable(startButton.isDisabled());
    }

    private void activateModule(String moduleId, boolean logChange) {
        activateModule(moduleId, logChange, false);
    }

    private void activateModule(String moduleId, boolean logChange, boolean keepCurrentTab) {
        String normalized = normalizeModuleId(moduleId);
        Tab selectedTab = mainTabs.getSelectionModel().getSelectedItem();
        if (!Objects.equals(activeModule, normalized)) {
            saveActiveModuleState();
            activeModule = normalized;
            preferences.put(PREF_ACTIVE_MODULE, activeModule);
            preferences.flush();
        }

        boolean loaded = loadModuleState(activeModule);
        if (!loaded) {
            applyModuleDefaults(activeModule);
        }
        enforceModuleConstraints(activeModule, keepCurrentTab);
        if (keepCurrentTab && selectedTab != null) {
            selectModule(selectedTab);
        }
        syncModuleButtons();
        syncSettingsModuleSelector();

        if (logChange) {
            addUserLog("INFO", "Module selected: " + moduleLabel(activeModule));
        }
    }

    private void applyModuleDefaults(String moduleId) {
        ModuleComponent component = moduleComponents.get(normalizeModuleId(moduleId));
        if (component == null) {
            component = moduleComponents.get(MODULE_OFFLINE);
        }
        if (component != null) {
            component.applyDefaults(moduleUiContext);
        }
    }

    private void enforceModuleConstraints(String moduleId, boolean keepCurrentTab) {
        ModuleComponent component = moduleComponents.get(normalizeModuleId(moduleId));
        if (component == null) {
            component = moduleComponents.get(MODULE_OFFLINE);
        }
        if (component != null) {
            component.enforceConstraints(moduleUiContext, keepCurrentTab);
        }

        sourceModeBox.setDisable(true);
        outputModeBox.setDisable(true);
        updateSourceModeUi();
        updateTranslationUi();
    }

    private ModuleComponent getActiveModuleComponent() {
        ModuleComponent component = moduleComponents.get(activeModule);
        if (component != null) {
            return component;
        }
        return ModuleRegistry.get(MODULE_OFFLINE);
    }

    private void syncSettingsModuleSelector() {
        if (settingsModuleBox == null) {
            return;
        }
        String activeLabel = moduleLabel(activeModule);
        if (Objects.equals(settingsModuleBox.getValue(), activeLabel)) {
            return;
        }
        settingsModuleSelectorSync = true;
        settingsModuleBox.getSelectionModel().select(activeLabel);
        settingsModuleSelectorSync = false;
    }

    private void updateTranslationUi() {
        boolean canToggle = MODULE_YOUTUBE_SUBS.equals(activeModule);
        boolean forceEnabled = MODULE_YOUTUBE_DUB.equals(activeModule);
        if (forceEnabled) {
            translateSubtitlesBox.setSelected(true);
        }
        boolean enabled = forceEnabled || (canToggle && translateSubtitlesBox.isSelected());
        translateSubtitlesBox.setDisable(!canToggle);
        targetLangBox.setDisable(!enabled);
    }

    private void saveActiveModuleState() {
        String module = normalizeModuleId(activeModule);
        String prefix = modulePrefPrefix(module);
        preferences.putBoolean(prefix + "initialized", true);
        preferences.put(prefix + "source_mode", safeValue(sourceModeBox));
        preferences.put(prefix + "output_mode", safeValue(outputModeBox));
        preferences.put(prefix + "local_path", trimToEmpty(localPathField.getText()));
        preferences.put(prefix + "youtube_url", trimToEmpty(youtubeUrlField.getText()));
        preferences.put(prefix + "output_dir", trimToEmpty(outputDirField.getText()));
        preferences.put(prefix + "output_prefix", trimToEmpty(outputPrefixField.getText()));
        preferences.put(prefix + "quality", safeValue(qualityBox));
        preferences.putBoolean(prefix + "playlist", playlistBox.isSelected());
        preferences.putBoolean(prefix + "keep_originals", keepOriginalsBox.isSelected());

        preferences.put(prefix + "model", safeValue(modelField));
        preferences.putBoolean(prefix + "auto_model", autoModelBox.isSelected());
        preferences.putBoolean(prefix + "prefer_gpu", useGpuBox.isSelected());
        preferences.put(prefix + "source_lang", safeValue(sourceLangBox));
        preferences.put(prefix + "summary_lang", safeValue(summaryLangBox));
        preferences.put(prefix + "target_lang", safeValue(targetLangBox));
        preferences.putInt(prefix + "batch_size", valueOf(batchSizeSpinner));
        preferences.putBoolean(prefix + "clean_text", cleanTextBox.isSelected());
        preferences.putBoolean(prefix + "export_md", exportMdBox.isSelected());
        preferences.putBoolean(prefix + "summary_pack", summaryPackBox.isSelected());
        preferences.putBoolean(prefix + "notify_done", notifyDoneBox.isSelected());
        preferences.putInt(prefix + "split_minutes", valueOf(splitMinutesSpinner));

        preferences.put(prefix + "subtitle_mode", safeValue(subtitleModeBox));
        preferences.put(prefix + "subtitle_font", trimToEmpty(subtitleFontField.getText()));
        preferences.putInt(prefix + "subtitle_size", valueOf(subtitleSizeSpinner));
        preferences.put(prefix + "subtitle_color", toHex(subtitleColorPicker.getValue()));
        preferences.put(prefix + "subtitle_outline_color", toHex(subtitleOutlineColorPicker.getValue()));
        preferences.putInt(prefix + "subtitle_outline_width", valueOf(subtitleOutlineWidthSpinner));
        preferences.putBoolean(prefix + "translate_subtitles", translateSubtitlesBox.isSelected());

        preferences.putBoolean(prefix + "diarization_enabled", diarizationEnabledBox.isSelected());
        preferences.put(prefix + "diarization_backend", safeValue(diarizationBackendBox));
        preferences.putInt(prefix + "diarization_min", valueOf(diarizationMinSpinner));
        preferences.putInt(prefix + "diarization_max", valueOf(diarizationMaxSpinner));
        preferences.putBoolean(prefix + "diarization_review", diarizationReviewBox.isSelected());
        preferences.putBoolean(prefix + "diarization_unmapped", diarizationIncludeUnmappedBox.isSelected());
        preferences.putBoolean(prefix + "diarization_prefix", diarizationPrefixSrtBox.isSelected());
        preferences.putBoolean(prefix + "diarization_prefill", diarizationProfilePrefillBox.isSelected());
        preferences.putBoolean(prefix + "diarization_fail", diarizationFailOnErrorBox.isSelected());
        preferences.put(prefix + "hf_token", trimToEmpty(hfTokenField.getText()));

        preferences.put(prefix + "speaker", trimToEmpty(speakerField.getText()));
        preferences.put(prefix + "topic", trimToEmpty(topicField.getText()));
        preferences.put(prefix + "conference_title", trimToEmpty(conferenceTitleField.getText()));
        preferences.put(prefix + "conference_date", trimToEmpty(conferenceDateField.getText()));
        preferences.put(prefix + "conference_rows", serializeConferenceRows());
        preferences.flush();
    }

    private boolean loadModuleState(String moduleId) {
        String module = normalizeModuleId(moduleId);
        String prefix = modulePrefPrefix(module);
        if (!preferences.getBoolean(prefix + "initialized", false)) {
            return false;
        }

        String sourceMode = trimToEmpty(preferences.get(prefix + "source_mode", safeValue(sourceModeBox)));
        if (!sourceMode.isBlank() && sourceModeBox.getItems().contains(sourceMode)) {
            sourceModeBox.getSelectionModel().select(sourceMode);
        }

        String outputMode = trimToEmpty(preferences.get(prefix + "output_mode", safeValue(outputModeBox)));
        if (!outputMode.isBlank() && outputModeBox.getItems().contains(outputMode)) {
            outputModeBox.getSelectionModel().select(outputMode);
        }

        localPathField.setText(trimToEmpty(preferences.get(prefix + "local_path", localPathField.getText())));
        youtubeUrlField.setText(trimToEmpty(preferences.get(prefix + "youtube_url", youtubeUrlField.getText())));
        outputDirField.setText(trimToEmpty(preferences.get(prefix + "output_dir", outputDirField.getText())));
        outputPrefixField.setText(trimToEmpty(preferences.get(prefix + "output_prefix", outputPrefixField.getText())));

        String quality = trimToEmpty(preferences.get(prefix + "quality", safeValue(qualityBox)));
        if (!quality.isBlank() && qualityBox.getItems().contains(quality)) {
            qualityBox.getSelectionModel().select(quality);
        }
        playlistBox.setSelected(preferences.getBoolean(prefix + "playlist", playlistBox.isSelected()));
        keepOriginalsBox.setSelected(preferences.getBoolean(prefix + "keep_originals", keepOriginalsBox.isSelected()));

        selectModel(trimToEmpty(preferences.get(prefix + "model", safeValue(modelField))));
        autoModelBox.setSelected(preferences.getBoolean(prefix + "auto_model", autoModelBox.isSelected()));
        useGpuBox.setSelected(preferences.getBoolean(prefix + "prefer_gpu", useGpuBox.isSelected()));

        selectComboValue(sourceLangBox, preferences.get(prefix + "source_lang", safeValue(sourceLangBox)));
        selectComboValue(summaryLangBox, preferences.get(prefix + "summary_lang", safeValue(summaryLangBox)));
        selectComboValue(targetLangBox, preferences.get(prefix + "target_lang", safeValue(targetLangBox)));

        batchSizeSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "batch_size", valueOf(batchSizeSpinner))
        );
        cleanTextBox.setSelected(preferences.getBoolean(prefix + "clean_text", cleanTextBox.isSelected()));
        exportMdBox.setSelected(preferences.getBoolean(prefix + "export_md", exportMdBox.isSelected()));
        summaryPackBox.setSelected(preferences.getBoolean(prefix + "summary_pack", summaryPackBox.isSelected()));
        notifyDoneBox.setSelected(preferences.getBoolean(prefix + "notify_done", notifyDoneBox.isSelected()));
        splitMinutesSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "split_minutes", valueOf(splitMinutesSpinner))
        );

        selectComboValue(subtitleModeBox, preferences.get(prefix + "subtitle_mode", safeValue(subtitleModeBox)));
        subtitleFontField.setText(preferences.get(prefix + "subtitle_font", trimToEmpty(subtitleFontField.getText())));
        subtitleSizeSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "subtitle_size", valueOf(subtitleSizeSpinner))
        );
        subtitleColorPicker.setValue(parseColor(
                preferences.get(prefix + "subtitle_color", toHex(subtitleColorPicker.getValue())),
                subtitleColorPicker.getValue()
        ));
        subtitleOutlineColorPicker.setValue(parseColor(
                preferences.get(prefix + "subtitle_outline_color", toHex(subtitleOutlineColorPicker.getValue())),
                subtitleOutlineColorPicker.getValue()
        ));
        subtitleOutlineWidthSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "subtitle_outline_width", valueOf(subtitleOutlineWidthSpinner))
        );
        translateSubtitlesBox.setSelected(preferences.getBoolean(prefix + "translate_subtitles", translateSubtitlesBox.isSelected()));

        diarizationEnabledBox.setSelected(preferences.getBoolean(prefix + "diarization_enabled", diarizationEnabledBox.isSelected()));
        selectComboValue(diarizationBackendBox, preferences.get(prefix + "diarization_backend", safeValue(diarizationBackendBox)));
        diarizationMinSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "diarization_min", valueOf(diarizationMinSpinner))
        );
        diarizationMaxSpinner.getValueFactory().setValue(
                preferences.getInt(prefix + "diarization_max", valueOf(diarizationMaxSpinner))
        );
        diarizationReviewBox.setSelected(preferences.getBoolean(prefix + "diarization_review", diarizationReviewBox.isSelected()));
        diarizationIncludeUnmappedBox.setSelected(preferences.getBoolean(prefix + "diarization_unmapped", diarizationIncludeUnmappedBox.isSelected()));
        diarizationPrefixSrtBox.setSelected(preferences.getBoolean(prefix + "diarization_prefix", diarizationPrefixSrtBox.isSelected()));
        diarizationProfilePrefillBox.setSelected(preferences.getBoolean(prefix + "diarization_prefill", diarizationProfilePrefillBox.isSelected()));
        diarizationFailOnErrorBox.setSelected(preferences.getBoolean(prefix + "diarization_fail", diarizationFailOnErrorBox.isSelected()));
        hfTokenField.setText(preferences.get(prefix + "hf_token", trimToEmpty(hfTokenField.getText())));

        speakerField.setText(preferences.get(prefix + "speaker", trimToEmpty(speakerField.getText())));
        topicField.setText(preferences.get(prefix + "topic", trimToEmpty(topicField.getText())));
        conferenceTitleField.setText(preferences.get(prefix + "conference_title", trimToEmpty(conferenceTitleField.getText())));
        conferenceDateField.setText(preferences.get(prefix + "conference_date", trimToEmpty(conferenceDateField.getText())));
        deserializeConferenceRows(preferences.get(prefix + "conference_rows", ""));

        return true;
    }

    private static String normalizeModuleId(String value) {
        String module = trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODULES.contains(module)) {
            return MODULE_OFFLINE;
        }
        return module;
    }

    private static String moduleLabel(String moduleId) {
        return ModuleRegistry.labelFor(normalizeModuleId(moduleId));
    }

    private static String modulePrefPrefix(String moduleId) {
        return PREF_MODULE_PREFIX + normalizeModuleId(moduleId) + ".";
    }

    private static void selectComboValue(ComboBox<String> box, String value) {
        String normalized = trimToEmpty(value);
        if (!normalized.isBlank() && box.getItems().contains(normalized)) {
            box.getSelectionModel().select(normalized);
        }
    }

    private String serializeConferenceRows() {
        try {
            var array = mapper.createArrayNode();
            for (ConferenceFileRow row : conferenceFiles) {
                var item = array.addObject();
                item.put("file_path", trimToEmpty(row.getFilePath()));
                item.put("speaker", trimToEmpty(row.getSpeaker()));
                item.put("description", trimToEmpty(row.getDescription()));
                item.put("lecture_date", trimToEmpty(row.getLectureDate()));
            }
            return mapper.writeValueAsString(array);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private void deserializeConferenceRows(String payload) {
        conferenceFiles.clear();
        String text = trimToEmpty(payload);
        if (text.isBlank()) {
            return;
        }
        try {
            JsonNode parsed = mapper.readTree(text);
            if (!parsed.isArray()) {
                return;
            }
            for (JsonNode item : parsed) {
                conferenceFiles.add(
                        new ConferenceFileRow(
                                item.path("file_path").asText(""),
                                item.path("speaker").asText(""),
                                item.path("description").asText(""),
                                item.path("lecture_date").asText("")
                        )
                );
            }
        } catch (Exception ignored) {
            // Keep existing empty list when parsing fails.
        }
    }

    private void applyTheme(String selectedTheme, boolean logChange) {
        String normalizedTheme = normalizeThemeName(selectedTheme);
        if (!rootPane.getStyleClass().contains("app-shell")) {
            rootPane.getStyleClass().add("app-shell");
        }
        if (!Objects.equals(themeBox.getValue(), normalizedTheme)) {
            themeBox.getSelectionModel().select(normalizedTheme);
        }
        if (Objects.equals(currentTheme, normalizedTheme)) {
            return;
        }

        rootPane.getStyleClass().removeAll("theme-light", "theme-dark", "theme-dracula");
        String themeCssClass = themeClassFromName(normalizedTheme);
        rootPane.getStyleClass().add(themeCssClass);
        currentTheme = normalizedTheme;
        preferences.put(PREF_THEME, normalizedTheme.toLowerCase(Locale.ROOT));
        preferences.flush();
        if (monitorWindow != null) {
            monitorWindow.applyThemeClass(themeCssClass);
        }

        if (logChange) {
            addUserLog("INFO", "Theme switched to " + normalizedTheme + ".");
        }
    }

    private String validateInputs() {
        String localPath = trimToEmpty(localPathField.getText());
        String youtubeUrl = trimToEmpty(youtubeUrlField.getText());
        String outputDir = trimToEmpty(outputDirField.getText());

        if (MODULE_OFFLINE.equals(activeModule) || MODULE_SPEAKER.equals(activeModule) || MODULE_CONFERENCE.equals(activeModule)) {
            if (localPath.isBlank()) {
                return "Please select local file/folder.";
            }
        }
        if (MODULE_YOUTUBE.equals(activeModule) || MODULE_YOUTUBE_SUBS.equals(activeModule) || MODULE_YOUTUBE_DUB.equals(activeModule)) {
            if (youtubeUrl.isBlank()) {
                return "Please enter YouTube URL.";
            }
        }
        if (outputDir.isBlank()) {
            return "Please choose output directory.";
        }
        if (MODULE_CONFERENCE.equals(activeModule) && conferenceFiles.isEmpty()) {
            return "Conference mode requires file metadata rows. Use 'Sync from source'.";
        }
        return null;
    }

    private ObjectNode buildPipelineParams() {
        ObjectNode params = mapper.createObjectNode();
        params.put("module", activeModule);

        ObjectNode source = params.putObject("source");
        source.put("mode", safeValue(sourceModeBox));
        source.put("path", trimToEmpty(localPathField.getText()));
        source.put("url", trimToEmpty(youtubeUrlField.getText()));
        source.put("is_playlist", playlistBox.isSelected());
        source.put("quality", safeValue(qualityBox));

        ObjectNode output = params.putObject("output");
        output.put("mode", safeValue(outputModeBox));
        output.put("out_dir", trimToEmpty(outputDirField.getText()));
        output.put("output_prefix", trimToEmpty(outputPrefixField.getText()));
        output.put("keep_originals", keepOriginalsBox.isSelected());

        ObjectNode transcription = params.putObject("transcription");
        transcription.put("model", safeValue(modelField));
        transcription.put("auto_model", autoModelBox.isSelected());
        transcription.put("prefer_gpu", useGpuBox.isSelected());
        transcription.put("source_lang", safeValue(sourceLangBox));
        transcription.put("batch_size", valueOf(batchSizeSpinner));

        ObjectNode translation = params.putObject("translation");
        boolean translationEnabled = MODULE_YOUTUBE_DUB.equals(activeModule)
                || (MODULE_YOUTUBE_SUBS.equals(activeModule) && translateSubtitlesBox.isSelected());
        translation.put("enabled", translationEnabled);
        translation.put("target_lang", safeValue(targetLangBox));

        ObjectNode subtitles = params.putObject("subtitles");
        subtitles.put("mode", safeValue(subtitleModeBox));
        subtitles.put("font", trimToEmpty(subtitleFontField.getText()));
        subtitles.put("size", valueOf(subtitleSizeSpinner));
        subtitles.put("color", toHex(subtitleColorPicker.getValue()));
        subtitles.put("outline_color", toHex(subtitleOutlineColorPicker.getValue()));
        subtitles.put("outline_width", valueOf(subtitleOutlineWidthSpinner));

        ObjectNode text = params.putObject("text");
        text.put("clean_text", cleanTextBox.isSelected());
        text.put("export_md", exportMdBox.isSelected());
        text.put("summary_pack", summaryPackBox.isSelected());
        text.put("split_minutes", valueOf(splitMinutesSpinner));
        text.put("summary_lang", safeValue(summaryLangBox));
        text.put("speaker", trimToEmpty(speakerField.getText()));
        text.put("topic", trimToEmpty(topicField.getText()));

        ObjectNode diarization = params.putObject("diarization");
        diarization.put("enabled", diarizationEnabledBox.isSelected());
        diarization.put("backend", safeValue(diarizationBackendBox));
        diarization.put("min_speakers", valueOf(diarizationMinSpinner));
        diarization.put("max_speakers", valueOf(diarizationMaxSpinner));
        diarization.put("review_after_file", diarizationReviewBox.isSelected());
        diarization.put("include_unmapped_speakers", diarizationIncludeUnmappedBox.isSelected());
        diarization.put("speaker_prefix_in_srt", diarizationPrefixSrtBox.isSelected());
        diarization.put("profile_prefill", diarizationProfilePrefillBox.isSelected());
        diarization.put("fail_on_error", diarizationFailOnErrorBox.isSelected());
        diarization.put("hf_token", trimToEmpty(hfTokenField.getText()));

        ObjectNode profilesNode = diarization.putObject("speaker_profiles");
        collectSpeakerProfiles().forEach(profilesNode::put);

        ObjectNode conferenceDefaults = params.putObject("conference_defaults");
        conferenceDefaults.put("speaker", trimToEmpty(speakerField.getText()));
        conferenceDefaults.put("topic", trimToEmpty(topicField.getText()));
        conferenceDefaults.put("lecture_description", "");
        conferenceDefaults.put("lecture_date", "");
        conferenceDefaults.put("conference_title", trimToEmpty(conferenceTitleField.getText()));
        conferenceDefaults.put("conference_date", trimToEmpty(conferenceDateField.getText()));

        ObjectNode conferenceMeta = params.putObject("conference_meta");
        for (ConferenceFileRow row : conferenceFiles) {
            String filePath = trimToEmpty(row.getFilePath());
            if (filePath.isBlank()) {
                continue;
            }
            ObjectNode item = conferenceMeta.putObject(filePath);
            item.put("speaker", trimToEmpty(row.getSpeaker()));
            item.put("topic", trimToEmpty(row.getDescription()));
            item.put("lecture_description", trimToEmpty(row.getDescription()));
            item.put("lecture_date", trimToEmpty(row.getLectureDate()));
            item.put("conference_title", trimToEmpty(conferenceTitleField.getText()));
            item.put("conference_date", trimToEmpty(conferenceDateField.getText()));
        }

        applyActiveModulePayloadOverrides(source, output, translation, diarization);

        return params;
    }

    private void applyActiveModulePayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    ) {
        ModuleComponent component = getActiveModuleComponent();
        if (component != null) {
            component.applyPayloadOverrides(source, output, translation, diarization);
        }
    }

    private CompletableFuture<Boolean> runPreflightAsync(ObjectNode params, boolean verbose) {
        return backendClient.sendRequest("preflight_check", params)
                .thenApply(result -> {
                    boolean ok = result.path("ok").asBoolean(false);
                    if (verbose) {
                        Platform.runLater(() -> {
                            JsonNode checks = result.path("checks");
                            if (checks.isArray()) {
                                addUserLog("INFO", "Preflight checks:");
                                for (JsonNode check : checks) {
                                    String status = check.path("status").asText("-");
                                    String name = check.path("name").asText("-");
                                    String message = check.path("message").asText("");
                                    String level = switch (status) {
                                        case "fail" -> "ERROR";
                                        case "warn" -> "WARN";
                                        default -> "INFO";
                                    };
                                    addUserLog(level, " - " + name + " [" + status + "]: " + message);
                                }
                            }
                            if (ok) {
                                addUserLog("SUCCESS", "Preflight passed.");
                            } else {
                                addUserLog("ERROR", "Preflight found blocking issues.");
                            }
                        });
                    }
                    return ok;
                });
    }

    private void handleBackendEvent(JsonNode event) {
        Platform.runLater(() -> {
            String eventName = event.path("event").asText("");
            String jobId = event.path("job_id").asText("");
            JsonNode payload = event.path("payload");

            switch (eventName) {
                case "job.log" -> {
                    String line = payload.path("line").asText("");
                    String level = detectLevel(line);
                    LogCategory category = detectCategory(line);
                    addLog(level, category, withJobPrefix(jobId, line));
                }
                case "job.progress" -> {
                    updateJobStatus(jobId, "running");
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        double overall = payload.path("overall_pct").asDouble(0.0);
                        boolean indeterminate = payload.path("indeterminate").asBoolean(false);
                        String step = payload.path("step").asText("-");

                        if (indeterminate) {
                            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                        } else {
                            progressBar.setProgress(Math.max(0.0, Math.min(1.0, overall / 100.0)));
                        }

                        stepLabel.setText(step + " (" + String.format(Locale.ROOT, "%.0f", overall) + "%)");
                        statusLabel.setText("Running");
                        updateEta(overall, indeterminate);
                    }
                }
                case "job.started" -> {
                    updateJobStatus(jobId, "running");
                    boolean affectsCurrent = false;
                    if (jobId != null && !jobId.isBlank() && (currentJobId == null || currentJobId.isBlank())) {
                        currentJobId = jobId;
                        affectsCurrent = true;
                    } else if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        affectsCurrent = true;
                    }
                    if (affectsCurrent) {
                        resetEtaDisplay();
                    }
                    addUserLog("INFO", withJobPrefix(jobId, "Job running..."));
                    refreshJobsSilently();
                }
                case "job.completed" -> {
                    updateJobStatus(jobId, "completed");
                    JsonNode result = payload.path("result");
                    String finalOut = result.path("final_output_dir").asText("");
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        statusLabel.setText("Completed");
                        stepLabel.setText("done");
                        progressBar.setProgress(1.0);
                        setEtaDone();
                        lastOutputDir = finalOut;
                    }
                    addUserLog("SUCCESS", withJobPrefix(jobId, "Job completed. Output: " + finalOut));
                    if (notifyDoneBox.isSelected() && finalOut != null && !finalOut.isBlank()) {
                        openPath(finalOut);
                    }
                    refreshJobsSilently();
                }
                case "job.failed" -> {
                    updateJobStatus(jobId, "failed");
                    JsonNode err = payload.path("error");
                    String message = err.path("message").asText("Pipeline failed");
                    addTechnicalLog("ERROR", withJobPrefix(jobId, message));
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        statusLabel.setText("Failed");
                        stepLabel.setText("error");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                    }
                    refreshJobsSilently();
                }
                case "job.cancelled" -> {
                    updateJobStatus(jobId, "cancelled");
                    addUserLog("WARN", withJobPrefix(jobId, "Job cancelled."));
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        statusLabel.setText("Cancelled");
                        stepLabel.setText("cancelled");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                    }
                    refreshJobsSilently();
                }
                case "job.cancel_requested" -> addUserLog("INFO", withJobPrefix(jobId, "Cancel request accepted."));
                case "backend.stderr" -> addTechnicalLog("DEBUG", payload.path("message").asText(""));
                case "backend.io_error", "backend.protocol_error" ->
                        addTechnicalLog("ERROR", payload.path("message").asText(""));
                default -> {
                    // Ignore unknown events.
                }
            }
        });
    }

    private void onJobSelectionChanged(JobRow selected) {
        if (selected == null || backendClient == null) {
            return;
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("job_id", selected.getJobId());
        backendClient.sendRequest("get_job", params)
                .thenAccept(result -> Platform.runLater(() -> {
                    try {
                        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                        jobDetailArea.setText(pretty);
                    } catch (JsonProcessingException e) {
                        jobDetailArea.setText(result.toString());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> jobDetailArea.setText("Cannot load job details: " + rootMessage(ex)));
                    return null;
                });
    }

    private void refreshJobsSilently() {
        refreshJobs(false);
    }

    private void refreshJobs(boolean verbose) {
        if (backendClient == null) {
            return;
        }

        refreshJobsButton.setDisable(true);
        backendClient.sendRequest("list_jobs")
                .thenAccept(result -> Platform.runLater(() -> {
                    Map<String, JobRow> fresh = new LinkedHashMap<>();
                    JsonNode jobs = result.path("jobs");
                    if (jobs.isArray()) {
                        for (JsonNode snapshot : jobs) {
                            String jobId = snapshot.path("job_id").asText("");
                            String status = snapshot.path("status").asText("-");
                            String created = snapshot.path("created_at").asText("-");
                            String mode = snapshot.path("request").path("module").asText("");
                            if (mode.isBlank()) {
                                mode = snapshot.path("request").path("output").path("mode").asText("-");
                            } else {
                                mode = moduleLabel(mode);
                            }
                            String source = snapshot.path("request").path("source").path("mode").asText("-");
                            fresh.put(jobId, new JobRow(jobId, status, mode, source, trimTimestamp(created)));
                        }
                    }

                    jobsById.clear();
                    jobsById.putAll(fresh);
                    rebuildJobTable();
                    refreshJobsButton.setDisable(false);

                    if (verbose) {
                        addUserLog("INFO", "Job history refreshed. Jobs: " + jobsById.size());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        refreshJobsButton.setDisable(false);
                        if (verbose) {
                            addTechnicalLog("ERROR", "Failed to refresh jobs: " + rootMessage(ex));
                        }
                    });
                    return null;
                });
    }

    private void rebuildJobTable() {
        List<JobRow> sorted = new ArrayList<>(jobsById.values());
        sorted.sort(Comparator.comparing(JobRow::getCreated).reversed());
        jobRows.setAll(sorted);
    }

    private void upsertJobRow(String jobId, String status, String mode, String source, String created) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }

        JobRow row = jobsById.get(jobId);
        if (row == null) {
            row = new JobRow(jobId, status, mode, source, created);
            jobsById.put(jobId, row);
        } else {
            row.setStatus(status);
            if (mode != null && !mode.isBlank()) {
                row.setMode(mode);
            }
            if (source != null && !source.isBlank()) {
                row.setSource(source);
            }
            if (created != null && !created.isBlank()) {
                row.setCreated(created);
            }
        }
        rebuildJobTable();
    }

    private void updateJobStatus(String jobId, String status) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }

        JobRow row = jobsById.get(jobId);
        if (row != null) {
            row.setStatus(status);
            jobHistoryTable.refresh();
            return;
        }

        upsertJobRow(jobId, status, moduleLabel(activeModule), sourceModeBox.getValue(), nowStamp());
    }

    private Map<String, String> collectSpeakerProfiles() {
        Map<String, String> profiles = new LinkedHashMap<>();
        for (SpeakerProfileRow row : speakerProfiles) {
            String label = normalizeSpeakerLabel(row.getLabel());
            String name = trimToEmpty(row.getName());
            if (!label.isBlank()) {
                profiles.put(label, name);
            }
        }
        return profiles;
    }

    private Map<String, String> parseProfilesFromText(String content, boolean assumeJson) throws Exception {
        Map<String, String> parsed = new LinkedHashMap<>();
        String safeContent = content == null ? "" : content;

        boolean parsedJson = false;
        if (assumeJson || safeContent.trim().startsWith("{")) {
            JsonNode node = mapper.readTree(safeContent);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(entry -> parsed.put(normalizeSpeakerLabel(entry.getKey()), trimToEmpty(entry.getValue().asText(""))));
                parsedJson = true;
            }
        }

        if (!parsedJson) {
            String[] lines = safeContent.split("\\r?\\n");
            for (String line : lines) {
                String cleaned = trimToEmpty(line);
                if (cleaned.isBlank() || cleaned.startsWith("#")) {
                    continue;
                }
                String[] parts = cleaned.split("=", 2);
                if (parts.length < 2) {
                    parts = cleaned.split(":", 2);
                }
                if (parts.length < 2) {
                    continue;
                }
                String label = normalizeSpeakerLabel(parts[0]);
                String name = trimToEmpty(parts[1]);
                if (!label.isBlank()) {
                    parsed.put(label, name);
                }
            }
        }

        return parsed;
    }

    private void refreshProfilePreview() {
        Map<String, String> profiles = collectSpeakerProfiles();
        if (profiles.isEmpty()) {
            profilePreviewArea.setText("No speaker profiles configured.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Profiles: ").append(profiles.size()).append("\n");
        profiles.forEach((label, name) -> sb.append(" - ").append(label).append(" => ").append(name).append("\n"));
        profilePreviewArea.setText(sb.toString());
    }

    private void applyUiMode() {
        boolean simpleMode = simpleModeBox.isSelected();
        simpleHintCard.setManaged(simpleMode);
        simpleHintCard.setVisible(simpleMode);

        syncModuleButtons();
        addUserLog("INFO", simpleMode ? "Simple mode enabled." : "Advanced mode enabled.");
    }

    private void updateSourceModeUi() {
        String mode = safeValue(sourceModeBox);
        boolean local = "local".equals(mode);

        localPathField.setDisable(!local);
        youtubeUrlField.setDisable(local);
        playlistBox.setDisable(local);
        qualityBox.setDisable(local);
        keepOriginalsBox.setDisable(local);
    }

    private void updateTargetsFromCapabilities(JsonNode targetsNode) {
        if (targetsNode == null || !targetsNode.isArray()) {
            return;
        }

        List<String> targets = new ArrayList<>();
        for (JsonNode target : targetsNode) {
            String normalized = trimToEmpty(target.asText("")).replace("→", "->");
            if (!normalized.isBlank()) {
                targets.add(normalized);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        String current = safeValue(targetLangBox);
        targetLangBox.setItems(FXCollections.observableArrayList(targets));
        if (targets.contains(current)) {
            targetLangBox.getSelectionModel().select(current);
        } else {
            targetLangBox.getSelectionModel().selectFirst();
        }
    }

    private String resolveLocalVersion() {
        List<Path> candidates = new ArrayList<>();
        String projectRoot = trimToEmpty(System.getenv("TM_PROJECT_ROOT"));
        if (!projectRoot.isBlank()) {
            candidates.add(Path.of(projectRoot, "version.txt"));
        }
        candidates.add(Path.of(System.getProperty("user.dir"), "version.txt"));

        try {
            Path codePath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            Path codeDir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (codeDir != null) {
                candidates.add(codeDir.resolve("version.txt"));
                candidates.add(codeDir.resolve("backend").resolve("version.txt"));
                candidates.add(codeDir.resolve("app").resolve("backend").resolve("version.txt"));
            }
        } catch (Exception ignored) {
            // Best effort only.
        }

        for (Path candidate : candidates) {
            try {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String text = trimToEmpty(Files.readString(candidate, StandardCharsets.UTF_8));
                if (!text.isBlank()) {
                    return text;
                }
            } catch (Exception ignored) {
                // Try next candidate path.
            }
        }
        return "";
    }

    private void setAppVersion(String version) {
        String normalized = trimToEmpty(version);
        if (normalized.equals(appVersion)) {
            return;
        }
        appVersion = normalized;
        if (appTitleLabel != null) {
            appTitleLabel.setText(buildAppTitleText());
        }
        updateWindowTitle();
    }

    private String buildAppTitleText() {
        if (appVersion == null || appVersion.isBlank()) {
            return APP_NAME;
        }
        return APP_NAME + " " + appVersion;
    }

    private void updateWindowTitle() {
        Stage stage = getStage();
        if (stage == null) {
            return;
        }
        stage.setTitle(buildAppTitleText() + " - " + WINDOW_TITLE_SUFFIX);
    }

    private void setRunning(boolean running) {
        startButton.setDisable(running);
        cancelButton.setDisable(!running);
        preflightButton.setDisable(running);
        simpleModeBox.setDisable(running);
        moduleFlowActionButton.setDisable(running);
        runModuleButton.setDisable(running);
        advancedModuleButton.setDisable(running);
        diarizationModuleButton.setDisable(running);
        logsModuleButton.setDisable(running);
        jobsModuleButton.setDisable(running);
        youtubeDubModuleButton.setDisable(running);
        settingsModuleButton.setDisable(running);
        if (installRuntimeButton != null) {
            installRuntimeButton.setDisable(running || runtimeBootstrapRunning);
        }
        if (settingsRepairRuntimeButton != null) {
            settingsRepairRuntimeButton.setDisable(running || runtimeBootstrapRunning);
        }
        if (settingsLoadModuleButton != null) {
            settingsLoadModuleButton.setDisable(running);
        }
        if (settingsResetModuleDefaultsButton != null) {
            settingsResetModuleDefaultsButton.setDisable(running);
        }
        if (settingsModuleBox != null) {
            settingsModuleBox.setDisable(running);
        }
    }

    private void resetEtaDisplay() {
        etaAnchorMillis = -1L;
        etaAnchorPercent = -1.0;
        if (etaLabel != null) {
            etaLabel.setText("--:--");
        }
    }

    private void setEtaDone() {
        etaAnchorMillis = -1L;
        etaAnchorPercent = -1.0;
        if (etaLabel != null) {
            etaLabel.setText("0s");
        }
    }

    private void updateEta(double overallPercent, boolean indeterminate) {
        if (etaLabel == null) {
            return;
        }

        if (indeterminate || overallPercent <= 0.0 || overallPercent >= 100.0) {
            etaAnchorMillis = -1L;
            etaAnchorPercent = -1.0;
            etaLabel.setText("--:--");
            return;
        }

        long now = System.currentTimeMillis();
        if (etaAnchorMillis < 0L || etaAnchorPercent < 0.0 || overallPercent <= etaAnchorPercent) {
            etaAnchorMillis = now;
            etaAnchorPercent = overallPercent;
            etaLabel.setText("Estimating...");
            return;
        }

        double percentDelta = overallPercent - etaAnchorPercent;
        long elapsedMillis = now - etaAnchorMillis;
        if (percentDelta < 0.5 || elapsedMillis < 4000L) {
            return;
        }

        double millisPerPercent = elapsedMillis / percentDelta;
        double remainingPercent = 100.0 - overallPercent;
        long remainingMillis = Math.round(Math.max(0.0, millisPerPercent * remainingPercent));

        etaLabel.setText(formatDurationShort(remainingMillis));

        etaAnchorMillis = now;
        etaAnchorPercent = overallPercent;
    }

    private String formatDurationShort(long millis) {
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

    private final class ControllerModuleUiContext implements ModuleComponent.ModuleUiContext {
        @Override
        public void selectSourceMode(String value) {
            selectComboValue(sourceModeBox, value);
        }

        @Override
        public void selectOutputMode(String value) {
            selectComboValue(outputModeBox, value);
        }

        @Override
        public void setDiarizationEnabled(boolean value) {
            diarizationEnabledBox.setSelected(value);
        }

        @Override
        public void selectDiarizationBackend(String value) {
            selectComboValue(diarizationBackendBox, value);
        }

        @Override
        public void setDiarizationProfilePrefill(boolean value) {
            diarizationProfilePrefillBox.setSelected(value);
        }

        @Override
        public void setDiarizationPrefixSrt(boolean value) {
            diarizationPrefixSrtBox.setSelected(value);
        }

        @Override
        public void setKeepOriginals(boolean value) {
            keepOriginalsBox.setSelected(value);
        }

        @Override
        public void setCleanText(boolean value) {
            cleanTextBox.setSelected(value);
        }

        @Override
        public void setSummaryPack(boolean value) {
            summaryPackBox.setSelected(value);
        }

        @Override
        public void setTranslateSubtitles(boolean value) {
            translateSubtitlesBox.setSelected(value);
        }

        @Override
        public void selectSubtitleMode(String value) {
            selectComboValue(subtitleModeBox, value);
        }

        @Override
        public void selectTargetLang(String value) {
            selectComboValue(targetLangBox, value);
        }

        @Override
        public void selectRunTab() {
            selectModule(runTab);
        }

        @Override
        public void selectAdvancedTab() {
            selectModule(advancedTab);
        }

        @Override
        public void selectDiarizationTab() {
            selectModule(diarizationTab);
        }
    }

    private void addUserLog(String level, String message) {
        addLog(level, LogCategory.USER, message);
    }

    private void addTechnicalLog(String level, String message) {
        addLog(level, LogCategory.TECHNICAL, message);
    }

    private void addLog(String level, LogCategory category, String message) {
        String safeMessage = trimToEmpty(message);
        if (safeMessage.isBlank()) {
            return;
        }

        LogEntry entry = new LogEntry(LocalDateTime.now(), normalizeLevel(level), category, safeMessage);
        allLogs.add(entry);

        if (allLogs.size() > MAX_LOG_ENTRIES) {
            allLogs.remove(0);
            rebuildLogStreams();
        } else {
            appendToStream(entry);
        }

        refreshFilteredLogs();
    }

    private void appendToStream(LogEntry entry) {
        if (entry.category() == LogCategory.USER) {
            userLogArea.appendText(entry.format() + "\n");
            userLogArea.setScrollTop(Double.MAX_VALUE);
        } else {
            technicalLogArea.appendText(entry.format() + "\n");
            technicalLogArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void rebuildLogStreams() {
        StringBuilder userSb = new StringBuilder();
        StringBuilder techSb = new StringBuilder();
        for (LogEntry entry : allLogs) {
            if (entry.category() == LogCategory.USER) {
                userSb.append(entry.format()).append("\n");
            } else {
                techSb.append(entry.format()).append("\n");
            }
        }
        userLogArea.setText(userSb.toString());
        technicalLogArea.setText(techSb.toString());
        userLogArea.setScrollTop(Double.MAX_VALUE);
        technicalLogArea.setScrollTop(Double.MAX_VALUE);
    }

    private void refreshFilteredLogs() {
        String query = trimToEmpty(logSearchField.getText()).toLowerCase(Locale.ROOT);
        String selectedLevel = normalizeLevel(logLevelFilterBox.getValue());
        boolean includeUser = showUserLogsBox.isSelected();
        boolean includeTechnical = showTechnicalLogsBox.isSelected();

        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : allLogs) {
            if (entry.category() == LogCategory.USER && !includeUser) {
                continue;
            }
            if (entry.category() == LogCategory.TECHNICAL && !includeTechnical) {
                continue;
            }
            if (!"ALL".equals(selectedLevel) && !entry.level().equals(selectedLevel)) {
                continue;
            }
            if (!query.isBlank() && !entry.message().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            sb.append(entry.format()).append("\n");
        }

        filteredLogArea.setText(sb.toString());
        filteredLogArea.setScrollTop(Double.MAX_VALUE);
    }

    private RuntimeBootstrapTarget resolveRuntimeBootstrapTarget() {
        Path appDataPath = resolveRuntimeAppDataDir();
        Set<Path> roots = new LinkedHashSet<>();

        String envProjectRoot = trimToEmpty(System.getenv("TM_PROJECT_ROOT"));
        if (!envProjectRoot.isBlank()) {
            roots.add(Path.of(envProjectRoot));
        }

        roots.add(Path.of(System.getProperty("user.dir")));

        try {
            Path codePath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            Path codeDir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (codeDir != null) {
                roots.add(codeDir);
            }
        } catch (Exception ignored) {
            // Best effort only.
        }

        for (Path rootPath : roots) {
            if (rootPath == null) {
                continue;
            }
            Path normalizedRoot = rootPath.toAbsolutePath().normalize();

            RuntimeBootstrapTarget bundledAppTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("app").resolve("backend").resolve("bootstrap_runtime.ps1"),
                    normalizedRoot.resolve("app").resolve("backend"),
                    appDataPath
            );
            if (bundledAppTarget != null) {
                return bundledAppTarget;
            }

            RuntimeBootstrapTarget bundledTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("backend").resolve("bootstrap_runtime.ps1"),
                    normalizedRoot.resolve("backend"),
                    appDataPath
            );
            if (bundledTarget != null) {
                return bundledTarget;
            }

            RuntimeBootstrapTarget devTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("scripts").resolve("bootstrap_runtime.ps1"),
                    normalizedRoot,
                    appDataPath
            );
            if (devTarget != null) {
                return devTarget;
            }
        }

        return null;
    }

    private RuntimeBootstrapTarget buildRuntimeBootstrapTarget(Path scriptPath, Path backendRoot, Path appDataPath) {
        if (scriptPath == null || backendRoot == null || appDataPath == null) {
            return null;
        }
        try {
            Path normalizedScript = scriptPath.toAbsolutePath().normalize();
            Path normalizedBackendRoot = backendRoot.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalizedScript)) {
                return null;
            }
            if (!isValidBackendRoot(normalizedBackendRoot)) {
                return null;
            }

            Path normalizedAppData = appDataPath.toAbsolutePath().normalize();
            Path progressPath = normalizedAppData.resolve("runtime-bootstrap-ui-progress.txt");
            Path logPath = normalizedAppData.resolve("runtime-bootstrap.log");
            return new RuntimeBootstrapTarget(
                    normalizedScript,
                    normalizedBackendRoot,
                    normalizedAppData,
                    progressPath,
                    logPath
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isValidBackendRoot(Path backendRoot) {
        if (backendRoot == null || !Files.isDirectory(backendRoot)) {
            return false;
        }
        Path serverPy = backendRoot.resolve("transcribemate").resolve("v2").resolve("backend").resolve("server.py");
        Path requirements = backendRoot.resolve("requirements.txt");
        return Files.isRegularFile(serverPy) && Files.isRegularFile(requirements);
    }

    private Path resolveRuntimeAppDataDir() {
        String localAppData = trimToEmpty(System.getenv("LOCALAPPDATA"));
        if (!localAppData.isBlank()) {
            return Path.of(localAppData, APP_NAME);
        }

        String userHome = trimToEmpty(System.getProperty("user.home"));
        if (userHome.isBlank()) {
            return Path.of(System.getProperty("user.dir"), APP_NAME);
        }

        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return Path.of(userHome, "AppData", "Local", APP_NAME);
        }
        return Path.of(userHome, ".local", "share", APP_NAME);
    }

    private void showRuntimeBootstrapWindow(RuntimeBootstrapTarget target, boolean mandatoryLaunch, boolean repairMode) {
        runtimeBootstrapRunning = true;
        if (installRuntimeButton != null) {
            installRuntimeButton.setDisable(true);
        }
        if (settingsRepairRuntimeButton != null) {
            settingsRepairRuntimeButton.setDisable(true);
        }
        if (settingsLoadModuleButton != null) {
            settingsLoadModuleButton.setDisable(true);
        }
        if (settingsResetModuleDefaultsButton != null) {
            settingsResetModuleDefaultsButton.setDisable(true);
        }
        if (settingsModuleBox != null) {
            settingsModuleBox.setDisable(true);
        }

        addUserLog("INFO", repairMode ? "Starting runtime repair..." : "Starting online runtime setup...");

        Label headline = new Label(repairMode ? "Runtime Repair" : "Online Runtime Setup");
        headline.getStyleClass().add("section-title");

        Label intro = new Label(repairMode
                ? "Reinstalling embedded Python runtime, dependencies, AI models and FFmpeg."
                : "Installing embedded Python, dependencies, AI models and FFmpeg.");
        intro.setWrapText(true);
        intro.getStyleClass().add("small-label");

        Label status = new Label("Starting bootstrap process...");
        status.getStyleClass().add("small-label");

        ProgressBar progressBarLocal = new ProgressBar(0.0);
        progressBarLocal.setMaxWidth(Double.MAX_VALUE);

        TextArea liveLogArea = new TextArea();
        liveLogArea.setEditable(false);
        liveLogArea.setWrapText(false);
        liveLogArea.setPrefRowCount(18);
        liveLogArea.getStyleClass().add("runtime-log-area");

        Button openLogButton = new Button("Open bootstrap log");
        openLogButton.setOnAction(event -> openPath(target.logFilePath().toString()));
        Button closeButton = new Button("Close");
        closeButton.setDisable(true);
        Button closeAppButton = new Button("Close application");
        closeAppButton.setVisible(false);
        closeAppButton.setManaged(false);
        closeAppButton.setDisable(true);

        HBox actions = new HBox(8.0, openLogButton, closeButton, closeAppButton);
        VBox content = new VBox(10.0, headline, intro, status, progressBarLocal, liveLogArea, actions);
        content.setPadding(new Insets(12.0));
        content.getStyleClass().addAll("app-shell", themeClassFromName(currentTheme));

        Stage dialog = new Stage();
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(repairMode ? "Runtime Repair" : "Online Runtime Setup");
        dialog.setMinWidth(860);
        dialog.setMinHeight(520);
        dialog.setScene(new Scene(content, 960, 620));
        if (rootPane != null && rootPane.getScene() != null) {
            dialog.getScene().getStylesheets().setAll(rootPane.getScene().getStylesheets());
        }
        closeButton.setOnAction(event -> dialog.close());
        closeAppButton.setOnAction(event -> {
            dialog.close();
            Platform.exit();
        });
        dialog.setOnCloseRequest(event -> {
            if (runtimeBootstrapRunning) {
                event.consume();
            }
        });
        dialog.show();

        CompletableFuture.runAsync(() -> {
            int exitCode = -1;
            Exception processError = null;
            try {
                Files.createDirectories(target.appDataDir());
                Files.deleteIfExists(target.progressFilePath());
                if (repairMode) {
                    Path markerPath = target.appDataDir()
                            .resolve("runtime")
                            .resolve(RUNTIME_READY_MARKER_NAME);
                    try {
                        Files.deleteIfExists(markerPath);
                    } catch (Exception ignored) {
                        // Best effort only.
                    }
                }

                List<String> command = new ArrayList<>();
                command.add(resolvePowerShellExecutable());
                command.add("-NoLogo");
                command.add("-NoProfile");
                command.add("-NonInteractive");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
                command.add(target.scriptPath().toString());
                command.add("-WithModels");
                command.add("-InstallDiarization");
                command.add("-DownloadFfmpeg");
                command.add("-BackendRoot");
                command.add(target.backendRoot().toString());
                command.add("-ProgressFile");
                command.add(target.progressFilePath().toString());
                command.add("-LogFile");
                command.add(target.logFilePath().toString());

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.directory(target.backendRoot().toFile());
                processBuilder.redirectErrorStream(true);
                processBuilder.environment().putIfAbsent("PYTHONUTF8", "1");
                processBuilder.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

                Process process = processBuilder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String outputLine = line;
                        RuntimeBootstrapProgress parsed = parseRuntimeBootstrapProgress(outputLine);
                        Platform.runLater(() -> {
                            appendBootstrapLogLine(liveLogArea, outputLine);
                            if (parsed != null) {
                                progressBarLocal.setProgress(parsed.percent() / 100.0);
                                if (!parsed.message().isBlank()) {
                                    status.setText(parsed.message());
                                }
                            }
                        });
                    }
                }

                exitCode = process.waitFor();
            } catch (Exception ex) {
                processError = ex;
            }

            int finalExitCode = exitCode;
            Exception finalProcessError = processError;
            Platform.runLater(() -> {
                runtimeBootstrapRunning = false;
                if (installRuntimeButton != null) {
                    installRuntimeButton.setDisable(false);
                }
                if (settingsRepairRuntimeButton != null) {
                    settingsRepairRuntimeButton.setDisable(false);
                }
                if (settingsLoadModuleButton != null) {
                    settingsLoadModuleButton.setDisable(false);
                }
                if (settingsResetModuleDefaultsButton != null) {
                    settingsResetModuleDefaultsButton.setDisable(false);
                }
                if (settingsModuleBox != null) {
                    settingsModuleBox.setDisable(false);
                }
                closeButton.setDisable(false);
                dialog.setOnCloseRequest(null);

                if (finalProcessError != null) {
                    status.setText(repairMode ? "Runtime repair failed to start." : "Runtime setup failed to start.");
                    appendBootstrapLogLine(liveLogArea, "ERROR: " + rootMessage(finalProcessError));
                    addTechnicalLog(
                            "ERROR",
                            (repairMode ? "Runtime repair failed to start: " : "Online runtime setup failed to start: ")
                                    + rootMessage(finalProcessError)
                    );
                    return;
                }

                if (finalExitCode == 0) {
                    progressBarLocal.setProgress(1.0);
                    status.setText(mandatoryLaunch || repairMode
                            ? "Runtime setup completed successfully. Restart the app."
                            : "Runtime setup completed successfully.");
                    closeAppButton.setDisable(false);
                    closeAppButton.setVisible(true);
                    closeAppButton.setManaged(true);
                    addUserLog("SUCCESS", repairMode
                            ? "Runtime repair completed successfully."
                            : "Online runtime setup completed successfully.");
                    if (mandatoryLaunch || repairMode || backendClient == null) {
                        addUserLog("INFO", "Use 'Close application' and start TranscribeMate again.");
                    }
                    return;
                }

                status.setText(repairMode ? "Runtime repair failed. See bootstrap log." : "Runtime setup failed. See bootstrap log.");
                addTechnicalLog(
                        "ERROR",
                        (repairMode ? "Runtime repair failed" : "Online runtime setup failed")
                                + " (exit " + finalExitCode + "). Log: " + target.logFilePath()
                );
            });
        });
    }

    private RuntimeBootstrapProgress parseRuntimeBootstrapProgress(String line) {
        String raw = trimToEmpty(line);
        if (raw.isBlank()) {
            return null;
        }

        int marker = raw.indexOf("TM_PROGRESS|");
        if (marker < 0) {
            return null;
        }

        String payload = raw.substring(marker + "TM_PROGRESS|".length());
        int divider = payload.indexOf('|');
        if (divider <= 0) {
            return null;
        }

        String percentText = trimToEmpty(payload.substring(0, divider));
        String message = trimToEmpty(payload.substring(divider + 1));
        try {
            int percent = Integer.parseInt(percentText);
            percent = Math.max(0, Math.min(100, percent));
            return new RuntimeBootstrapProgress(percent, message);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendBootstrapLogLine(TextArea area, String line) {
        if (area == null) {
            return;
        }
        String safeLine = line == null ? "" : line;
        area.appendText(safeLine + "\n");
        int currentLength = area.getLength();
        if (currentLength > BOOTSTRAP_LOG_MAX_CHARS) {
            int removeChars = currentLength - BOOTSTRAP_LOG_MAX_CHARS;
            area.deleteText(0, removeChars);
        }
        area.positionCaret(area.getLength());
    }

    private String resolvePowerShellExecutable() {
        String osName = trimToEmpty(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return "pwsh";
        }

        String systemRoot = trimToEmpty(System.getenv("SystemRoot"));
        if (systemRoot.isBlank()) {
            systemRoot = trimToEmpty(System.getenv("WINDIR"));
        }
        if (!systemRoot.isBlank()) {
            Path candidate = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "powershell.exe";
    }

    private void openPath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                addTechnicalLog("WARN", "Desktop actions are not supported in this environment.");
                return;
            }
            Desktop.getDesktop().open(new File(path));
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Cannot open path: " + path + " | " + ex.getMessage());
        }
    }

    private Stage getStage() {
        if (rootPane.getScene() == null) {
            return null;
        }
        return (Stage) rootPane.getScene().getWindow();
    }

    private void runStartShortcut() {
        Platform.runLater(() -> {
            if (!startButton.isDisabled()) {
                onStart();
            }
        });
    }

    private void runCancelShortcut() {
        Platform.runLater(() -> {
            if (!cancelButton.isDisabled()) {
                onCancel();
            }
        });
    }

    private void runPreflightShortcut() {
        Platform.runLater(() -> {
            if (!preflightButton.isDisabled()) {
                onRunPreflight();
            }
        });
    }

    private void runMonitorShortcut() {
        Platform.runLater(this::onOpenMonitor);
    }

    private void runRefreshModelShortcut() {
        Platform.runLater(() -> {
            if (!refreshModelsButton.isDisabled()) {
                onRefreshModels();
            }
        });
    }

    private void runClearLogsShortcut() {
        Platform.runLater(this::onClearLogs);
    }

    private static String normalizeThemeName(String value) {
        String normalized = trimToEmpty(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "dark" -> THEME_DARK;
            case "dracula" -> THEME_DRACULA;
            default -> THEME_LIGHT;
        };
    }

    private static String themeClassFromName(String themeName) {
        return switch (normalizeThemeName(themeName)) {
            case THEME_DARK -> "theme-dark";
            case THEME_DRACULA -> "theme-dracula";
            default -> "theme-light";
        };
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeValue(ComboBox<String> box) {
        return trimToEmpty(box.getValue());
    }

    private void selectModel(String modelName) {
        String model = trimToEmpty(modelName);
        if (model.isBlank()) {
            return;
        }
        if (!modelField.getItems().contains(model)) {
            modelField.getItems().add(model);
        }
        modelField.getSelectionModel().select(model);
    }

    private static Integer valueOf(Spinner<Integer> spinner) {
        Integer value = spinner.getValue();
        return value == null ? 0 : value;
    }

    private static String toHex(javafx.scene.paint.Color color) {
        int r = (int) Math.round(color.getRed() * 255.0);
        int g = (int) Math.round(color.getGreen() * 255.0);
        int b = (int) Math.round(color.getBlue() * 255.0);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static javafx.scene.paint.Color parseColor(String raw, javafx.scene.paint.Color fallback) {
        String text = trimToEmpty(raw);
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return javafx.scene.paint.Color.web(text);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalizeSpeakerLabel(String value) {
        return trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private static boolean isMediaFile(Path path) {
        String file = trimToEmpty(path.getFileName().toString()).toLowerCase(Locale.ROOT);
        return file.endsWith(".mp3")
                || file.endsWith(".wav")
                || file.endsWith(".m4a")
                || file.endsWith(".aac")
                || file.endsWith(".flac")
                || file.endsWith(".ogg")
                || file.endsWith(".mp4")
                || file.endsWith(".mkv")
                || file.endsWith(".mov")
                || file.endsWith(".avi")
                || file.endsWith(".webm");
    }

    private static String detectLevel(String line) {
        String upper = trimToEmpty(line).toUpperCase(Locale.ROOT);
        if (upper.contains("[ERROR]") || upper.contains(" FAIL") || upper.startsWith("FAIL")) {
            return "ERROR";
        }
        if (upper.contains("[WARN]") || upper.contains(" WARN")) {
            return "WARN";
        }
        if (upper.contains("[OK]") || upper.contains(" SUCCESS")) {
            return "SUCCESS";
        }
        if (upper.contains("TRACEBACK") || upper.contains("[TRACE]") || upper.contains(" DEBUG")) {
            return "DEBUG";
        }
        return "INFO";
    }

    private static LogCategory detectCategory(String line) {
        String upper = trimToEmpty(line).toUpperCase(Locale.ROOT);
        if (upper.contains("TRACEBACK") || upper.contains("[TRACE]") || upper.contains("PYINSTALLERIMPORTERROR")) {
            return LogCategory.TECHNICAL;
        }
        return LogCategory.USER;
    }

    private static String normalizeLevel(String level) {
        String normalized = trimToEmpty(level).toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? "INFO" : normalized;
    }

    private static String withJobPrefix(String jobId, String message) {
        String safeMessage = trimToEmpty(message);
        if (jobId == null || jobId.isBlank()) {
            return safeMessage;
        }
        return "[" + shortJobId(jobId) + "] " + safeMessage;
    }

    private static String shortJobId(String jobId) {
        if (jobId == null) {
            return "-";
        }
        String cleaned = jobId.trim();
        return cleaned.length() <= 8 ? cleaned : cleaned.substring(0, 8);
    }

    private static String nowStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String trimTimestamp(String value) {
        String text = trimToEmpty(value);
        if (text.length() >= 19) {
            return text.substring(0, 19).replace('T', ' ');
        }
        return text;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return trimToEmpty(cursor.getMessage()).isBlank() ? cursor.toString() : cursor.getMessage();
    }

    private static final class JsonPreferences {
        private static final String ROOT_UI_KEY = "ui_preferences";

        private final ObjectMapper mapper;
        private Path path;
        private ObjectNode rootNode;
        private ObjectNode uiNode;
        private boolean loaded;

        private JsonPreferences(ObjectMapper mapper, Path initialPath) {
            this.mapper = mapper;
            this.path = initialPath;
            this.rootNode = mapper.createObjectNode();
            this.uiNode = mapper.createObjectNode();
            this.loaded = false;
        }

        private synchronized boolean setPath(Path newPath) {
            if (newPath == null) {
                return false;
            }
            Path normalized = newPath.toAbsolutePath().normalize();
            if (normalized.equals(path)) {
                return false;
            }
            path = normalized;
            loaded = false;
            ensureLoaded();
            return true;
        }

        private synchronized String get(String key, String defaultValue) {
            ensureLoaded();
            JsonNode node = uiNode.get(key);
            if (node == null || node.isNull()) {
                return defaultValue;
            }
            return node.asText(defaultValue);
        }

        private synchronized boolean getBoolean(String key, boolean defaultValue) {
            ensureLoaded();
            JsonNode node = uiNode.get(key);
            if (node == null || node.isNull()) {
                return defaultValue;
            }
            return node.asBoolean(defaultValue);
        }

        private synchronized int getInt(String key, int defaultValue) {
            ensureLoaded();
            JsonNode node = uiNode.get(key);
            if (node == null || node.isNull()) {
                return defaultValue;
            }
            return node.asInt(defaultValue);
        }

        private synchronized void put(String key, String value) {
            ensureLoaded();
            uiNode.put(key, value == null ? "" : value);
        }

        private synchronized void putBoolean(String key, boolean value) {
            ensureLoaded();
            uiNode.put(key, value);
        }

        private synchronized void putInt(String key, int value) {
            ensureLoaded();
            uiNode.put(key, value);
        }

        private synchronized void flush() {
            ensureLoaded();
            if (path == null) {
                return;
            }
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                Files.writeString(path, json, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Best effort only. Runtime should not fail on preference persistence issues.
            }
        }

        private void ensureLoaded() {
            if (loaded) {
                return;
            }

            ObjectNode loadedRoot = mapper.createObjectNode();
            try {
                if (path != null && Files.isRegularFile(path)) {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    JsonNode parsed = mapper.readTree(text);
                    if (parsed instanceof ObjectNode parsedObject) {
                        loadedRoot = parsedObject;
                    }
                }
            } catch (Exception ignored) {
                loadedRoot = mapper.createObjectNode();
            }

            JsonNode existingUiNode = loadedRoot.get(ROOT_UI_KEY);
            ObjectNode loadedUiNode;
            if (existingUiNode instanceof ObjectNode objectNode) {
                loadedUiNode = objectNode;
            } else {
                loadedUiNode = mapper.createObjectNode();
                loadedRoot.set(ROOT_UI_KEY, loadedUiNode);
            }

            rootNode = loadedRoot;
            uiNode = loadedUiNode;
            loaded = true;
        }
    }
}
