package com.transcribemate.v2.fx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.ModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;
import com.transcribemate.v2.fx.modules.registry.ModuleRegistry;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardStepSpec;
import com.transcribemate.v2.fx.wizard.ProjectWizardWindow;
import com.transcribemate.v2.fx.wizard.WizardValueStore;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Main JavaFX controller coordinating the full desktop workflow:
 * - shell/navigation state
 * - project workspace lifecycle
 * - module-specific UI defaults/constraints
 * - backend request/response/event bridge
 * - runtime bootstrap and diagnostics surfaces
 *
 * This class intentionally centralizes orchestration logic; module-specific behavior
 * is delegated through {@link ModuleComponent}.
 */
public class MainController {
    // Shared formatters, limits and preference keys used across UI + backend orchestration.
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DASHBOARD_ACTIVITY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_LOG_ENTRIES = 50000;
    private static final int BOOTSTRAP_LOG_MAX_CHARS = 200000;
    private static final String APP_NAME = "TranscribeMate";
    private static final String RUNTIME_READY_MARKER_NAME = "runtime-ready.json";
    private static final String WINDOW_TITLE_SUFFIX = "JavaFX + Python Backend";
    private static final String PREF_THEME = "ui.theme";
    private static final String PREF_UI_LANGUAGE = "ui.language";
    private static final String PREF_WORKSPACE_ROOT = "ui.workspace_root";
    private static final String PREF_AUTO_PREFLIGHT = "ui.auto_preflight";
    private static final String PREF_ACTIVE_MODULE = "ui.active_module";
    private static final String PREF_MODULE_PREFIX = "ui.module.";
    private static final String PREF_PROJECT_PREFIX = "ui.project.";
    private static final String PREF_MODULE_PRESETS_JSON = "presets_json";
    private static final String PREF_MODULE_SELECTED_PRESET = "selected_preset";
    private static final String PREF_PROJECT_AUTO_OUTPUT = "ui.projects.auto_output";
    private static final String PREF_SOURCE_MODE = "ui.source_mode";
    private static final String PREF_OUTPUT_MODE = "ui.output_mode";
    private static final String PREF_MODEL = "ui.model";
    private static final String PREF_USE_GPU = "ui.use_gpu";
    private static final String PREF_DIARIZATION_ENABLED = "ui.diarization.enabled";
    private static final String PREF_DIARIZATION_BACKEND = "ui.diarization.backend";
    private static final String PREF_DIARIZATION_ACCURACY_PROFILE = "ui.diarization.accuracy_profile";
    private static final String DIARIZATION_BACKEND_FAST = "local_cluster_fast";
    private static final String DIARIZATION_BACKEND_ACCURATE = "local_cluster_accurate";
    private static final String DIARIZATION_ACCURACY_LOW = "low";
    private static final String DIARIZATION_ACCURACY_BALANCED = "balanced";
    private static final String DIARIZATION_ACCURACY_HIGH = "high";
    private static final String DIARIZATION_ACCURACY_MAXIMUM = "maximum";
    private static final String THEME_LIGHT = "Light";
    private static final String THEME_DARK = "Dark";
    private static final String THEME_DRACULA = "Dracula";
    private static final String UI_LANG_EN = "en";
    private static final String UI_LANG_CS = "cs";
    private static final String MODULE_OFFLINE = "offline_transcribe";
    private static final String MODULE_YOUTUBE = "youtube_transcribe";
    private static final String MODULE_SPEAKER = "speaker_transcribe";
    private static final String MODULE_CONFERENCE = "conference_mode";
    private static final String MODULE_YOUTUBE_SUBS = "youtube_subtitles";
    private static final String MODULE_YOUTUBE_DUB = "youtube_dub";
    private static final String SECTION_DASHBOARD = "dashboard";
    private static final String SECTION_PROJECTS = "projects";
    private static final String SECTION_FILES = "files";
    private static final String SECTION_MODULES = "modules";
    private static final String PROJECT_SECTION_OVERVIEW = "overview";
    private static final String PROJECT_SECTION_WORKFLOW = "workflow";
    private static final String PROJECT_SECTION_FILES = "files";
    private static final String PROJECT_SECTION_JOBS = "jobs";
    private static final String PROJECT_SECTION_LOGS = "logs";
    private static final String PROJECT_SECTION_SETTINGS = "settings";
    private static final boolean ENFORCE_GUIDED_WIZARD = true;
    private static final boolean RUN_SOURCE_OUTPUT_READONLY_PREVIEW = true;
    private static final String WORKSPACE_DIR_NAME = "workspace";
    private static final String WORKSPACE_META_FILE = "workspace.json";
    private static final String WORKSPACE_PROJECTS_DIR = "projects";
    private static final long EDITOR_MAX_BYTES = 2L * 1024L * 1024L;
    private static final Set<String> UI_SCHEMA_TABS = Set.of("run", "advanced", "diarization", "logs", "jobs", "operations", "settings");
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
    private static final List<WizardActivityDefinition> WIZARD_ACTIVITIES = List.of(
            new WizardActivityDefinition("offline_transcribe", "Offline A/V Transcript", MODULE_OFFLINE, "local"),
            new WizardActivityDefinition("youtube_transcribe", "YouTube Transcript", MODULE_YOUTUBE, "youtube"),
            new WizardActivityDefinition("speaker_transcribe", "Speaker Transcript", MODULE_SPEAKER, "local"),
            new WizardActivityDefinition("conference_mode", "Conference Mode", MODULE_CONFERENCE, "local"),
            new WizardActivityDefinition("youtube_subtitles", "YouTube Subtitles", MODULE_YOUTUBE_SUBS, "youtube"),
            new WizardActivityDefinition("youtube_dub", "YouTube Dub", MODULE_YOUTUBE_DUB, "youtube")
    );
    // Global coordinator prevents multiple workspace windows/controllers from starting jobs concurrently.
    private static final Object GLOBAL_JOB_COORDINATOR_LOCK = new Object();
    private static final Set<MainController> REGISTERED_CONTROLLERS = ConcurrentHashMap.newKeySet();
    private static String globalJobOwnerControllerId = "";

    /**
     * Distinguishes user-facing logs from low-level diagnostics in split log views.
     */
    private enum LogCategory {
        USER,
        TECHNICAL
    }

    /**
     * Immutable in-memory log line used for filtering and multi-pane rendering.
     */
    private record LogEntry(LocalDateTime timestamp, String level, LogCategory category, String message) {
        String format() {
            String categoryLabel = category == LogCategory.USER ? "USER" : "TECH";
            return "[" + timestamp.format(TS_FMT) + "] [" + level + "] [" + categoryLabel + "] " + message;
        }
    }

    /**
     * Resolved bootstrap script location and related runtime paths.
     */
    private record RuntimeBootstrapTarget(
            Path scriptPath,
            Path backendRoot,
            Path appDataDir,
            Path progressFilePath,
            Path logFilePath
    ) {
        boolean isPowerShellScript() {
            String name = scriptPath == null ? "" : trimToEmpty(scriptPath.getFileName().toString()).toLowerCase(Locale.ROOT);
            return name.endsWith(".ps1");
        }
    }

    /**
     * Parsed progress token emitted by bootstrap script (TM_PROGRESS|...).
     */
    private record RuntimeBootstrapProgress(int percent, String message) {
    }

    /**
     * Effective per-module UI visibility schema used by tab/field toggles.
     */
    private record ModuleUiSchema(
            Set<String> showTabs,
            Set<String> showSections,
            Set<String> showFields,
            boolean jobsFilterModule
    ) {
        boolean allowsTab(String key) {
            return showTabs.isEmpty() || showTabs.contains(key);
        }

        boolean allowsSection(String key) {
            return showSections.isEmpty() || showSections.contains(key);
        }

        boolean allowsField(String key) {
            return showFields.isEmpty() || showFields.contains(key);
        }
    }

    /**
     * Persistent workspace project descriptor stored in workspace metadata.
     */
    private record ProjectWorkspace(
            String projectId,
            String name,
            String rootDir,
            String createdAt,
            String updatedAt,
            String lastOpenedAt
    ) {
        Path rootPath() {
            String candidate = trimToEmpty(rootDir);
            if (candidate.isBlank()) {
                return null;
            }
            try {
                return Path.of(candidate).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private record ActivitySnapshot(String label, LocalDateTime at) {
    }

    /**
     * Precomputed dashboard card payload built from project filesystem metadata.
     */
    private record ProjectDashboardCard(
            String projectId,
            String projectName,
            Path rootPath,
            long inputCount,
            long outputCount,
            long transcriptCount,
            long diskBytes,
            String lastActivity,
            String lastActivityAt,
            long sortEpoch
    ) {
    }

    /**
     * Wizard activity catalog entry mapped to module + source mode.
     */
    private record WizardActivityDefinition(String id, String title, String moduleId, String sourceMode) {
    }

    private record WizardYoutubeInput(String url, String quality, boolean playlist) {
    }

    private record WorkspaceMigrationFailure(String projectId, String message) {
    }

    private record WorkspaceMigrationResult(
            Path targetRoot,
            List<ProjectWorkspace> projects,
            String activeProjectId,
            List<WorkspaceMigrationFailure> failures
    ) {
    }

    private record ProjectWorkspaceHost(
            String projectId,
            MainController controller,
            Stage hostStage,
            Stage workspaceStage
    ) {
    }

    /**
     * Progress callback used during long-running workspace migration.
     */
    @FunctionalInterface
    private interface WorkspaceMigrationReporter {
        void report(int processed, int total, String message);
    }

    // FXML-injected controls (single source of truth for scene graph bindings).
    @FXML
    private BorderPane rootPane;

    @FXML
    private Button navDashboardButton;

    @FXML
    private Button navProjectsButton;

    @FXML
    private Button navFilesButton;

    @FXML
    private Button navModulesButton;

    @FXML
    private Button navOperationsButton;

    @FXML
    private Button navJobsButton;

    @FXML
    private Button navLogsButton;

    @FXML
    private Button navSettingsButton;

    @FXML
    private FlowPane workspaceNavRowPane;

    @FXML
    private VBox dashboardPane;

    @FXML
    private Label dashboardActiveProjectLabel;

    @FXML
    private Label dashboardProjectStatsLabel;

    @FXML
    private Button dashboardRefreshButton;

    @FXML
    private Button dashboardOpenProjectButton;

    @FXML
    private Button dashboardImportFilesButton;

    @FXML
    private Button dashboardOpenOutputFolderButton;

    @FXML
    private Button dashboardOpenWizardButton;

    @FXML
    private Label dashboardGalleryInfoLabel;

    @FXML
    private FlowPane dashboardProjectGalleryBox;

    @FXML
    private TableView<WorkspaceFileRow> dashboardRecentFilesTable;

    @FXML
    private TableColumn<WorkspaceFileRow, String> dashboardRecentPathColumn;

    @FXML
    private TableColumn<WorkspaceFileRow, String> dashboardRecentTypeColumn;

    @FXML
    private TableColumn<WorkspaceFileRow, String> dashboardRecentModifiedColumn;

    @FXML
    private Button dashboardOpenSelectedRecentButton;

    @FXML
    private Button dashboardOpenTimelineButton;

    @FXML
    private Label dashboardTimelineInfoLabel;

    @FXML
    private TextArea dashboardTimelineArea;

    @FXML
    private VBox projectsPane;

    @FXML
    private VBox filesPane;

    @FXML
    private VBox modulesPane;

    @FXML
    private VBox legacyModuleFlowCard;

    @FXML
    private StackPane workspaceStack;

    @FXML
    private Button projectCreateButton;

    @FXML
    private Button projectSelectButton;

    @FXML
    private Button projectDeleteButton;

    @FXML
    private Button projectOpenFolderButton;

    @FXML
    private Button projectRefreshButton;

    @FXML
    private Label activeProjectLabel;

    @FXML
    private TableView<ProjectRow> projectTable;

    @FXML
    private TableColumn<ProjectRow, String> projectNameColumn;

    @FXML
    private TableColumn<ProjectRow, String> projectStatusColumn;

    @FXML
    private TableColumn<ProjectRow, String> projectUpdatedColumn;

    @FXML
    private TableColumn<ProjectRow, String> projectSizeColumn;

    @FXML
    private TableColumn<ProjectRow, String> projectPathColumn;

    @FXML
    private Label filesActiveProjectLabel;

    @FXML
    private Button filesImportButton;

    @FXML
    private Button filesRefreshButton;

    @FXML
    private Button filesOpenSelectedButton;

    @FXML
    private Button filesAiSummaryButton;

    @FXML
    private Button filesOpenProjectFolderButton;

    @FXML
    private Button filesSaveEditorButton;

    @FXML
    private Button filesHistoryButton;

    @FXML
    private TextField filesSearchField;

    @FXML
    private CheckBox filesSearchContentBox;

    @FXML
    private Label filesDropHintLabel;

    @FXML
    private VBox filesGalleryHost;

    @FXML
    private FlowPane filesInputGalleryBox;

    @FXML
    private FlowPane filesOutputGalleryBox;

    @FXML
    private TableView<WorkspaceFileRow> projectFilesTable;

    @FXML
    private TableColumn<WorkspaceFileRow, String> projectFileRelativePathColumn;

    @FXML
    private TableColumn<WorkspaceFileRow, String> projectFileTypeColumn;

    @FXML
    private TableColumn<WorkspaceFileRow, String> projectFileSizeColumn;

    @FXML
    private TableColumn<WorkspaceFileRow, String> projectFileModifiedColumn;

    @FXML
    private Label fileEditorStatusLabel;

    @FXML
    private TextArea projectFileEditorArea;

    @FXML
    private TextArea projectSidecarPreviewArea;

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
    private Tab operationsTab;

    @FXML
    private Tab settingsTab;

    @FXML
    private VBox runSourceCard;

    @FXML
    private VBox runModuleContextCard;

    @FXML
    private Label runModuleContextLabel;

    @FXML
    private VBox runWizardCard;

    @FXML
    private VBox runOutputCard;

    @FXML
    private VBox runPreflightCard;

    @FXML
    private VBox simpleHintCard;

    @FXML
    private Button wizardSourceButton;

    @FXML
    private Button wizardOutputButton;

    @FXML
    private Button wizardPreflightButton;

    @FXML
    private Button wizardRunButton;

    @FXML
    private Button wizardNextButton;

    @FXML
    private Label wizardStateLabel;

    @FXML
    private Label preflightSummaryLabel;

    @FXML
    private Button applyPreflightFixButton;

    @FXML
    private TableView<PreflightCheckRow> preflightChecksTable;

    @FXML
    private TableColumn<PreflightCheckRow, String> preflightNameColumn;

    @FXML
    private TableColumn<PreflightCheckRow, String> preflightStatusColumn;

    @FXML
    private TableColumn<PreflightCheckRow, String> preflightMessageColumn;

    @FXML
    private TableColumn<PreflightCheckRow, String> preflightFixColumn;

    @FXML
    private Label sourceModeLabel;

    @FXML
    private Label localPathLabel;

    @FXML
    private Label youtubeUrlLabel;

    @FXML
    private Label qualityLabel;

    @FXML
    private Label outputModeLabel;

    @FXML
    private Label outputDirLabel;

    @FXML
    private Label outputPrefixLabel;

    @FXML
    private VBox advancedSubtitlesCard;

    @FXML
    private VBox advancedConferenceCard;

    @FXML
    private VBox settingsAppearanceCard;

    @FXML
    private VBox settingsRuntimeCard;

    @FXML
    private VBox settingsCoreCard;

    @FXML
    private Label settingsScopeLabel;

    @FXML
    private VBox settingsModuleFlowCard;

    @FXML
    private HBox settingsModuleScopeRow;

    @FXML
    private ComboBox<String> sourceModeBox;

    @FXML
    private TextField localPathField;

    @FXML
    private Button localPathBrowseButton;

    @FXML
    private TextField youtubeUrlField;

    @FXML
    private CheckBox playlistBox;

    @FXML
    private ComboBox<String> qualityBox;

    @FXML
    private TextField outputDirField;

    @FXML
    private Button outputDirBrowseButton;

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
    private Label settingsSourceLangLabel;

    @FXML
    private ComboBox<String> summaryLangBox;

    @FXML
    private Label settingsSummaryLangLabel;

    @FXML
    private CheckBox summaryAiBox;

    @FXML
    private Label settingsSummaryAiLabel;

    @FXML
    private ComboBox<String> summaryModelTierBox;

    @FXML
    private Label settingsSummaryModelTierLabel;

    @FXML
    private ComboBox<String> targetLangBox;

    @FXML
    private Label settingsTargetLangLabel;

    @FXML
    private Spinner<Integer> batchSizeSpinner;

    @FXML
    private Label settingsBatchSizeLabel;

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
    private Label settingsWhisperModelLabel;

    @FXML
    private HBox settingsModelOptionsBox;

    @FXML
    private Label settingsTextOptionsLabel;

    @FXML
    private HBox settingsTextOptionsBox;

    @FXML
    private Label settingsSplitMinutesLabel;

    @FXML
    private Label settingsSpeakerModuleHintLabel;

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
    private ComboBox<String> diarizationAccuracyBox;

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
    private TableView<SpeakerProfileRow> speakerProfilesTable;

    @FXML
    private TableColumn<SpeakerProfileRow, String> speakerLabelColumn;

    @FXML
    private TableColumn<SpeakerProfileRow, String> speakerNameColumn;

    @FXML
    private Button applySpeakerMappingButton;

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
    private CheckBox logsErrorsOnlyBox;

    @FXML
    private CheckBox logsPauseAutoscrollBox;

    @FXML
    private CheckBox logsLinkSelectedJobBox;

    @FXML
    private Label logsJobFilterLabel;

    @FXML
    private Label logsStreamInfoLabel;

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
    private TextField jobsSearchField;

    @FXML
    private ComboBox<String> jobsStatusFilterBox;

    @FXML
    private CheckBox jobsActiveModuleOnlyBox;

    @FXML
    private Label jobsInfoLabel;

    @FXML
    private Label operationsStatusLabel;

    @FXML
    private Label operationsMetricsLabel;

    @FXML
    private Button operationsRefreshDiagnosticsButton;

    @FXML
    private Button operationsOpenMonitorButton;

    @FXML
    private TableView<JobRow> operationsJobsTable;

    @FXML
    private TableColumn<JobRow, String> operationsJobIdColumn;

    @FXML
    private TableColumn<JobRow, String> operationsJobStatusColumn;

    @FXML
    private TableColumn<JobRow, String> operationsJobModeColumn;

    @FXML
    private TableColumn<JobRow, String> operationsJobCreatedColumn;

    @FXML
    private TextArea operationsLogsArea;

    @FXML
    private TextArea operationsDiagnosticsArea;

    @FXML
    private Button startButton;

    @FXML
    private Button cancelButton;

    @FXML
    private Button preflightButton;

    @FXML
    private FlowPane actionRowPane;

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
    private Button replayJobButton;

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
    private HBox moduleSelectionRow;

    @FXML
    private FlowPane moduleQuickActionsPane;

    @FXML
    private ComboBox<String> moduleSwitcherBox;

    @FXML
    private Button modulePreviousButton;

    @FXML
    private ComboBox<String> themeBox;

    @FXML
    private ComboBox<String> uiLanguageBox;

    @FXML
    private ComboBox<String> settingsModuleBox;

    @FXML
    private Label settingsScopeValueLabel;

    @FXML
    private Button settingsResetProjectModuleButton;

    @FXML
    private Button settingsSaveGlobalDefaultsButton;

    @FXML
    private TextField settingsWorkspaceRootField;

    @FXML
    private Button settingsChangeWorkspaceRootButton;

    @FXML
    private Label settingsWorkspaceRootInfoLabel;

    @FXML
    private Button settingsLoadModuleButton;

    @FXML
    private Button settingsResetModuleDefaultsButton;

    @FXML
    private HBox settingsPresetRow;

    @FXML
    private ComboBox<String> settingsPresetBox;

    @FXML
    private Button settingsSavePresetButton;

    @FXML
    private Button settingsLoadPresetButton;

    @FXML
    private Button settingsDeletePresetButton;

    @FXML
    private TextField settingsSearchField;

    @FXML
    private Label moduleFlowTitleLabel;

    @FXML
    private Label moduleFlowLabel;

    @FXML
    private Button moduleFlowActionButton;

    // Core controller services and observable collections backing table/list views.
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonPreferences preferences = new JsonPreferences(mapper, resolveRuntimeAppDataDir().resolve("config.json"));
    private final ObservableList<JobRow> jobRows = FXCollections.observableArrayList();
    private final FilteredList<JobRow> filteredJobRows = new FilteredList<>(jobRows, row -> true);
    private final Map<String, JobRow> jobsById = new LinkedHashMap<>();
    private final ObservableList<ProjectRow> projectRows = FXCollections.observableArrayList();
    private final Map<String, ProjectWorkspace> projectsById = new LinkedHashMap<>();
    private final ObservableList<WorkspaceFileRow> dashboardRecentFileRows = FXCollections.observableArrayList();
    private final ObservableList<WorkspaceFileRow> workspaceFileRows = FXCollections.observableArrayList();
    private final FilteredList<WorkspaceFileRow> filteredWorkspaceFileRows = new FilteredList<>(workspaceFileRows, row -> true);
    private final ObservableList<PreflightCheckRow> preflightCheckRows = FXCollections.observableArrayList();
    private final ObservableList<SpeakerProfileRow> speakerProfiles = FXCollections.observableArrayList();
    private final ObservableList<ConferenceFileRow> conferenceFiles = FXCollections.observableArrayList();
    private final List<LogEntry> allLogs = new ArrayList<>();
    private final Map<String, ModuleComponent> moduleComponents = ModuleRegistry.byId();
    private final Map<String, String> moduleLabelsToId = ModuleRegistry.labelToId();
    private final Map<String, ModuleUiSchema> moduleUiSchemas = new LinkedHashMap<>();
    private final ModuleComponent.ModuleUiContext moduleUiContext = new ControllerModuleUiContext();
    private final String controllerInstanceId = UUID.randomUUID().toString();
    private final Map<String, ProjectWorkspaceHost> openProjectWorkspaceHosts = new LinkedHashMap<>();

    // Mutable runtime/session state.
    private BackendClient backendClient;
    private String currentJobId;
    private String lastOutputDir;
    private String appDataDir;
    private String appVersion;
    private String currentTheme = "";
    private String currentUiLanguage = UI_LANG_EN;
    private String activeProjectId = "";
    private Path activeProjectRoot;
    private Path activeEditedFilePath;
    private Path lastDiarizationSidecarPath;
    private WorkspaceFileRow selectedWorkspaceFileRow;
    private String activeSummaryOperationId = "";
    private String activeSummaryOutputPath = "";
    private String loadedEditedFileText = "";
    private boolean editorDirty;
    private boolean editorAutoSaving;
    private long editorLastSaveMillis;
    private boolean lastPreflightOk;
    private long lastPreflightMillis;
    private boolean themeInitializing;
    private boolean restoringPreferences;
    private boolean runtimeBootstrapRunning;
    private boolean settingsModuleSelectorSync;
    private boolean moduleSwitcherSync;
    private String moduleFlowActionKey = "";
    private String activeModule = MODULE_OFFLINE;
    private String previousModule = "";
    private String selectedJobLogFilter = "";
    private String activeSection = SECTION_DASHBOARD;
    private String wizardActivityId = "";
    private boolean wizardSourceImported;
    private boolean wizardYoutubeDownloadConfirmed;
    private boolean wizardSourceUpdateInProgress;
    private boolean settingsAppearanceAllowed = true;
    private boolean settingsRuntimeAllowed = true;
    private boolean settingsCoreAllowed = true;
    private boolean settingsModuleFlowAllowed = true;
    private boolean settingsModuleScopeAllowed = true;
    private boolean settingsPresetRowAllowed = true;
    private boolean workspaceMigrationRunning;
    private long etaAnchorMillis = -1L;
    private double etaAnchorPercent = -1.0;

    // Auxiliary windows/timers detached from base FXML scene graph.
    private Timeline jobsRefreshTimeline;
    private PauseTransition editorAutosavePause;
    private SystemMonitorWindow monitorWindow;
    private JobProgressWindow jobProgressWindow;
    private Stage filesSummaryStage;
    private Label filesSummarySelectedFileLabel;
    private ComboBox<String> filesSummaryLangPicker;
    private ComboBox<String> filesSummaryTierPicker;
    private Label filesSummaryStatusLabel;
    private ProgressBar filesSummaryProgressBar;
    private TextArea filesSummaryLogArea;
    private Button filesSummaryStartButton;
    private Button filesSummaryOpenOutputButton;
    private Stage projectWorkspaceStage;
    private Label projectWorkspaceTitleLabel;
    private Button projectWorkspacePreflightButton;
    private Button projectWorkspaceRunWizardButton;
    private Button projectWorkspaceSectionOverviewButton;
    private Button projectWorkspaceSectionWorkflowButton;
    private Button projectWorkspaceSectionFilesButton;
    private Button projectWorkspaceSectionJobsButton;
    private Button projectWorkspaceSectionLogsButton;
    private Button projectWorkspaceSectionSettingsButton;
    private StackPane projectWorkspaceContentHost;
    private VBox projectWorkspaceOverviewPane;
    private Label projectWorkspaceOverviewProjectLabel;
    private Label projectWorkspaceOverviewStatsLabel;
    private Label projectWorkspaceOverviewActivityLabel;
    private Label projectWorkspaceOverviewWorkflowLabel;
    private Button projectWorkspaceOverviewRunWizardButton;
    private String activeProjectWorkspaceSection = PROJECT_SECTION_OVERVIEW;
    private boolean controllerRunning;
    private boolean hostedProjectWorkspaceController;
    private Stage launcherMainStage;

    // Single backend event bridge registered/unregistered during controller lifecycle.
    private final Consumer<JsonNode> eventListener = this::handleBackendEvent;

    /**
     * JavaFX lifecycle entrypoint invoked after FXML injection.
     *
     * Order matters here: UI controls/tables/navigation are initialized first,
     * then persisted preferences and module context are restored on top.
     */
    @FXML
    private void initialize() {
        AppFileLogger.initialize();
        setupCombosAndDefaults();
        setupTables();
        setupFilters();
        setupEditorBehaviors();
        setupFileDropImport();
        setupModuleNavigation();
        setupShellNavigation();
        setupThemeSelector();
        setupUiLanguageSelector();
        setupSettingsModuleSelector();
        setupModuleSwitcher();
        initializeWorkspace();
        applyGuidedWizardUi();
        applyRunReadOnlyPreviewMode();
        applyUiTestShellOverrides();

        sourceModeBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateSourceModeUi();
            onWizardSourceFieldManuallyChanged("source_mode");
            invalidatePreflightState();
            updateWizardState();
        });
        if (localPathField != null) {
            localPathField.textProperty().addListener((obs, oldVal, newVal) -> {
                onWizardSourceFieldManuallyChanged("local_path");
                invalidatePreflightState();
                updateWizardState();
            });
        }
        if (youtubeUrlField != null) {
            youtubeUrlField.textProperty().addListener((obs, oldVal, newVal) -> {
                onWizardSourceFieldManuallyChanged("youtube_url");
                invalidatePreflightState();
                updateWizardState();
            });
        }
        if (outputDirField != null) {
            outputDirField.textProperty().addListener((obs, oldVal, newVal) -> {
                invalidatePreflightState();
                updateWizardState();
            });
        }
        translateSubtitlesBox.selectedProperty().addListener((obs, oldVal, newVal) -> updateTranslationUi());
        jobHistoryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> onJobSelectionChanged(newItem));

        statusLabel.setText("Ready");
        stepLabel.setText("-");
        progressBar.setProgress(0.0);
        resetEtaDisplay();
        setAppVersion(resolveLocalVersion());

        restoreUiPreferences();
        updateSourceModeUi();
        activateModule(activeModule, false);
        registerPreferenceListeners();
        registerControllerInstance();
        setRunning(false);
        refreshProfilePreview();
        updateSpeakerMappingButtonState(false);
        if (replayJobButton != null) {
            replayJobButton.setDisable(true);
        }
        // Always open shell on Dashboard after startup initialization.
        showShellSection(SECTION_DASHBOARD);
    }

    /**
     * Attaches backend client, wires event listener and pulls capability/path data.
     */
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
                    updateModuleSchemasFromCapabilities(result.path("module_components"));
                    activateModule(activeModule, false, true);
                    addUserLog(
                            "INFO",
                            backendVersion.isBlank()
                                    ? "Backend connected."
                                    : "Backend connected (version " + backendVersion + ")."
                    );
                    refreshOperationsDiagnostics();
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
                                activateModule(activeModule, false, true);
                            }
                        } catch (Exception ignored) {
                            // Keep default config path fallback.
                        }
                    }
                    if (!isHostedProjectWorkspaceController()) {
                        reloadWorkspaceFromCurrentAppDataDir();
                    }
                    addUserLog("INFO", "App data: " + appDataDir);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> addTechnicalLog("WARN", "Could not fetch app data path: " + ex.getMessage()));
                    return null;
                });

        refreshJobsSilently();
    }

    /**
     * Marks this controller as hosted in a detached project workspace window.
     */
    private void configureHostedProjectWorkspaceController(Stage launcherStage) {
        hostedProjectWorkspaceController = true;
        launcherMainStage = launcherStage;
    }

    private boolean isLauncherController() {
        return !hostedProjectWorkspaceController;
    }

    private boolean isHostedProjectWorkspaceController() {
        return hostedProjectWorkspaceController;
    }

    /**
     * Registers controller for global run-lock broadcasts across all windows.
     */
    private void registerControllerInstance() {
        REGISTERED_CONTROLLERS.add(this);
        applyGlobalRunLockState();
    }

    /**
     * Unregisters controller and releases lock ownership when this controller exits.
     */
    private void unregisterControllerInstance() {
        REGISTERED_CONTROLLERS.remove(this);
        releaseGlobalRunLockIfOwned();
        broadcastGlobalRunLockState();
    }

    private boolean isGlobalRunLockedByOtherController() {
        synchronized (GLOBAL_JOB_COORDINATOR_LOCK) {
            return !globalJobOwnerControllerId.isBlank()
                    && !Objects.equals(globalJobOwnerControllerId, controllerInstanceId);
        }
    }

    private boolean ownsGlobalRunLock() {
        synchronized (GLOBAL_JOB_COORDINATOR_LOCK) {
            return Objects.equals(globalJobOwnerControllerId, controllerInstanceId);
        }
    }

    /**
     * Claims global run lock if no other controller currently owns it.
     */
    private boolean acquireGlobalRunLock() {
        synchronized (GLOBAL_JOB_COORDINATOR_LOCK) {
            if (!globalJobOwnerControllerId.isBlank()
                    && !Objects.equals(globalJobOwnerControllerId, controllerInstanceId)) {
                return false;
            }
            globalJobOwnerControllerId = controllerInstanceId;
        }
        broadcastGlobalRunLockState();
        return true;
    }

    /**
     * Releases global run lock only when lock is owned by this controller instance.
     */
    private void releaseGlobalRunLockIfOwned() {
        boolean changed = false;
        synchronized (GLOBAL_JOB_COORDINATOR_LOCK) {
            if (Objects.equals(globalJobOwnerControllerId, controllerInstanceId)) {
                globalJobOwnerControllerId = "";
                changed = true;
            }
        }
        if (changed) {
            broadcastGlobalRunLockState();
        }
    }

    /**
     * Pushes lock-state refresh to all active controllers on JavaFX thread.
     */
    private static void broadcastGlobalRunLockState() {
        Platform.runLater(() -> {
            for (MainController controller : new ArrayList<>(REGISTERED_CONTROLLERS)) {
                controller.applyGlobalRunLockState();
            }
        });
    }

    private void applyGlobalRunLockState() {
        setRunning(controllerRunning);
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

    public void startMandatoryRuntimeSetupOnLaunch(RuntimeInstallChecker.RuntimeCheckResult runtimeCheck) {
        if (runtimeBootstrapRunning) {
            return;
        }

        RuntimeInstallChecker.RuntimeCheckResult check = runtimeCheck;
        if (check == null) {
            check = RuntimeInstallChecker.repairRequired(
                    resolveRuntimeAppDataDir(),
                    "runtime_check_missing",
                    "Runtime check state is unavailable.",
                    List.of()
            );
        }

        boolean firstRunRequired = check.isFirstRunRequired();
        RuntimeBootstrapTarget target = resolveRuntimeBootstrapTarget();
        if (target == null) {
            addTechnicalLog(
                    "ERROR",
                    "Mandatory runtime setup/repair is unavailable. Missing bootstrap_runtime.ps1 or backend bundle."
            );
            if (firstRunRequired) {
                runMandatoryRuntimeRollbackAndExit(check.appDataDir(), "Mandatory first-launch setup cannot start.");
                return;
            }
            Platform.exit();
            return;
        }

        statusLabel.setText(firstRunRequired ? "Runtime setup required" : "Runtime repair required");
        stepLabel.setText(firstRunRequired ? "first launch" : "runtime repair");
        progressBar.setProgress(0.0);
        resetEtaDisplay();
        addUserLog(
                "WARN",
                firstRunRequired
                        ? "First launch requires online runtime setup before the app can be used."
                        : "Runtime check failed. Mandatory runtime repair is required before the app can be used."
        );

        ButtonType startButton = new ButtonType(
                firstRunRequired ? "Start setup" : "Start repair",
                ButtonBar.ButtonData.OK_DONE
        );
        ButtonType exitButton = new ButtonType("Exit application", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert runtimeDialog = new Alert(Alert.AlertType.CONFIRMATION);
        runtimeDialog.setTitle(firstRunRequired ? "First Launch Setup Required" : "Runtime Repair Required");
        runtimeDialog.setHeaderText(firstRunRequired
                ? "Online runtime setup is required"
                : "Runtime installation is incomplete and must be repaired");
        StringBuilder body = new StringBuilder();
        body.append(firstRunRequired
                ? "Before TranscribeMate can run, it must verify and install required runtime components.\n\n"
                : "TranscribeMate detected missing runtime components. Repair is required before continuing.\n\n");
        body.append("What will be checked and installed:\n")
                .append("- Managed runtime folder\n")
                .append("- Managed Python runtime and backend dependencies\n")
                .append("- Default AI model pack (Whisper large-v3, Helsinki-NLP/opus-mt-en-cs)\n")
                .append("- FFmpeg tools\n");
        if (AppRuntimePaths.isMac()) {
            body.append("- CPU-focused runtime profile for Apple Silicon (CUDA/NVIDIA path disabled)\n\n");
        } else {
            body.append("- NVIDIA GPU detection and CUDA Torch provisioning (fallback to CPU runtime)\n\n");
        }
        body.append("Runtime location:\n")
                .append(target.appDataDir())
                .append("\n");

        if (!trimToEmpty(check.reasonMessage()).isBlank()) {
            body.append("\nDetected reason: ").append(check.reasonMessage()).append("\n");
        }

        if (!check.missingArtifacts().isEmpty()) {
            body.append("\nMissing or invalid artifacts:\n");
            for (String item : check.missingArtifacts()) {
                String normalized = trimToEmpty(item);
                if (!normalized.isBlank()) {
                    body.append("- ").append(normalized).append("\n");
                }
            }
        }

        body.append(
                "\nYou can cancel later during setup. If setup is not completed, the application will close."
        );
        runtimeDialog.setContentText(body.toString());
        runtimeDialog.getButtonTypes().setAll(startButton, exitButton);
        Stage owner = getStage();
        if (owner != null) {
            runtimeDialog.initOwner(owner);
        }
        applyThemeToDialog(runtimeDialog);

        Optional<ButtonType> choice = runtimeDialog.showAndWait();
        if (choice.isPresent() && choice.get() == startButton) {
            showRuntimeBootstrapWindow(
                    target,
                    true,
                    !firstRunRequired,
                    firstRunRequired
            );
            return;
        }

        addUserLog("WARN", "Mandatory runtime setup/repair was declined by user.");
        if (firstRunRequired) {
            runMandatoryRuntimeRollbackAndExit(target.appDataDir(), "Mandatory first-launch setup was declined.");
            return;
        }
        Platform.exit();
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
                () -> Platform.runLater(() -> selectModule(operationsTab != null ? operationsTab : logsTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT5, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(settingsTab))
        );
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT6, KeyCombination.CONTROL_DOWN),
                () -> Platform.runLater(() -> selectModule(operationsTab != null ? operationsTab : settingsTab))
        );
    }

    public void dispose() {
        controllerRunning = false;
        saveActiveModuleState();
        if (isLauncherController()) {
            closeAllHostedProjectWorkspaceWindows();
        }
        if (jobsRefreshTimeline != null) {
            jobsRefreshTimeline.stop();
            jobsRefreshTimeline = null;
        }
        closeProjectWorkspaceWindow();
        if (jobProgressWindow != null) {
            jobProgressWindow.close();
            jobProgressWindow = null;
        }
        if (filesSummaryStage != null) {
            filesSummaryStage.hide();
            filesSummaryStage = null;
        }
        if (monitorWindow != null) {
            monitorWindow.close();
            monitorWindow = null;
        }
        if (backendClient != null) {
            backendClient.removeEventListener(eventListener);
        }
        unregisterControllerInstance();
    }

    @FXML
    private void onToggleSimpleMode() {
        // Deprecated UI toggle retained only for backward compatibility.
    }

    @FXML
    private void onNavDashboard() {
        showShellSection(SECTION_DASHBOARD);
    }

    @FXML
    private void onNavProjects() {
        showShellSection(SECTION_PROJECTS);
        refreshProjectsView();
    }

    @FXML
    private void onNavFiles() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        openProjectWindowForProject(activeProjectId, false, PROJECT_SECTION_FILES);
    }

    @FXML
    private void onNavModules() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        openProjectWindowForProject(activeProjectId, false, PROJECT_SECTION_WORKFLOW);
    }

    @FXML
    private void onNavOperations() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        openProjectWindowForProject(activeProjectId, false, PROJECT_SECTION_JOBS);
    }

    @FXML
    private void onNavJobs() {
        onNavOperations();
    }

    @FXML
    private void onNavLogs() {
        onNavOperations();
    }

    @FXML
    private void onNavSettings() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        openProjectWindowForProject(activeProjectId, false, PROJECT_SECTION_SETTINGS);
    }

    @FXML
    private void onRefreshDashboard() {
        refreshDashboardData();
        addUserLog("INFO", "Dashboard refreshed.");
    }

    @FXML
    private void onDashboardOpenProjectFolder() {
        onOpenProjectFolder();
    }

    @FXML
    private void onDashboardImportFiles() {
        onImportFilesToProject();
    }

    @FXML
    private void onDashboardOpenOutputFolder() {
        if (activeProjectRoot == null) {
            addUserLog("WARN", "No active project selected.");
            return;
        }
        openPath(activeProjectRoot.resolve("output").toString());
    }

    @FXML
    private void onDashboardOpenWizardForActive() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        openProjectWindowForProject(activeProjectId, true, PROJECT_SECTION_WORKFLOW);
    }

    @FXML
    private void onDashboardOpenTimeline() {
        if (activeProjectRoot == null) {
            addUserLog("WARN", "No active project selected.");
            return;
        }
        Path timelinePath = activeProjectRoot.resolve("jobs").resolve("timeline.jsonl");
        if (!Files.isRegularFile(timelinePath)) {
            addUserLog("WARN", "Timeline file does not exist yet.");
            return;
        }
        openPath(timelinePath.toString());
    }

    @FXML
    private void onDashboardOpenSelectedRecentFile() {
        WorkspaceFileRow selected = dashboardRecentFilesTable == null
                ? null
                : dashboardRecentFilesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select recent output file first.");
            return;
        }
        openPath(selected.getAbsolutePath());
    }

    @FXML
    private void onCreateProject() {
        String projectNameInput = resolveUiTestProjectNameOverride();
        if (projectNameInput == null) {
            TextInputDialog dialog = new TextInputDialog("");
            dialog.setTitle("Create Project");
            dialog.setHeaderText("Create new workspace project");
            dialog.setContentText("Project name:");
            Stage owner = getStage();
            if (owner != null) {
                dialog.initOwner(owner);
            }
            applyThemeToDialog(dialog);
            Optional<String> result = dialog.showAndWait();
            if (result.isEmpty()) {
                return;
            }
            projectNameInput = result.get();
        }

        String projectName = sanitizeProjectName(projectNameInput);
        if (projectName.isBlank()) {
            addUserLog("WARN", "Project name cannot be empty.");
            return;
        }

        String projectId = buildProjectId(projectName);
        Path projectRoot = workspaceProjectsRootPath().resolve(projectId);

        try {
            ensureProjectStructure(projectRoot);
            String now = nowStamp();
            ProjectWorkspace project = new ProjectWorkspace(
                    projectId,
                    projectName,
                    projectRoot.toString(),
                    now,
                    now,
                    now
            );
            projectsById.put(projectId, project);
            initializeProjectSettingsFromGlobalDefaults(projectId);
            setActiveProject(projectId, true, false);
            refreshProjectsView();
            refreshProjectFiles();
            if (!AppRuntimePaths.isUiTestMode()) {
                openProjectWindowForProject(projectId, false, PROJECT_SECTION_OVERVIEW);
            }
            addUserLog("SUCCESS", "Project created: " + projectName);
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Failed to create project: " + ex.getMessage());
            addUserLog("ERROR", "Project creation failed.");
        }
    }

    private String resolveUiTestProjectNameOverride() {
        if (!AppRuntimePaths.isUiTestMode()) {
            return null;
        }
        String value = trimToEmpty(System.getProperty("tm.uiTestProjectName", ""));
        return value.isBlank() ? null : value;
    }

    @FXML
    private void onSelectProject() {
        ProjectRow selected = projectTable == null ? null : projectTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select project first.");
            return;
        }
        setActiveProject(selected.getProjectId(), true, true);
        refreshProjectsView();
        refreshProjectFiles();
        openProjectWindowForProject(selected.getProjectId(), false, PROJECT_SECTION_OVERVIEW);
    }

    private boolean ensureWorkflowProjectReady() {
        if (activeProjectRoot != null && !trimToEmpty(activeProjectId).isBlank()) {
            return true;
        }
        addUserLog("WARN", "Project is required. Select or create a project first.");
        showShellSection(SECTION_DASHBOARD);
        return false;
    }

    private void openProjectWizard(String projectId) {
        openProjectWindowForProject(projectId, true, PROJECT_SECTION_WORKFLOW);
    }

    private void openProjectWindowForProject(String projectId, boolean runWizard, String sectionId) {
        String normalized = trimToEmpty(projectId);
        if (normalized.isBlank() || !projectsById.containsKey(normalized)) {
            addUserLog("WARN", "Project does not exist anymore.");
            reloadWorkspaceFromCurrentAppDataDir();
            return;
        }

        setActiveProject(normalized, true, !Objects.equals(activeProjectId, normalized));
        refreshProjectsView();
        refreshProjectFiles();
        ProjectWorkspace project = projectsById.get(normalized);
        String projectName = project == null ? normalized : project.name();

        if (!isLauncherController()) {
            openProjectWorkspaceWindow();
            if (!trimToEmpty(sectionId).isBlank()) {
                showProjectWorkspaceSection(sectionId);
            }
            if (runWizard) {
                addUserLog("INFO", "Workflow wizard opened for project: " + projectName);
                onRunProjectWizard();
            }
            return;
        }

        cleanupHostedProjectWorkspaceWindows();
        ProjectWorkspaceHost existing = openProjectWorkspaceHosts.get(normalized);
        if (existing != null
                && existing.workspaceStage() != null
                && existing.workspaceStage().isShowing()) {
            existing.workspaceStage().toFront();
            existing.workspaceStage().requestFocus();
            if (!trimToEmpty(sectionId).isBlank()) {
                existing.controller().showProjectWorkspaceSection(sectionId);
            }
            if (runWizard) {
                existing.controller().onRunProjectWizard();
            } else {
                addUserLog("INFO", "Project already open, focused existing window: " + projectName);
            }
            return;
        }

        Stage launcherStage = getMainStage();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/transcribemate/v2/fx/main-view.fxml"));
            Parent hostRoot = loader.load();
            MainController hostedController = loader.getController();

            Scene hostScene = new Scene(hostRoot, 1460, 920);
            if (rootPane != null && rootPane.getScene() != null) {
                hostScene.getStylesheets().setAll(rootPane.getScene().getStylesheets());
            } else {
                hostScene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());
            }

            Stage hostStage = new Stage();
            if (launcherStage != null) {
                hostStage.initOwner(launcherStage);
            }
            hostStage.setScene(hostScene);
            hostStage.setTitle(buildAppTitleText() + " - Project Host");

            hostedController.configureHostedProjectWorkspaceController(launcherStage);
            hostedController.initBackend(backendClient);
            hostedController.setActiveProject(normalized, true, false);
            hostedController.refreshProjectsView();
            hostedController.refreshProjectFiles();
            hostedController.openProjectWorkspaceWindow();

            Stage workspaceStage = hostedController.projectWorkspaceStage;
            if (workspaceStage == null) {
                hostedController.dispose();
                hostStage.close();
                addTechnicalLog("ERROR", "Failed to create project workspace window for " + projectName + ".");
                return;
            }

            workspaceStage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event ->
                    Platform.runLater(() -> unregisterHostedProjectWorkspaceWindow(normalized))
            );

            openProjectWorkspaceHosts.put(normalized, new ProjectWorkspaceHost(normalized, hostedController, hostStage, workspaceStage));

            if (!trimToEmpty(sectionId).isBlank()) {
                hostedController.showProjectWorkspaceSection(sectionId);
            }
            workspaceStage.toFront();
            workspaceStage.requestFocus();

            if (runWizard) {
                addUserLog("INFO", "Workflow wizard opened for project: " + projectName);
                hostedController.onRunProjectWizard();
            } else {
                addUserLog("INFO", "Project opened: " + projectName);
            }
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Failed to open project window: " + rootMessage(ex));
            addUserLog("ERROR", "Cannot open project window.");
        }
    }

    private void cleanupHostedProjectWorkspaceWindows() {
        if (openProjectWorkspaceHosts.isEmpty()) {
            return;
        }
        List<String> closedProjectIds = new ArrayList<>();
        for (Map.Entry<String, ProjectWorkspaceHost> entry : openProjectWorkspaceHosts.entrySet()) {
            ProjectWorkspaceHost host = entry.getValue();
            Stage workspaceStage = host == null ? null : host.workspaceStage();
            if (workspaceStage == null || !workspaceStage.isShowing()) {
                closedProjectIds.add(entry.getKey());
            }
        }
        for (String projectId : closedProjectIds) {
            unregisterHostedProjectWorkspaceWindow(projectId);
        }
    }

    private void unregisterHostedProjectWorkspaceWindow(String projectId) {
        String normalized = trimToEmpty(projectId);
        if (normalized.isBlank()) {
            return;
        }
        ProjectWorkspaceHost host = openProjectWorkspaceHosts.remove(normalized);
        if (host == null) {
            return;
        }
        MainController hostedController = host.controller();
        if (hostedController != null) {
            hostedController.dispose();
        }
        Stage hostStage = host.hostStage();
        if (hostStage != null) {
            hostStage.close();
        }
    }

    private void closeAllHostedProjectWorkspaceWindows() {
        if (openProjectWorkspaceHosts.isEmpty()) {
            return;
        }
        List<String> projectIds = new ArrayList<>(openProjectWorkspaceHosts.keySet());
        for (String projectId : projectIds) {
            unregisterHostedProjectWorkspaceWindow(projectId);
        }
    }

    private void openProjectWorkspaceWindow() {
        if (modulesPane == null || workspaceStack == null) {
            return;
        }
        if (!ensureWorkflowProjectReady()) {
            return;
        }

        if (projectWorkspaceStage != null) {
            updateProjectWorkspaceWindowHeader();
            refreshProjectWorkspaceOverview();
            if (!projectWorkspaceStage.isShowing()) {
                projectWorkspaceStage.show();
            }
            projectWorkspaceStage.toFront();
            projectWorkspaceStage.requestFocus();
            return;
        }

        if (modulesPane.getParent() instanceof Pane parentPane) {
            parentPane.getChildren().remove(modulesPane);
        }
        if (filesPane != null && filesPane.getParent() instanceof Pane parentPane) {
            parentPane.getChildren().remove(filesPane);
        }

        BorderPane windowRoot = new BorderPane();
        windowRoot.getStyleClass().add("project-shell");
        if (rootPane != null) {
            for (String styleClass : rootPane.getStyleClass()) {
                if (!windowRoot.getStyleClass().contains(styleClass)) {
                    windowRoot.getStyleClass().add(styleClass);
                }
            }
        }

        HBox header = new HBox(8.0);
        header.setPadding(new Insets(10.0, 12.0, 10.0, 12.0));
        header.getStyleClass().addAll("card-pane", "app-titlebar");

        Label title = new Label("Project workspace");
        title.getStyleClass().add("section-title");
        projectWorkspaceTitleLabel = title;

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button runWizardButton = new Button("Run wizard");
        runWizardButton.getStyleClass().add("accent-btn");
        runWizardButton.setOnAction(event -> onRunProjectWizard());
        projectWorkspaceRunWizardButton = runWizardButton;

        Button runPreflightButton = new Button("Run preflight");
        runPreflightButton.setOnAction(event -> onRunPreflight());
        projectWorkspacePreflightButton = runPreflightButton;

        Button monitorButtonLocal = new Button("System monitor");
        monitorButtonLocal.getStyleClass().add("accent-btn");
        monitorButtonLocal.setOnAction(event -> onOpenMonitor());

        Button dashboardButton = new Button("Back to dashboard");
        dashboardButton.setOnAction(event -> {
            Stage mainStage = getMainStage();
            if (mainStage != null) {
                mainStage.show();
                mainStage.toFront();
            }
            if (!isHostedProjectWorkspaceController()) {
                showShellSection(SECTION_DASHBOARD);
            }
        });

        Button closeButton = new Button("Close project window");
        closeButton.setOnAction(event -> closeProjectWorkspaceWindow());

        header.getChildren().addAll(
                title,
                spacer,
                runWizardButton,
                runPreflightButton,
                monitorButtonLocal,
                dashboardButton,
                closeButton
        );
        windowRoot.setTop(header);

        VBox sectionNav = createProjectWorkspaceSectionNav();
        projectWorkspaceOverviewPane = createProjectWorkspaceOverviewPane();

        projectWorkspaceContentHost = new StackPane();
        projectWorkspaceContentHost.getStyleClass().add("project-shell-content-host");
        if (projectWorkspaceOverviewPane != null) {
            projectWorkspaceContentHost.getChildren().add(projectWorkspaceOverviewPane);
        }
        projectWorkspaceContentHost.getChildren().add(modulesPane);
        if (filesPane != null) {
            projectWorkspaceContentHost.getChildren().add(filesPane);
        }

        HBox shellBody = new HBox(10.0, sectionNav, projectWorkspaceContentHost);
        shellBody.getStyleClass().add("project-shell-body");
        HBox.setHgrow(projectWorkspaceContentHost, Priority.ALWAYS);
        windowRoot.setCenter(shellBody);

        Stage owner = getStage();
        double defaultWidth = 1125.0;
        double defaultHeight = 1000.0;

        Scene scene = new Scene(windowRoot, defaultWidth, defaultHeight);
        if (rootPane != null && rootPane.getScene() != null) {
            scene.getStylesheets().setAll(rootPane.getScene().getStylesheets());
        }

        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setTitle(buildAppTitleText() + " - Project Workspace");
        stage.setScene(scene);
        applyThemeToStage(stage);
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (controllerRunning) {
                addUserLog("WARN", "Cannot close project window while a job is running.");
                event.consume();
            }
        });
        stage.setOnHidden(event -> {
            restoreProjectWorkspacePanesToMainShell();
            projectWorkspaceStage = null;
            projectWorkspaceTitleLabel = null;
            projectWorkspacePreflightButton = null;
            projectWorkspaceRunWizardButton = null;
            projectWorkspaceSectionOverviewButton = null;
            projectWorkspaceSectionWorkflowButton = null;
            projectWorkspaceSectionFilesButton = null;
            projectWorkspaceSectionJobsButton = null;
            projectWorkspaceSectionLogsButton = null;
            projectWorkspaceSectionSettingsButton = null;
            projectWorkspaceContentHost = null;
            projectWorkspaceOverviewPane = null;
            projectWorkspaceOverviewProjectLabel = null;
            projectWorkspaceOverviewStatsLabel = null;
            projectWorkspaceOverviewActivityLabel = null;
            projectWorkspaceOverviewWorkflowLabel = null;
            projectWorkspaceOverviewRunWizardButton = null;
            activeProjectWorkspaceSection = PROJECT_SECTION_OVERVIEW;
            if (!isHostedProjectWorkspaceController()) {
                showShellSection(SECTION_DASHBOARD);
            }
        });

        projectWorkspaceStage = stage;
        updateProjectWorkspaceWindowHeader();
        refreshProjectWorkspaceOverview();
        setNodeVisibleManaged(modulesPane, true);
        setNodeVisibleManaged(filesPane, true);
        if (runTab != null) {
            selectModule(runTab);
        }
        showProjectWorkspaceSection(PROJECT_SECTION_OVERVIEW);
        stage.show();
        stage.setMaximized(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void closeProjectWorkspaceWindow() {
        if (projectWorkspaceStage != null) {
            projectWorkspaceStage.close();
        }
    }

    private void restoreProjectWorkspacePanesToMainShell() {
        if (workspaceStack == null) {
            return;
        }

        restorePaneToWorkspaceStack(modulesPane);
        restorePaneToWorkspaceStack(filesPane);

        setNodeVisibleManaged(modulesPane, SECTION_MODULES.equals(activeSection));
        setNodeVisibleManaged(filesPane, SECTION_FILES.equals(activeSection));
    }

    private void restorePaneToWorkspaceStack(Node pane) {
        if (pane == null || workspaceStack == null) {
            return;
        }
        if (pane.getParent() instanceof Pane parentPane) {
            parentPane.getChildren().remove(pane);
        }
        if (!workspaceStack.getChildren().contains(pane)) {
            workspaceStack.getChildren().add(pane);
        }
    }

    private void updateProjectWorkspaceWindowHeader() {
        if (projectWorkspaceTitleLabel == null) {
            return;
        }
        ProjectWorkspace active = projectsById.get(activeProjectId);
        if (active == null) {
            projectWorkspaceTitleLabel.setText("Project workspace");
            return;
        }
        projectWorkspaceTitleLabel.setText(
                "Project workspace - " + active.name() + " (" + trimToEmpty(active.projectId()) + ")"
        );
    }

    private VBox createProjectWorkspaceSectionNav() {
        VBox nav = new VBox(8.0);
        nav.getStyleClass().addAll("card-pane", "project-section-nav");
        nav.setPadding(new Insets(10.0));
        nav.setPrefWidth(210.0);
        nav.setMinWidth(190.0);

        Label title = new Label("Project sections");
        title.getStyleClass().add("section-title");

        projectWorkspaceSectionOverviewButton = createProjectWorkspaceSectionButton("Overview", PROJECT_SECTION_OVERVIEW);
        projectWorkspaceSectionWorkflowButton = createProjectWorkspaceSectionButton("Workflow", PROJECT_SECTION_WORKFLOW);
        projectWorkspaceSectionFilesButton = createProjectWorkspaceSectionButton("Files", PROJECT_SECTION_FILES);
        projectWorkspaceSectionJobsButton = createProjectWorkspaceSectionButton("Jobs", PROJECT_SECTION_JOBS);
        projectWorkspaceSectionLogsButton = createProjectWorkspaceSectionButton("Logs", PROJECT_SECTION_LOGS);
        projectWorkspaceSectionSettingsButton = createProjectWorkspaceSectionButton("Settings", PROJECT_SECTION_SETTINGS);

        nav.getChildren().addAll(
                title,
                projectWorkspaceSectionOverviewButton,
                projectWorkspaceSectionWorkflowButton,
                projectWorkspaceSectionFilesButton,
                projectWorkspaceSectionJobsButton,
                projectWorkspaceSectionLogsButton,
                projectWorkspaceSectionSettingsButton
        );
        return nav;
    }

    private Button createProjectWorkspaceSectionButton(String label, String sectionId) {
        Button button = new Button(label);
        button.getStyleClass().add("project-section-btn");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> showProjectWorkspaceSection(sectionId));
        return button;
    }

    private VBox createProjectWorkspaceOverviewPane() {
        VBox pane = new VBox(10.0);
        pane.getStyleClass().addAll("workspace-pane", "project-overview-pane");

        VBox summaryCard = new VBox(8.0);
        summaryCard.getStyleClass().add("card-pane");
        summaryCard.setPadding(new Insets(12.0));

        Label heading = new Label("Overview");
        heading.getStyleClass().add("section-title");

        projectWorkspaceOverviewProjectLabel = new Label("Project: -");
        projectWorkspaceOverviewProjectLabel.getStyleClass().add("small-label");
        projectWorkspaceOverviewStatsLabel = new Label("Input: 0 | Output: 0 | Transcripts: 0");
        projectWorkspaceOverviewStatsLabel.getStyleClass().add("small-label");
        projectWorkspaceOverviewActivityLabel = new Label("Last activity: -");
        projectWorkspaceOverviewActivityLabel.getStyleClass().add("small-label");
        projectWorkspaceOverviewWorkflowLabel = new Label("Current workflow: -");
        projectWorkspaceOverviewWorkflowLabel.getStyleClass().add("small-label");

        HBox actions = new HBox(8.0);
        Button openWizard = new Button("Run wizard");
        openWizard.getStyleClass().add("accent-btn");
        openWizard.setOnAction(event -> onRunProjectWizard());
        projectWorkspaceOverviewRunWizardButton = openWizard;
        Button openWorkflow = new Button("Open workflow");
        openWorkflow.setOnAction(event -> showProjectWorkspaceSection(PROJECT_SECTION_WORKFLOW));
        Button openFiles = new Button("Open files");
        openFiles.setOnAction(event -> showProjectWorkspaceSection(PROJECT_SECTION_FILES));
        Button openFolder = new Button("Open project folder");
        openFolder.setOnAction(event -> onOpenProjectFolder());
        actions.getChildren().addAll(openWizard, openWorkflow, openFiles, openFolder);

        summaryCard.getChildren().addAll(
                heading,
                projectWorkspaceOverviewProjectLabel,
                projectWorkspaceOverviewStatsLabel,
                projectWorkspaceOverviewActivityLabel,
                projectWorkspaceOverviewWorkflowLabel,
                actions
        );
        pane.getChildren().add(summaryCard);
        VBox.setVgrow(summaryCard, Priority.NEVER);
        return pane;
    }

    private void refreshProjectWorkspaceOverview() {
        if (projectWorkspaceOverviewProjectLabel == null) {
            return;
        }
        ProjectWorkspace active = projectsById.get(activeProjectId);
        if (active == null) {
            projectWorkspaceOverviewProjectLabel.setText("Project: none");
            projectWorkspaceOverviewStatsLabel.setText("Input: 0 | Output: 0 | Transcripts: 0");
            projectWorkspaceOverviewActivityLabel.setText("Last activity: -");
            projectWorkspaceOverviewWorkflowLabel.setText("Current workflow: " + moduleLabel(activeModule));
            return;
        }

        Path root = active.rootPath();
        long inputCount = countRegularFiles(root == null ? null : root.resolve("input"));
        long outputCount = countRegularFiles(root == null ? null : root.resolve("output"));
        long transcriptCount = countTranscriptArtifacts(root == null ? null : root.resolve("output"));
        ActivitySnapshot activity = readLastTimelineActivity(root == null ? null : root.resolve("jobs").resolve("timeline.jsonl"));
        if (activity == null) {
            activity = new ActivitySnapshot("No activity yet", parseStamp(active.updatedAt()));
        }
        String activityTime = activity.at() == null ? "-" : DASHBOARD_ACTIVITY_FMT.format(activity.at());

        projectWorkspaceOverviewProjectLabel.setText("Project: " + active.name() + " (" + trimToEmpty(active.projectId()) + ")");
        projectWorkspaceOverviewStatsLabel.setText(
                "Input: " + inputCount
                        + " | Output: " + outputCount
                        + " | Transcripts: " + transcriptCount
                        + " | Size: " + formatBytes(directorySizeBytes(root))
        );
        projectWorkspaceOverviewActivityLabel.setText("Last activity: " + trimToEmpty(activity.label()) + " (" + activityTime + ")");
        projectWorkspaceOverviewWorkflowLabel.setText("Current workflow: " + moduleLabel(activeModule));
    }

    private void showProjectWorkspaceSection(String sectionId) {
        String normalized = trimToEmpty(sectionId).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = PROJECT_SECTION_OVERVIEW;
        }
        activeProjectWorkspaceSection = normalized;

        boolean showOverview = PROJECT_SECTION_OVERVIEW.equals(normalized);
        boolean showFiles = PROJECT_SECTION_FILES.equals(normalized);
        boolean showModules = !showOverview && !showFiles;

        setNodeVisibleManaged(projectWorkspaceOverviewPane, showOverview);
        setNodeVisibleManaged(filesPane, showFiles);
        setNodeVisibleManaged(modulesPane, showModules);

        if (showOverview) {
            refreshProjectWorkspaceOverview();
        } else if (showFiles) {
            refreshProjectFiles();
        } else if (PROJECT_SECTION_WORKFLOW.equals(normalized)) {
            if (runTab != null) {
                selectModule(runTab);
            }
            setNodeVisibleManaged(filesPane, false);
            setNodeVisibleManaged(modulesPane, true);
        } else if (PROJECT_SECTION_JOBS.equals(normalized)) {
            if (jobsTab != null) {
                selectModule(jobsTab);
            }
            setNodeVisibleManaged(filesPane, false);
            setNodeVisibleManaged(modulesPane, true);
        } else if (PROJECT_SECTION_LOGS.equals(normalized)) {
            if (logsTab != null) {
                selectModule(logsTab);
            }
            setNodeVisibleManaged(filesPane, false);
            setNodeVisibleManaged(modulesPane, true);
        } else if (PROJECT_SECTION_SETTINGS.equals(normalized)) {
            if (settingsTab != null) {
                selectModule(settingsTab);
            }
            setNodeVisibleManaged(filesPane, false);
            setNodeVisibleManaged(modulesPane, true);
        }
        updateProjectWorkspaceSectionButtons();
    }

    private void updateProjectWorkspaceSectionButtons() {
        setProjectSectionButtonActive(projectWorkspaceSectionOverviewButton, PROJECT_SECTION_OVERVIEW.equals(activeProjectWorkspaceSection));
        setProjectSectionButtonActive(projectWorkspaceSectionWorkflowButton, PROJECT_SECTION_WORKFLOW.equals(activeProjectWorkspaceSection));
        setProjectSectionButtonActive(projectWorkspaceSectionFilesButton, PROJECT_SECTION_FILES.equals(activeProjectWorkspaceSection));
        setProjectSectionButtonActive(projectWorkspaceSectionJobsButton, PROJECT_SECTION_JOBS.equals(activeProjectWorkspaceSection));
        setProjectSectionButtonActive(projectWorkspaceSectionLogsButton, PROJECT_SECTION_LOGS.equals(activeProjectWorkspaceSection));
        setProjectSectionButtonActive(projectWorkspaceSectionSettingsButton, PROJECT_SECTION_SETTINGS.equals(activeProjectWorkspaceSection));
    }

    private void setProjectSectionButtonActive(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.getStyleClass().remove("project-section-btn-active");
        if (active) {
            button.getStyleClass().add("project-section-btn-active");
        }
    }

    private void selectProjectWorkspaceWorkflowTab() {
        showProjectWorkspaceSection(PROJECT_SECTION_WORKFLOW);
    }

    private void selectProjectWorkspaceFilesTab() {
        showProjectWorkspaceSection(PROJECT_SECTION_FILES);
    }

    @FXML
    private void onDeleteProject() {
        ProjectRow selected = projectTable == null ? null : projectTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select project first.");
            return;
        }

        ProjectWorkspace workspace = projectsById.get(selected.getProjectId());
        if (workspace == null) {
            addUserLog("WARN", "Project does not exist anymore.");
            reloadWorkspaceFromCurrentAppDataDir();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Project");
        confirm.setHeaderText("Delete project '" + workspace.name() + "'?");
        confirm.setContentText("This will remove project metadata and delete the project folder from disk.");
        Stage owner = getStage();
        if (owner != null) {
            confirm.initOwner(owner);
        }
        applyThemeToDialog(confirm);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }

        try {
            Path root = workspace.rootPath();
            if (isLauncherController()) {
                unregisterHostedProjectWorkspaceWindow(workspace.projectId());
            }
            if (root != null && Files.exists(root)) {
                deleteRecursively(root);
            }
            projectsById.remove(workspace.projectId());
            preferences.removeByPrefix(PREF_PROJECT_PREFIX + workspace.projectId() + ".");
            preferences.flush();
            if (Objects.equals(activeProjectId, workspace.projectId())) {
                String replacement = projectsById.keySet().stream().findFirst().orElse("");
                setActiveProject(replacement, false, false);
            }
            saveWorkspaceState();
            refreshProjectsView();
            refreshProjectFiles();
            addUserLog("INFO", "Project deleted: " + workspace.name());
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Failed to delete project: " + ex.getMessage());
            addUserLog("ERROR", "Project delete failed.");
        }
    }

    @FXML
    private void onOpenProjectFolder() {
        Path target = activeProjectRoot;
        ProjectRow selected = projectTable == null ? null : projectTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            ProjectWorkspace selectedWorkspace = projectsById.get(selected.getProjectId());
            if (selectedWorkspace != null && selectedWorkspace.rootPath() != null) {
                target = selectedWorkspace.rootPath();
            }
        }
        if (target == null) {
            addUserLog("WARN", "No project selected.");
            return;
        }
        openPath(target.toString());
    }

    @FXML
    private void onRefreshProjects() {
        reloadWorkspaceFromCurrentAppDataDir();
        addUserLog("INFO", "Projects refreshed.");
    }

    @FXML
    private void onImportFilesToProject() {
        if (activeProjectRoot == null) {
            addUserLog("WARN", "Select an active project first.");
            return;
        }
        int copied = importFromChooserToProjectInput("Import files to project input");
        if (copied <= 0) {
            return;
        }
        refreshProjectFiles();
        addUserLog("INFO", "Imported files: " + copied);
    }

    @FXML
    private void onRefreshProjectFiles() {
        refreshProjectFiles();
    }

    @FXML
    private void onOpenSelectedProjectFile() {
        WorkspaceFileRow selected = currentSelectedWorkspaceFile();
        if (selected == null) {
            addUserLog("WARN", "Select file first.");
            return;
        }
        openPath(selected.getAbsolutePath());
    }

    @FXML
    private void onFilesAiSummary() {
        WorkspaceFileRow selected = currentSelectedWorkspaceFile();
        if (!isAiSummaryEligibleFile(selected)) {
            addUserLog("WARN", "AI summary supports only selected output .txt transcript files.");
            return;
        }
        showFilesSummaryWindow(selected);
    }

    @FXML
    private void onSaveProjectFileEditor() {
        if (activeEditedFilePath == null) {
            addUserLog("WARN", "No editable file selected.");
            return;
        }
        if (projectFileEditorArea == null || !projectFileEditorArea.isEditable()) {
            addUserLog("WARN", "Selected file is not editable in the built-in editor.");
            return;
        }
        saveEditedFileWithHistory(true);
    }

    @FXML
    private void onOpenEditedFileHistory() {
        if (activeProjectRoot == null || activeEditedFilePath == null) {
            addUserLog("WARN", "Select an editable file first.");
            return;
        }
        try {
            Path relative = activeProjectRoot.relativize(activeEditedFilePath.toAbsolutePath().normalize());
            String safe = relative.toString().replace('\\', '_').replace('/', '_');
            Path historyRoot = activeProjectRoot.resolve(".history");
            Files.createDirectories(historyRoot);
            Path target = historyRoot.resolve(safe);
            Files.createDirectories(target);
            openPath(target.toString());
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Failed to open history folder: " + ex.getMessage());
            addUserLog("ERROR", "Cannot open history folder.");
        }
    }

    @FXML
    private void onWizardSource() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        Optional<WizardActivityDefinition> selected = promptWizardActivitySelection();
        if (selected.isEmpty()) {
            return;
        }
        applyWizardActivitySelection(selected.get());
    }

    @FXML
    private void onWizardOutput() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        WizardActivityDefinition activity = resolveWizardActivitySelection();
        if (activity == null || !Objects.equals(trimToEmpty(wizardActivityId), activity.id())) {
            addUserLog("WARN", "Step 1 required: select activity first.");
            return;
        }
        if (runWizardImportStep(activity)) {
            updateWizardState();
        }
    }

    @FXML
    private void onWizardPreflight() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        WizardActivityDefinition activity = resolveWizardActivitySelection();
        if (activity == null || !Objects.equals(trimToEmpty(wizardActivityId), activity.id())) {
            addUserLog("WARN", "Step 1 required: select activity first.");
            return;
        }
        if (!wizardSourceImported) {
            addUserLog("WARN", "Step 2 required: import source first.");
            return;
        }
        if ("youtube".equals(activity.sourceMode()) && !wizardYoutubeDownloadConfirmed) {
            addUserLog("WARN", "Step 2 required: confirm YouTube import step first.");
            return;
        }
        onRunPreflight();
    }

    @FXML
    private void onWizardRun() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        WizardActivityDefinition activity = resolveWizardActivitySelection();
        if (activity == null || !Objects.equals(trimToEmpty(wizardActivityId), activity.id())) {
            addUserLog("WARN", "Step 1 required: select activity first.");
            return;
        }
        if (!wizardSourceImported) {
            addUserLog("WARN", "Step 2 required: import source first.");
            return;
        }
        if ("youtube".equals(activity.sourceMode()) && !wizardYoutubeDownloadConfirmed) {
            addUserLog("WARN", "Step 2 required: confirm YouTube import step first.");
            return;
        }
        onStart();
    }

    @FXML
    private void onWizardNext() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        if (runtimeBootstrapRunning) {
            addUserLog("WARN", "Online runtime setup is in progress. Wait until it completes.");
            return;
        }
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }

        WizardActivityDefinition activity = resolveWizardActivitySelection();
        boolean activityReady = activity != null && Objects.equals(trimToEmpty(wizardActivityId), activity.id());
        if (!activityReady) {
            Optional<WizardActivityDefinition> selected = promptWizardActivitySelection();
            if (selected.isEmpty()) {
                return;
            }
            applyWizardActivitySelection(selected.get());
            activity = selected.get();
            activityReady = true;
        }

        if (activity == null || !activityReady) {
            addUserLog("WARN", "Wizard could not determine activity.");
            return;
        }

        boolean sourceReady = wizardSourceImported;
        boolean youtubeConfirmReady = !"youtube".equals(activity.sourceMode()) || wizardYoutubeDownloadConfirmed;
        if (!sourceReady || !youtubeConfirmReady) {
            if (!runWizardImportStep(activity)) {
                updateWizardState();
                return;
            }
            sourceReady = wizardSourceImported;
            youtubeConfirmReady = !"youtube".equals(activity.sourceMode()) || wizardYoutubeDownloadConfirmed;
        }

        if (!sourceReady || !youtubeConfirmReady) {
            updateWizardState();
            return;
        }

        saveActiveModuleState();
        String validationError = validateInputs();
        if (validationError != null) {
            addUserLog("ERROR", validationError);
            updateWizardState();
            return;
        }

        ObjectNode params = buildPipelineParams();
        startPipelineRequest(params, "wizard");
    }

    /**
     * Opens guided wizard window and executes module-defined steps in order.
     */
    @FXML
    private void onRunProjectWizard() {
        if (!ensureWorkflowProjectReady()) {
            return;
        }
        if (controllerRunning) {
            addUserLog("WARN", "A job is running. Wait until it finishes.");
            return;
        }
        if (isGlobalRunLockedByOtherController()) {
            addUserLog("WARN", "Another project job is running. Wait until it finishes.");
            return;
        }

        Optional<WizardActivityDefinition> selectedActivity = promptWizardActivitySelection();
        if (selectedActivity.isEmpty()) {
            addUserLog("INFO", "Wizard cancelled.");
            return;
        }

        WizardActivityDefinition activity = selectedActivity.get();
        applyWizardActivitySelection(activity);
        showProjectWorkspaceSection(PROJECT_SECTION_WORKFLOW);
        final WizardActivityDefinition resolvedActivity = activity;
        ModuleWizardSpec wizardSpec = resolveActiveModuleWizardSpec(resolvedActivity);
        Stage owner = projectWorkspaceStage != null ? projectWorkspaceStage : getStage();
        ProjectWizardWindow wizardWindow = new ProjectWizardWindow(
                owner,
                wizardSpec,
                (step, index, total, store) -> runProjectWizardStep(step, resolvedActivity, store),
                this::applyThemeToStage
        );

        var result = wizardWindow.showAndWait();
        if (result.completed()) {
            addUserLog("INFO", "Wizard completed: " + wizardSpec.title());
        } else {
            addUserLog("INFO", "Wizard cancelled: " + wizardSpec.title());
        }
        refreshProjectWorkspaceOverview();
        updateWizardState();
    }

    private boolean isProjectWorkspaceDetached() {
        if (projectWorkspaceStage == null) {
            return false;
        }
        if (workspaceStack == null) {
            return true;
        }
        boolean modulesDetached = modulesPane != null
                && modulesPane.getParent() != null
                && modulesPane.getParent() != workspaceStack;
        boolean filesDetached = filesPane != null
                && filesPane.getParent() != null
                && filesPane.getParent() != workspaceStack;
        return modulesDetached || filesDetached;
    }

    private WizardActivityDefinition resolveWizardActivityForModule(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        for (WizardActivityDefinition option : WIZARD_ACTIVITIES) {
            if (Objects.equals(option.moduleId(), normalized)) {
                return option;
            }
        }
        return null;
    }

    private ModuleWizardSpec resolveActiveModuleWizardSpec(WizardActivityDefinition activity) {
        ModuleComponent component = getActiveModuleComponent();
        ModuleWizardSpec spec = component == null ? null : component.wizardSpec();
        if (spec == null || spec.steps().isEmpty()) {
            List<ModuleWizardStepSpec> fallbackSteps = new ArrayList<>();
            if (activity != null && Objects.equals(activity.sourceMode(), "youtube")) {
                fallbackSteps.add(new ModuleWizardStepSpec("import_youtube", "YouTube source", "Provide URL and quality.", "import_youtube"));
            } else {
                fallbackSteps.add(new ModuleWizardStepSpec("import_local", "Import local source", "Copy media into project input.", "import_local"));
            }
            fallbackSteps.add(new ModuleWizardStepSpec("transcription", "Transcription settings", "Set model and language defaults.", "configure_transcription"));
            fallbackSteps.add(new ModuleWizardStepSpec("output", "Output options", "Set output prefix and output options.", "configure_output"));
            fallbackSteps.add(new ModuleWizardStepSpec("preflight_start", "Preflight and start", "Run preflight gate and confirm start.", "preflight_start"));
            String title = activity == null ? moduleLabel(activeModule) + " Wizard" : activity.title() + " Wizard";
            String moduleId = activity == null ? activeModule : activity.moduleId();
            return new ModuleWizardSpec(moduleId, title, fallbackSteps);
        }
        return spec;
    }

    /**
     * Executes one wizard step action and records step metadata in wizard store.
     */
    private boolean runProjectWizardStep(ModuleWizardStepSpec step, WizardActivityDefinition activity, WizardValueStore store) {
        if (step == null) {
            return true;
        }
        String actionKey = trimToEmpty(step.actionKey());
        if (actionKey.isBlank()) {
            return true;
        }
        boolean ok = runWizardStepAction(actionKey, activity);
        if (ok && store != null) {
            store.put("last_step", trimToEmpty(step.id()));
            store.put("last_action", actionKey);
            store.put("module", activeModule);
            store.put("activity", activity == null ? "" : activity.id());
        }
        refreshProjectWorkspaceOverview();
        updateWizardState();
        return ok;
    }

    /**
     * Dispatches wizard action keys to concrete handlers.
     */
    private boolean runWizardStepAction(String actionKey, WizardActivityDefinition activity) {
        String key = trimToEmpty(actionKey).toLowerCase(Locale.ROOT);
        return switch (key) {
            case "import_local" -> runWizardLocalImportStep(activity);
            case "import_youtube" -> runWizardYoutubeImportStep(activity);
            case "configure_transcription" -> promptWizardTranscriptionSettings();
            case "configure_output" -> promptWizardOutputSettings();
            case "configure_speaker" -> promptWizardSpeakerSettings();
            case "configure_conference" -> promptWizardConferenceSettings();
            case "configure_subtitles" -> promptWizardSubtitleSettings();
            case "configure_dub" -> promptWizardDubSettings();
            case "preflight_start" -> confirmWizardStartAndRun(activity);
            default -> {
                addUserLog("WARN", "Unknown wizard step action: " + actionKey);
                yield false;
            }
        };
    }

    private boolean promptWizardTranscriptionSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transcription settings");
        dialog.setHeaderText("Configure transcription defaults for this run.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> modelPicker = new ComboBox<>(FXCollections.observableArrayList(modelField.getItems()));
        modelPicker.setMaxWidth(Double.MAX_VALUE);
        modelPicker.getSelectionModel().select(safeValue(modelField));

        ComboBox<String> sourceLangPicker = new ComboBox<>(FXCollections.observableArrayList(sourceLangBox.getItems()));
        sourceLangPicker.setMaxWidth(Double.MAX_VALUE);
        sourceLangPicker.getSelectionModel().select(safeValue(sourceLangBox));

        ComboBox<String> summaryLangPicker = new ComboBox<>(FXCollections.observableArrayList(summaryLangBox.getItems()));
        summaryLangPicker.setMaxWidth(Double.MAX_VALUE);
        summaryLangPicker.getSelectionModel().select(safeValue(summaryLangBox));

        CheckBox summaryAiToggle = new CheckBox("Enable AI summary");
        summaryAiToggle.setSelected(summaryAiBox != null && summaryAiBox.isSelected());

        ComboBox<String> summaryTierPicker = new ComboBox<>(FXCollections.observableArrayList(
                summaryModelTierBox == null
                        ? List.of("low", "medium", "high")
                        : summaryModelTierBox.getItems()
        ));
        summaryTierPicker.setMaxWidth(Double.MAX_VALUE);
        summaryTierPicker.getSelectionModel().select(
                summaryModelTierBox == null ? "medium" : safeValue(summaryModelTierBox)
        );
        summaryTierPicker.setDisable(!summaryAiToggle.isSelected());
        summaryAiToggle.selectedProperty().addListener((obs, oldVal, newVal) ->
                summaryTierPicker.setDisable(newVal == null || !newVal)
        );

        VBox content = new VBox(
                8.0,
                new Label("Model"),
                modelPicker,
                new Label("Source language"),
                sourceLangPicker,
                new Label("Summary language"),
                summaryLangPicker,
                summaryAiToggle,
                new Label("Summary model tier"),
                summaryTierPicker
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        selectComboValue(modelField, safeValue(modelPicker));
        selectComboValue(sourceLangBox, safeValue(sourceLangPicker));
        selectComboValue(summaryLangBox, safeValue(summaryLangPicker));
        if (summaryAiBox != null) {
            summaryAiBox.setSelected(summaryAiToggle.isSelected());
        }
        if (summaryModelTierBox != null) {
            selectComboValue(summaryModelTierBox, safeValue(summaryTierPicker));
            summaryModelTierBox.setDisable(summaryAiBox == null || !summaryAiBox.isSelected());
        }
        addUserLog("INFO", "Wizard settings updated: transcription.");
        return true;
    }

    private boolean promptWizardOutputSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Output settings");
        dialog.setHeaderText("Configure output options for this run.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField prefixField = new TextField(trimToEmpty(outputPrefixField == null ? "" : outputPrefixField.getText()));
        prefixField.setPromptText("optional");
        CheckBox keepOriginalsToggle = new CheckBox("Keep originals");
        keepOriginalsToggle.setSelected(keepOriginalsBox != null && keepOriginalsBox.isSelected());

        Label outputInfo = new Label(
                "Output folder is managed by project workspace:\n"
                        + trimToEmpty(outputDirField == null ? "" : outputDirField.getText())
        );
        outputInfo.getStyleClass().add("small-label");
        outputInfo.setWrapText(true);

        VBox content = new VBox(
                8.0,
                outputInfo,
                new Label("Output prefix"),
                prefixField,
                keepOriginalsToggle
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        if (outputPrefixField != null) {
            outputPrefixField.setText(trimToEmpty(prefixField.getText()));
        }
        if (keepOriginalsBox != null) {
            keepOriginalsBox.setSelected(keepOriginalsToggle.isSelected());
        }
        addUserLog("INFO", "Wizard settings updated: output.");
        return true;
    }

    private boolean promptWizardSpeakerSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Speaker diarization settings");
        dialog.setHeaderText("Configure diarization backend and speaker limits.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> backendPicker = new ComboBox<>(FXCollections.observableArrayList(diarizationBackendBox.getItems()));
        backendPicker.setMaxWidth(Double.MAX_VALUE);
        backendPicker.getSelectionModel().select(safeValue(diarizationBackendBox));

        ComboBox<String> accuracyPicker = new ComboBox<>(FXCollections.observableArrayList(diarizationAccuracyBox.getItems()));
        accuracyPicker.setMaxWidth(Double.MAX_VALUE);
        accuracyPicker.getSelectionModel().select(safeValue(diarizationAccuracyBox));

        SpinnerValueFactory.IntegerSpinnerValueFactory minFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, diarizationMinSpinner.getValue());
        Spinner<Integer> minSpinner = new Spinner<>(minFactory);
        minSpinner.setEditable(true);
        SpinnerValueFactory.IntegerSpinnerValueFactory maxFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, diarizationMaxSpinner.getValue());
        Spinner<Integer> maxSpinner = new Spinner<>(maxFactory);
        maxSpinner.setEditable(true);

        VBox content = new VBox(
                8.0,
                new Label("Backend"), backendPicker,
                new Label("Accuracy profile"), accuracyPicker,
                new Label("Min speakers"), minSpinner,
                new Label("Max speakers"), maxSpinner
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        if (diarizationEnabledBox != null) {
            diarizationEnabledBox.setSelected(true);
        }
        selectComboValue(diarizationBackendBox, safeValue(backendPicker));
        selectComboValue(diarizationAccuracyBox, safeValue(accuracyPicker));
        if (diarizationMinSpinner != null) {
            diarizationMinSpinner.getValueFactory().setValue(minSpinner.getValue());
        }
        if (diarizationMaxSpinner != null) {
            diarizationMaxSpinner.getValueFactory().setValue(maxSpinner.getValue());
        }
        addUserLog("INFO", "Wizard settings updated: speaker diarization.");
        return true;
    }

    private boolean promptWizardConferenceSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Conference settings");
        dialog.setHeaderText("Configure conference metadata defaults.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField titleField = new TextField(trimToEmpty(conferenceTitleField == null ? "" : conferenceTitleField.getText()));
        TextField dateField = new TextField(trimToEmpty(conferenceDateField == null ? "" : conferenceDateField.getText()));
        TextField speakerFieldLocal = new TextField(trimToEmpty(speakerField == null ? "" : speakerField.getText()));
        TextField topicFieldLocal = new TextField(trimToEmpty(topicField == null ? "" : topicField.getText()));
        CheckBox syncFilesToggle = new CheckBox("Sync file rows from current input now");
        syncFilesToggle.setSelected(false);

        VBox content = new VBox(
                8.0,
                new Label("Conference title"), titleField,
                new Label("Conference date"), dateField,
                new Label("Default speaker"), speakerFieldLocal,
                new Label("Default topic"), topicFieldLocal,
                syncFilesToggle
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        if (conferenceTitleField != null) {
            conferenceTitleField.setText(trimToEmpty(titleField.getText()));
        }
        if (conferenceDateField != null) {
            conferenceDateField.setText(trimToEmpty(dateField.getText()));
        }
        if (speakerField != null) {
            speakerField.setText(trimToEmpty(speakerFieldLocal.getText()));
        }
        if (topicField != null) {
            topicField.setText(trimToEmpty(topicFieldLocal.getText()));
        }
        if (syncFilesToggle.isSelected()) {
            onSyncConferenceFiles();
        }
        addUserLog("INFO", "Wizard settings updated: conference metadata.");
        return true;
    }

    private boolean promptWizardSubtitleSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Subtitle settings");
        dialog.setHeaderText("Configure subtitle workflow options.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> subtitleModePicker = new ComboBox<>(FXCollections.observableArrayList(subtitleModeBox.getItems()));
        subtitleModePicker.setMaxWidth(Double.MAX_VALUE);
        subtitleModePicker.getSelectionModel().select(safeValue(subtitleModeBox));

        CheckBox translateToggle = new CheckBox("Translate subtitles");
        translateToggle.setSelected(translateSubtitlesBox != null && translateSubtitlesBox.isSelected());

        ComboBox<String> targetLangPicker = new ComboBox<>(FXCollections.observableArrayList(targetLangBox.getItems()));
        targetLangPicker.setMaxWidth(Double.MAX_VALUE);
        targetLangPicker.getSelectionModel().select(safeValue(targetLangBox));
        targetLangPicker.disableProperty().bind(translateToggle.selectedProperty().not());

        VBox content = new VBox(
                8.0,
                new Label("Subtitle mode"), subtitleModePicker,
                translateToggle,
                new Label("Target language"), targetLangPicker
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        selectComboValue(subtitleModeBox, safeValue(subtitleModePicker));
        if (translateSubtitlesBox != null) {
            translateSubtitlesBox.setSelected(translateToggle.isSelected());
        }
        selectComboValue(targetLangBox, safeValue(targetLangPicker));
        updateTranslationUi();
        addUserLog("INFO", "Wizard settings updated: subtitles.");
        return true;
    }

    private boolean promptWizardDubSettings() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Dub settings");
        dialog.setHeaderText("Configure dub workflow options.");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<String> targetLangPicker = new ComboBox<>(FXCollections.observableArrayList(targetLangBox.getItems()));
        targetLangPicker.setMaxWidth(Double.MAX_VALUE);
        targetLangPicker.getSelectionModel().select(safeValue(targetLangBox));

        ComboBox<String> sourceLangPicker = new ComboBox<>(FXCollections.observableArrayList(sourceLangBox.getItems()));
        sourceLangPicker.setMaxWidth(Double.MAX_VALUE);
        sourceLangPicker.getSelectionModel().select(safeValue(sourceLangBox));

        VBox content = new VBox(
                8.0,
                new Label("Target language"), targetLangPicker,
                new Label("Source language"), sourceLangPicker
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }

        if (translateSubtitlesBox != null) {
            translateSubtitlesBox.setSelected(true);
        }
        selectComboValue(targetLangBox, safeValue(targetLangPicker));
        selectComboValue(sourceLangBox, safeValue(sourceLangPicker));
        updateTranslationUi();
        addUserLog("INFO", "Wizard settings updated: dubbing.");
        return true;
    }

    private boolean confirmWizardStartAndRun(WizardActivityDefinition activity) {
        if (activity == null) {
            addUserLog("WARN", "Wizard activity is missing.");
            return false;
        }
        if (!wizardSourceImported) {
            addUserLog("WARN", "Source is not prepared. Complete import step first.");
            return false;
        }
        if ("youtube".equals(activity.sourceMode()) && !wizardYoutubeDownloadConfirmed) {
            addUserLog("WARN", "YouTube source is not confirmed. Complete import step first.");
            return false;
        }

        saveActiveModuleState();
        String validationError = validateInputs();
        if (validationError != null) {
            addUserLog("ERROR", validationError);
            return false;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Start workflow");
        confirm.setHeaderText("Preflight is mandatory and will run before job start.");
        confirm.setContentText(
                "Activity: " + activity.title() + "\n"
                        + "Project: " + trimToEmpty(activeProjectId) + "\n\n"
                        + "Start workflow now?"
        );
        Stage owner = getStage();
        if (owner != null) {
            confirm.initOwner(owner);
        }
        applyThemeToDialog(confirm);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            addUserLog("WARN", "Wizard start cancelled.");
            return false;
        }

        ObjectNode params = buildPipelineParams();
        startPipelineRequest(params, "wizard");
        return true;
    }

    private Optional<WizardActivityDefinition> promptWizardActivitySelection() {
        List<String> choices = WIZARD_ACTIVITIES.stream().map(WizardActivityDefinition::title).toList();
        String defaultChoice = WIZARD_ACTIVITIES.get(0).title();

        WizardActivityDefinition current = resolveWizardActivitySelection();
        if (current != null) {
            defaultChoice = current.title();
        } else {
            for (WizardActivityDefinition option : WIZARD_ACTIVITIES) {
                if (Objects.equals(option.moduleId(), activeModule)) {
                    defaultChoice = option.title();
                    break;
                }
            }
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(defaultChoice, choices);
        dialog.setTitle("Wizard - Select Activity");
        dialog.setHeaderText("Step 1/4: choose activity for this project");
        dialog.setContentText("Activity:");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        applyThemeToDialog(dialog);
        Optional<String> selectedLabel = dialog.showAndWait();
        if (selectedLabel.isEmpty()) {
            return Optional.empty();
        }
        for (WizardActivityDefinition option : WIZARD_ACTIVITIES) {
            if (Objects.equals(option.title(), selectedLabel.get())) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    private WizardActivityDefinition resolveWizardActivitySelection() {
        String selectedId = trimToEmpty(wizardActivityId);
        WizardActivityDefinition selectedOption = null;
        if (!selectedId.isBlank()) {
            for (WizardActivityDefinition option : WIZARD_ACTIVITIES) {
                if (Objects.equals(option.id(), selectedId)) {
                    selectedOption = option;
                    break;
                }
            }
        }
        if (selectedOption != null && Objects.equals(selectedOption.moduleId(), activeModule)) {
            return selectedOption;
        }
        for (WizardActivityDefinition option : WIZARD_ACTIVITIES) {
            if (Objects.equals(option.moduleId(), activeModule)) {
                return option;
            }
        }
        return selectedOption;
    }

    private void applyWizardActivitySelection(WizardActivityDefinition activity) {
        if (activity == null) {
            return;
        }
        wizardActivityId = activity.id();
        wizardSourceImported = false;
        wizardYoutubeDownloadConfirmed = false;
        invalidatePreflightState();

        showShellSection(SECTION_MODULES);
        activateModule(activity.moduleId(), true, true);
        selectModule(runTab);
        withWizardSourceUpdate(() -> selectComboValue(sourceModeBox, activity.sourceMode()));

        if ("local".equals(activity.sourceMode()) && activeProjectRoot != null && localPathField != null) {
            withWizardSourceUpdate(() -> localPathField.setText(activeProjectRoot.resolve("input").toString()));
            requestFocusIfVisible(localPathField);
        } else if ("youtube".equals(activity.sourceMode())) {
            requestFocusIfVisible(youtubeUrlField);
        }

        addUserLog("INFO", "Wizard activity selected: " + activity.title());
        updateWizardState();
    }

    private boolean runWizardImportStep(WizardActivityDefinition activity) {
        if (activity == null) {
            return false;
        }
        if ("youtube".equals(activity.sourceMode())) {
            return runWizardYoutubeImportStep(activity);
        }
        return runWizardLocalImportStep(activity);
    }

    private boolean runWizardLocalImportStep(WizardActivityDefinition activity) {
        int copied = importFromChooserToProjectInput("Step 2/4 - Import local source into project input");
        Path inputDir = activeProjectRoot == null ? null : activeProjectRoot.resolve("input");
        if (copied <= 0) {
            long existingCount = countRegularFiles(inputDir);
            if (existingCount <= 0L) {
                addUserLog("WARN", "No source imported. Add at least one file for this activity.");
                return false;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Use Existing Project Input");
            confirm.setHeaderText("No new files were selected.");
            confirm.setContentText(
                    "Project input already contains " + existingCount + " file(s).\n"
                            + "Use existing input for activity '" + activity.title() + "'?"
            );
            Stage owner = getStage();
            if (owner != null) {
                confirm.initOwner(owner);
            }
            applyThemeToDialog(confirm);
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.OK) {
                addUserLog("WARN", "Step 2 not completed.");
                return false;
            }
            addUserLog("INFO", "Wizard import step accepted existing project input (" + existingCount + " file(s)).");
        } else {
            refreshProjectFiles();
            addUserLog("INFO", "Wizard import step copied " + copied + " file(s) into project input.");
        }

        wizardSourceImported = true;
        wizardYoutubeDownloadConfirmed = false;
        if (inputDir != null && localPathField != null) {
            withWizardSourceUpdate(() -> localPathField.setText(inputDir.toString()));
        }
        return true;
    }

    private boolean runWizardYoutubeImportStep(WizardActivityDefinition activity) {
        Optional<WizardYoutubeInput> inputResult = promptWizardYoutubeImportInput(activity);
        if (inputResult.isEmpty()) {
            addUserLog("WARN", "YouTube import step cancelled.");
            return false;
        }
        WizardYoutubeInput input = inputResult.get();

        String selectedQuality = input.quality();

        withWizardSourceUpdate(() -> selectComboValue(sourceModeBox, "youtube"));
        if (youtubeUrlField != null) {
            final String finalUrl = input.url();
            withWizardSourceUpdate(() -> youtubeUrlField.setText(finalUrl));
        }
        if (qualityBox != null) {
            selectedQuality = resolveWizardYoutubeQualitySelection(input.quality(), qualityBox.getItems());
            final String finalQuality = selectedQuality;
            withWizardSourceUpdate(() -> selectComboValue(qualityBox, finalQuality));
        }
        if (playlistBox != null) {
            final boolean playlistSelected = input.playlist();
            withWizardSourceUpdate(() -> playlistBox.setSelected(playlistSelected));
        }

        wizardSourceImported = true;
        wizardYoutubeDownloadConfirmed = true;
        addUserLog(
                "INFO",
                "Wizard import step confirmed YouTube source ("
                        + sanitizeYoutubeUrlForLog(input.url())
                        + ", quality="
                        + selectedQuality
                        + ")."
        );
        return true;
    }

    private Optional<WizardYoutubeInput> promptWizardYoutubeImportInput(WizardActivityDefinition activity) {
        Dialog<WizardYoutubeInput> dialog = new Dialog<>();
        dialog.setTitle("YouTube Import");
        dialog.setHeaderText("Step 2/4: provide YouTube source for " + trimToEmpty(activity == null ? "" : activity.title()));
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType continueButtonType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(continueButtonType, ButtonType.CANCEL);

        TextField urlField = new TextField("");
        urlField.setPromptText("https://www.youtube.com/watch?v=...");

        List<String> availableQualities = new ArrayList<>();
        if (qualityBox != null && qualityBox.getItems() != null) {
            availableQualities.addAll(qualityBox.getItems());
        }
        if (availableQualities.isEmpty()) {
            availableQualities.add("best");
        }

        ComboBox<String> qualityPicker = new ComboBox<>(FXCollections.observableArrayList(availableQualities));
        qualityPicker.setMaxWidth(Double.MAX_VALUE);
        String defaultQuality = resolveWizardYoutubeQualitySelection(
                qualityBox == null ? "" : safeValue(qualityBox),
                availableQualities
        );
        qualityPicker.getSelectionModel().select(defaultQuality);

        CheckBox playlistToggle = new CheckBox("Playlist");
        playlistToggle.setSelected(playlistBox != null && playlistBox.isSelected());

        Label validationLabel = new Label("URL is required.");
        validationLabel.getStyleClass().add("warning-label");
        validationLabel.setWrapText(true);

        Label disclaimerLabel = new Label(wizardYoutubeDisclaimerText());
        disclaimerLabel.getStyleClass().add("warning-label");
        disclaimerLabel.setWrapText(true);

        VBox content = new VBox(8);
        content.getChildren().addAll(
                new Label("YouTube URL"),
                urlField,
                new Label("Quality"),
                qualityPicker,
                playlistToggle,
                validationLabel,
                disclaimerLabel
        );
        dialog.getDialogPane().setContent(content);
        applyThemeToDialog(dialog);

        Node continueButton = dialog.getDialogPane().lookupButton(continueButtonType);
        Runnable refreshValidity = () -> {
            boolean valid = !trimToEmpty(urlField.getText()).isBlank();
            if (continueButton != null) {
                continueButton.setDisable(!valid);
            }
            validationLabel.setManaged(!valid);
            validationLabel.setVisible(!valid);
        };
        urlField.textProperty().addListener((obs, oldValue, newValue) -> refreshValidity.run());
        refreshValidity.run();

        dialog.setResultConverter(buttonType -> {
            if (buttonType != continueButtonType) {
                return null;
            }
            String url = trimToEmpty(urlField.getText());
            if (url.isBlank()) {
                return null;
            }
            String quality = resolveWizardYoutubeQualitySelection(safeValue(qualityPicker), availableQualities);
            return new WizardYoutubeInput(url, quality, playlistToggle.isSelected());
        });

        Platform.runLater(urlField::requestFocus);
        return dialog.showAndWait();
    }

    private String wizardYoutubeDisclaimerText() {
        String language = normalizeUiLanguage(currentUiLanguage);
        if (UI_LANG_CS.equals(language)) {
            return "Pouzivejte pouze obsah, ke kteremu mate opravneni, a dodrzujte podminky platforem (napr. YouTube).";
        }
        return "Use only content you are authorized to process and follow platform terms (e.g., YouTube).";
    }

    private String resolveWizardYoutubeQualitySelection(String requested, List<String> availableQualities) {
        List<String> qualities = availableQualities == null ? List.of() : availableQualities;
        if (qualities.isEmpty()) {
            return "best";
        }
        String normalized = trimToEmpty(requested);
        if (!normalized.isBlank() && qualities.contains(normalized)) {
            return normalized;
        }
        if (qualities.contains("best")) {
            return "best";
        }
        return qualities.get(0);
    }

    private String sanitizeYoutubeUrlForLog(String url) {
        String trimmed = trimToEmpty(url);
        if (trimmed.isBlank()) {
            return "(empty)";
        }
        int queryStart = trimmed.indexOf('?');
        String sanitized = queryStart >= 0 ? trimmed.substring(0, queryStart) : trimmed;
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 117) + "...";
        }
        return sanitized;
    }

    private void onWizardSourceFieldManuallyChanged(String fieldKey) {
        if (wizardSourceUpdateInProgress) {
            return;
        }
        WizardActivityDefinition activity = resolveWizardActivitySelection();
        if (activity == null) {
            return;
        }
        String key = trimToEmpty(fieldKey).toLowerCase(Locale.ROOT);
        if ("source_mode".equals(key) && !Objects.equals(activity.sourceMode(), safeValue(sourceModeBox))) {
            wizardSourceImported = false;
            wizardYoutubeDownloadConfirmed = false;
            return;
        }
        if ("local".equals(activity.sourceMode()) && "local_path".equals(key)) {
            wizardSourceImported = false;
            return;
        }
        if ("youtube".equals(activity.sourceMode()) && "youtube_url".equals(key)) {
            wizardSourceImported = false;
            wizardYoutubeDownloadConfirmed = false;
        }
    }

    private void withWizardSourceUpdate(Runnable action) {
        if (action == null) {
            return;
        }
        wizardSourceUpdateInProgress = true;
        try {
            action.run();
        } finally {
            wizardSourceUpdateInProgress = false;
        }
    }

    @FXML
    private void onApplySelectedPreflightFix() {
        PreflightCheckRow selected = preflightChecksTable == null ? null : preflightChecksTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select a preflight check first.");
            return;
        }
        applyPreflightQuickFix(selected);
    }

    @FXML
    private void onRefreshOperationsDiagnostics() {
        refreshOperationsDiagnostics();
    }

    private void setupEditorBehaviors() {
        if (projectFileEditorArea != null) {
            editorAutosavePause = new PauseTransition(Duration.seconds(1.2));
            editorAutosavePause.setOnFinished(event -> saveEditedFileWithHistory(false));
            projectFileEditorArea.textProperty().addListener((obs, oldVal, newVal) -> {
                if (editorAutoSaving || activeEditedFilePath == null || !projectFileEditorArea.isEditable()) {
                    return;
                }
                editorDirty = !Objects.equals(trimToEmpty(newVal), trimToEmpty(loadedEditedFileText));
                updateEditorButtonsState();
                updateWizardState();
                if (editorDirty && editorAutosavePause != null) {
                    editorAutosavePause.playFromStart();
                }
            });
        }
        updateEditorButtonsState();
        updateWizardState();
    }

    private void setupFileDropImport() {
        if (filesPane != null) {
            filesPane.setOnDragOver(event -> {
                if (event.getDragboard().hasFiles() && activeProjectRoot != null) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
                event.consume();
            });
            filesPane.setOnDragDropped(event -> {
                boolean success = false;
                if (activeProjectRoot != null && event.getDragboard().hasFiles()) {
                    List<Path> dropped = event.getDragboard().getFiles().stream()
                            .filter(Objects::nonNull)
                            .map(File::toPath)
                            .toList();
                    int imported = importPathsToProjectInput(dropped);
                    if (imported > 0) {
                        refreshProjectFiles();
                        addUserLog("INFO", "Imported from drag & drop: " + imported + " file(s).");
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
        }
        if (projectFilesTable != null) {
            projectFilesTable.setOnDragOver(event -> {
                if (event.getDragboard().hasFiles() && activeProjectRoot != null) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
                event.consume();
            });
            projectFilesTable.setOnDragDropped(event -> {
                boolean success = false;
                if (activeProjectRoot != null && event.getDragboard().hasFiles()) {
                    List<Path> dropped = event.getDragboard().getFiles().stream()
                            .filter(Objects::nonNull)
                            .map(File::toPath)
                            .toList();
                    int imported = importPathsToProjectInput(dropped);
                    if (imported > 0) {
                        refreshProjectFiles();
                        addUserLog("INFO", "Imported from drag & drop: " + imported + " file(s).");
                        success = true;
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
        }
    }

    private int importFromChooserToProjectInput(String chooserTitle) {
        if (activeProjectRoot == null) {
            return 0;
        }
        Stage stage = getStage();
        if (stage == null) {
            return 0;
        }

        List<Path> selectedPaths = new ArrayList<>();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(trimToEmpty(chooserTitle).isBlank() ? "Import files to project input" : chooserTitle);
        List<File> selectedFiles = chooser.showOpenMultipleDialog(stage);
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (File file : selectedFiles) {
                if (file != null) {
                    selectedPaths.add(file.toPath());
                }
            }
        } else {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Import folder to project input");
            File folder = directoryChooser.showDialog(stage);
            if (folder != null) {
                selectedPaths.add(folder.toPath());
            }
        }
        return importPathsToProjectInput(selectedPaths);
    }

    private int importPathsToProjectInput(List<Path> paths) {
        if (activeProjectRoot == null || paths == null || paths.isEmpty()) {
            return 0;
        }
        Path inputRoot = activeProjectRoot.resolve("input");
        int copied = 0;
        try {
            Files.createDirectories(inputRoot);
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Cannot prepare input folder: " + ex.getMessage());
            return 0;
        }

        for (Path candidate : paths) {
            if (candidate == null) {
                continue;
            }
            Path absolute = candidate.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                continue;
            }
            try {
                if (Files.isDirectory(absolute)) {
                    String dirName = trimToEmpty(absolute.getFileName() == null ? "" : absolute.getFileName().toString());
                    Path baseTarget = dirName.isBlank() ? inputRoot : inputRoot.resolve(dirName);
                    try (Stream<Path> stream = Files.walk(absolute)) {
                        List<Path> files = stream.filter(Files::isRegularFile).toList();
                        for (Path file : files) {
                            Path rel = absolute.relativize(file);
                            Path destination = resolveUniqueTargetPath(baseTarget.resolve(rel).getParent(), rel.getFileName());
                            Files.createDirectories(destination.getParent());
                            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                            copied += 1;
                        }
                    }
                } else if (Files.isRegularFile(absolute)) {
                    Path destination = resolveUniqueTargetPath(inputRoot, absolute.getFileName());
                    Files.copy(absolute, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied += 1;
                }
            } catch (Exception ex) {
                addTechnicalLog("WARN", "Drag import failed for '" + absolute + "': " + ex.getMessage());
            }
        }
        return copied;
    }

    private void requestFocusIfVisible(Node... nodes) {
        if (nodes == null) {
            return;
        }
        for (Node node : nodes) {
            if (node != null && node.isVisible() && !node.isDisable()) {
                node.requestFocus();
                return;
            }
        }
    }

    private void updateEditorButtonsState() {
        boolean running = controllerRunning;
        boolean editable = activeEditedFilePath != null && projectFileEditorArea != null && projectFileEditorArea.isEditable();
        if (filesSaveEditorButton != null) {
            filesSaveEditorButton.setDisable(running || !editable || !editorDirty);
        }
        if (filesHistoryButton != null) {
            filesHistoryButton.setDisable(running || activeEditedFilePath == null || activeProjectRoot == null);
        }
        if (fileEditorStatusLabel != null && activeEditedFilePath != null && editable) {
            if (editorDirty) {
                fileEditorStatusLabel.setText("Editing*: " + activeEditedFilePath.getFileName() + " (unsaved changes)");
            } else if (trimToEmpty(fileEditorStatusLabel.getText()).startsWith("Editing*")) {
                fileEditorStatusLabel.setText("Editing: " + activeEditedFilePath.getFileName());
            }
        }
        if (applyPreflightFixButton != null) {
            updatePreflightFixButtonState();
        }
    }

    private void maybeAutosaveEditedFile() {
        if (!editorDirty) {
            return;
        }
        saveEditedFileWithHistory(false);
    }

    private boolean saveEditedFileWithHistory(boolean userInitiated) {
        if (activeEditedFilePath == null || projectFileEditorArea == null || !projectFileEditorArea.isEditable()) {
            return false;
        }
        String content = projectFileEditorArea.getText();
        if (!userInitiated && !editorDirty) {
            return true;
        }
        if (Objects.equals(trimToEmpty(content), trimToEmpty(loadedEditedFileText))) {
            editorDirty = false;
            updateEditorButtonsState();
            return true;
        }

        try {
            boolean createSnapshot = userInitiated || (System.currentTimeMillis() - editorLastSaveMillis) > 60_000L;
            if (createSnapshot) {
                snapshotEditedFile(activeEditedFilePath);
            }

            editorAutoSaving = true;
            Files.writeString(activeEditedFilePath, content, StandardCharsets.UTF_8);
            editorAutoSaving = false;
            loadedEditedFileText = content;
            editorDirty = false;
            editorLastSaveMillis = System.currentTimeMillis();

            if (fileEditorStatusLabel != null) {
                String mode = userInitiated ? "Saved" : "Auto-saved";
                fileEditorStatusLabel.setText(mode + ": " + activeEditedFilePath.getFileName());
            }
            if (userInitiated) {
                addUserLog("SUCCESS", "File saved: " + activeEditedFilePath.getFileName());
            }
            refreshWorkspaceFileFilter();
            updateEditorButtonsState();
            return true;
        } catch (Exception ex) {
            editorAutoSaving = false;
            addTechnicalLog("ERROR", "Failed to save file: " + ex.getMessage());
            if (userInitiated) {
                addUserLog("ERROR", "Save failed.");
            }
            return false;
        }
    }

    private void snapshotEditedFile(Path editedFile) throws Exception {
        if (editedFile == null || activeProjectRoot == null || !Files.exists(editedFile)) {
            return;
        }
        Path rel = activeProjectRoot.relativize(editedFile.toAbsolutePath().normalize());
        String safe = rel.toString().replace('\\', '_').replace('/', '_');
        if (safe.isBlank()) {
            safe = "unknown";
        }
        Path snapshotDir = activeProjectRoot.resolve(".history").resolve(safe);
        Files.createDirectories(snapshotDir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path snapshot = snapshotDir.resolve(stamp + ".bak");
        Files.copy(editedFile, snapshot, StandardCopyOption.REPLACE_EXISTING);
    }

    private void loadRelatedSidecarPreview(Path selectedPath) {
        if (projectSidecarPreviewArea == null) {
            return;
        }
        if (selectedPath == null) {
            projectSidecarPreviewArea.setText("");
            return;
        }

        Path sidecar = null;
        String fileName = trimToEmpty(selectedPath.getFileName() == null ? "" : selectedPath.getFileName().toString());
        if (fileName.endsWith(".diarization.json")) {
            sidecar = selectedPath;
        } else {
            String stem = fileName;
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            Path sibling = selectedPath.resolveSibling(stem + ".diarization.json");
            if (Files.isRegularFile(sibling)) {
                sidecar = sibling;
            }
        }

        if (sidecar == null || !Files.isRegularFile(sidecar)) {
            projectSidecarPreviewArea.setText("No related diarization sidecar found for selected file.");
            return;
        }

        try {
            String raw = Files.readString(sidecar, StandardCharsets.UTF_8);
            JsonNode node = mapper.readTree(raw);
            String preview = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
            if (preview.length() > 200_000) {
                preview = preview.substring(0, 200_000) + "\n...\n(truncated)";
            }
            projectSidecarPreviewArea.setText(preview);
            projectSidecarPreviewArea.positionCaret(0);
        } catch (Exception ex) {
            projectSidecarPreviewArea.setText("Failed to preview sidecar: " + ex.getMessage());
        }
    }

    private void refreshWorkspaceFileFilter() {
        String query = filesSearchField == null ? "" : trimToEmpty(filesSearchField.getText()).toLowerCase(Locale.ROOT);
        boolean searchContent = filesSearchContentBox != null && filesSearchContentBox.isSelected();
        filteredWorkspaceFileRows.setPredicate(row -> matchesWorkspaceFileFilter(row, query, searchContent));
        if (selectedWorkspaceFileRow != null && !filteredWorkspaceFileRows.contains(selectedWorkspaceFileRow)) {
            onWorkspaceFileSelectionChanged(null);
        }
        refreshFilesGallery();
        if (filesDropHintLabel != null) {
            filesDropHintLabel.setText(
                    query.isBlank()
                            ? "Tip: drag and drop files/folders here to import into project input."
                            : "Filtered files: " + filteredWorkspaceFileRows.size() + " of " + workspaceFileRows.size()
            );
        }
        updateFilesSummaryButtonState();
    }

    private void refreshFilesGallery() {
        if (filesInputGalleryBox == null || filesOutputGalleryBox == null) {
            return;
        }
        filesInputGalleryBox.getChildren().clear();
        filesOutputGalleryBox.getChildren().clear();

        boolean hasInput = false;
        boolean hasOutput = false;
        for (WorkspaceFileRow row : filteredWorkspaceFileRows) {
            if (row == null) {
                continue;
            }
            Node card = buildWorkspaceFileGalleryCard(row);
            if (isInputWorkspacePath(row)) {
                filesInputGalleryBox.getChildren().add(card);
                hasInput = true;
            } else if (isOutputWorkspacePath(row)) {
                filesOutputGalleryBox.getChildren().add(card);
                hasOutput = true;
            }
        }

        if (!hasInput) {
            filesInputGalleryBox.getChildren().add(buildFilesGalleryPlaceholder("No input files."));
        }
        if (!hasOutput) {
            filesOutputGalleryBox.getChildren().add(buildFilesGalleryPlaceholder("No output files."));
        }
    }

    private Node buildWorkspaceFileGalleryCard(WorkspaceFileRow row) {
        VBox card = new VBox(4.0);
        card.getStyleClass().add("project-gallery-card");
        if (sameWorkspaceFile(selectedWorkspaceFileRow, row)) {
            card.getStyleClass().add("project-gallery-card-active");
        }
        card.setPadding(new Insets(10.0));
        card.setMinWidth(320.0);
        card.setPrefWidth(340.0);
        card.setMaxWidth(360.0);
        card.setFocusTraversable(true);

        String relative = trimToEmpty(row.getRelativePath());
        String fileName = relative;
        int slash = relative.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < relative.length()) {
            fileName = relative.substring(slash + 1);
        }

        Label title = new Label(fileName);
        title.getStyleClass().add("section-title");
        title.setWrapText(true);

        Label path = new Label(relative);
        path.getStyleClass().add("small-label");
        path.setWrapText(true);

        Label meta = new Label(
                trimToEmpty(row.getType()) + " | " + trimToEmpty(row.getSize()) + " | " + trimToEmpty(row.getModified())
        );
        meta.getStyleClass().add("small-label");
        meta.setWrapText(true);

        card.getChildren().addAll(title, path, meta);
        card.setOnMouseClicked(event -> {
            onWorkspaceFileSelectionChanged(row);
            if (event.getClickCount() >= 2) {
                openPath(row.getAbsolutePath());
            }
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                onWorkspaceFileSelectionChanged(row);
                event.consume();
            }
        });
        return card;
    }

    private Node buildFilesGalleryPlaceholder(String message) {
        Label label = new Label(trimToEmpty(message));
        label.getStyleClass().add("small-label");
        return label;
    }

    private boolean isInputWorkspacePath(WorkspaceFileRow row) {
        String relative = trimToEmpty(row == null ? "" : row.getRelativePath()).toLowerCase(Locale.ROOT);
        return relative.equals("input") || relative.startsWith("input/");
    }

    private boolean isOutputWorkspacePath(WorkspaceFileRow row) {
        String relative = trimToEmpty(row == null ? "" : row.getRelativePath()).toLowerCase(Locale.ROOT);
        return relative.equals("output") || relative.startsWith("output/");
    }

    private boolean sameWorkspaceFile(WorkspaceFileRow left, WorkspaceFileRow right) {
        if (left == null || right == null) {
            return false;
        }
        String leftPath = trimToEmpty(left.getAbsolutePath());
        String rightPath = trimToEmpty(right.getAbsolutePath());
        return !leftPath.isBlank() && Objects.equals(leftPath, rightPath);
    }

    private WorkspaceFileRow currentSelectedWorkspaceFile() {
        if (selectedWorkspaceFileRow != null) {
            return selectedWorkspaceFileRow;
        }
        if (projectFilesTable != null) {
            return projectFilesTable.getSelectionModel().getSelectedItem();
        }
        return null;
    }

    private boolean isAiSummaryEligibleFile(WorkspaceFileRow row) {
        if (row == null) {
            return false;
        }
        String relative = trimToEmpty(row.getRelativePath()).toLowerCase(Locale.ROOT);
        return relative.startsWith("output/") && relative.endsWith(".txt");
    }

    private void updateFilesSummaryButtonState() {
        if (filesAiSummaryButton == null) {
            return;
        }
        boolean running = controllerRunning;
        boolean hasActiveProject = activeProjectRoot != null;
        boolean hasActiveSummary = !trimToEmpty(activeSummaryOperationId).isBlank();
        filesAiSummaryButton.setDisable(
                running
                        || !hasActiveProject
                        || hasActiveSummary
                        || !isAiSummaryEligibleFile(currentSelectedWorkspaceFile())
        );
    }

    private boolean matchesWorkspaceFileFilter(WorkspaceFileRow row, String query, boolean searchContent) {
        if (row == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String rel = trimToEmpty(row.getRelativePath()).toLowerCase(Locale.ROOT);
        String abs = trimToEmpty(row.getAbsolutePath()).toLowerCase(Locale.ROOT);
        if (rel.contains(query) || abs.contains(query)) {
            return true;
        }
        if (!searchContent || query.length() < 3) {
            return false;
        }
        try {
            Path path = Path.of(row.getAbsolutePath());
            if (!isEditableTextFile(path) || safeSize(path) > EDITOR_MAX_BYTES) {
                return false;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return text.contains(query);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updatePreflightFixButtonState() {
        if (applyPreflightFixButton == null) {
            return;
        }
        PreflightCheckRow selected = preflightChecksTable == null ? null : preflightChecksTable.getSelectionModel().getSelectedItem();
        boolean running = controllerRunning;
        boolean enabled = selected != null && !trimToEmpty(selected.getSuggestedFix()).isBlank();
        applyPreflightFixButton.setDisable(running || !enabled);
    }

    private String suggestedFixForCheck(String name, String status) {
        String normalizedName = trimToEmpty(name);
        String normalizedStatus = trimToEmpty(status).toLowerCase(Locale.ROOT);
        if (!"fail".equals(normalizedStatus) && !"warn".equals(normalizedStatus)) {
            return "";
        }
        return switch (normalizedName) {
            case "source_local" -> "Select valid local source";
            case "source_youtube_url" -> "Provide valid YouTube URL";
            case "output_dir" -> "Choose writable output folder";
            case "gpu_request" -> "Switch to CPU mode";
            case "ffmpeg", "torch", "edge_tts", "yt_dlp", "diarization_runtime" -> "Run Repair runtime";
            default -> "Review module settings";
        };
    }

    private void applyPreflightQuickFix(PreflightCheckRow row) {
        if (row == null) {
            return;
        }
        String name = trimToEmpty(row.getName());
        switch (name) {
            case "source_local" -> {
                onBrowseLocalPath();
                addUserLog("INFO", "Quick fix: select valid local input.");
            }
            case "source_youtube_url" -> {
                requestFocusIfVisible(youtubeUrlField);
                addUserLog("INFO", "Quick fix: provide valid YouTube URL.");
            }
            case "output_dir" -> {
                onBrowseOutputDir();
                addUserLog("INFO", "Quick fix: select writable output directory.");
            }
            case "gpu_request" -> {
                if (useGpuBox != null) {
                    useGpuBox.setSelected(false);
                }
                addUserLog("WARN", "Quick fix: switched to CPU mode (disable Prefer GPU).");
            }
            case "ffmpeg", "torch", "edge_tts", "yt_dlp", "diarization_runtime" -> {
                addUserLog("INFO", "Quick fix: launching runtime repair.");
                onRepairRuntime();
            }
            default -> addUserLog("INFO", "No automatic fix for '" + name + "'. " + trimToEmpty(row.getSuggestedFix()));
        }
    }

    private void updateWizardState() {
        boolean projectReady = activeProjectRoot != null && !trimToEmpty(activeProjectId).isBlank();
        boolean running = controllerRunning;
        WizardActivityDefinition activity = resolveWizardActivitySelection();
        boolean activityReady = activity != null && Objects.equals(trimToEmpty(wizardActivityId), activity.id());
        boolean sourceReady = wizardSourceImported;
        boolean youtubeConfirmReady = activity == null
                || !"youtube".equals(activity.sourceMode())
                || wizardYoutubeDownloadConfirmed;
        boolean outputReady = !trimToEmpty(outputDirField == null ? "" : outputDirField.getText()).isBlank();
        boolean preflightPassed = lastPreflightMillis > 0L && lastPreflightOk;
        String activityTitle = activityReady ? activity.title() : "not selected";
        String step1State = activityReady ? "done" : "pending";
        String step2State = sourceReady && youtubeConfirmReady ? "done" : "pending";
        String step3State = lastPreflightMillis <= 0L ? "pending" : (preflightPassed ? "done" : "failed");
        String step4State = projectReady && activityReady && sourceReady && youtubeConfirmReady && preflightPassed
                ? "ready"
                : "blocked";
        String nextAction;
        if (!projectReady) {
            nextAction = "Create or select a project on Dashboard.";
        } else if (!activityReady) {
            nextAction = "Step 1: choose activity.";
        } else if (!sourceReady || !youtubeConfirmReady) {
            nextAction = "Step 2: import source into project input.";
        } else if (!preflightPassed) {
            nextAction = "Step 3: run preflight until all required checks pass.";
        } else {
            nextAction = "Step 4: start workflow.";
        }
        if (wizardStateLabel != null) {
            wizardStateLabel.setText(
                    "Project: " + (projectReady ? "selected" : "required") + "\n"
                            + "1) Activity: " + step1State + " (" + activityTitle + ")\n"
                            + "2) Import: " + step2State + (outputReady ? " | output ready" : " | output missing") + "\n"
                            + "3) Validate: " + step3State
                            + (activityReady && activity != null && "youtube".equals(activity.sourceMode())
                            ? " | YouTube confirm: " + (youtubeConfirmReady ? "done" : "pending")
                            : "")
                            + "\n"
                            + "4) Start: " + step4State + "\n"
                            + "Next action: " + nextAction
            );
        }

        if (wizardNextButton != null) {
            wizardNextButton.setDisable(running || !projectReady);
            wizardNextButton.setText("Run wizard");
        }
        if (wizardSourceButton != null) {
            wizardSourceButton.setDisable(running || !projectReady);
        }
        if (wizardOutputButton != null) {
            wizardOutputButton.setDisable(running || !projectReady || !activityReady);
        }
        if (wizardPreflightButton != null) {
            wizardPreflightButton.setDisable(running || !projectReady || !activityReady || !sourceReady || !youtubeConfirmReady);
        }
        if (wizardRunButton != null) {
            wizardRunButton.setDisable(running || !projectReady || !activityReady || !sourceReady || !youtubeConfirmReady || !preflightPassed);
        }
    }

    private void invalidatePreflightState() {
        lastPreflightMillis = 0L;
        lastPreflightOk = false;
        if (preflightSummaryLabel != null && preflightCheckRows.isEmpty()) {
            preflightSummaryLabel.setText("Preflight not executed yet.");
        }
    }

    private void refreshOperationsDiagnostics() {
        if (operationsStatusLabel != null) {
            operationsStatusLabel.setText("Refreshing...");
        }
        if (backendClient == null) {
            if (operationsStatusLabel != null) {
                operationsStatusLabel.setText("Backend offline");
            }
            if (operationsMetricsLabel != null) {
                operationsMetricsLabel.setText("Diagnostics unavailable: backend is not connected.");
            }
            return;
        }

        CompletableFuture<JsonNode> healthFuture = backendClient.sendRequest("health");
        CompletableFuture<JsonNode> metricsFuture = backendClient.sendRequest("get_system_metrics");
        healthFuture.thenCombine(metricsFuture, (health, metrics) -> List.of(health, metrics))
                .thenAccept(result -> Platform.runLater(() -> applyOperationsDiagnostics(result.get(0), result.get(1))))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (operationsStatusLabel != null) {
                            operationsStatusLabel.setText("Error");
                        }
                        if (operationsMetricsLabel != null) {
                            operationsMetricsLabel.setText("Diagnostics refresh failed: " + rootMessage(ex));
                        }
                        if (operationsDiagnosticsArea != null) {
                            operationsDiagnosticsArea.setText("Refresh failed: " + rootMessage(ex));
                        }
                    });
                    return null;
                });
    }

    private void applyOperationsDiagnostics(JsonNode health, JsonNode metrics) {
        if (operationsStatusLabel != null) {
            operationsStatusLabel.setText("Updated");
        }
        String gpuName = trimToEmpty(metrics.path("gpu_name").asText(""));
        String driverModel = trimToEmpty(metrics.path("nvidia_driver_model").asText(""));
        String vram = formatMetric(metrics.path("vram_used_gb").asDouble(Double.NaN), "GB")
                + " / "
                + formatMetric(metrics.path("vram_total_gb").asDouble(Double.NaN), "GB");
        String cpu = formatMetric(metrics.path("cpu_percent").asDouble(Double.NaN), "%");
        String ram = formatMetric(metrics.path("ram_percent").asDouble(Double.NaN), "%");
        String gpuUtil = formatMetric(metrics.path("gpu_percent").asDouble(Double.NaN), "%");
        String gpuMem = formatMetric(metrics.path("gpu_memory_percent").asDouble(Double.NaN), "%");

        if (operationsMetricsLabel != null) {
            operationsMetricsLabel.setText(
                    "CPU " + cpu
                            + " | RAM " + ram
                            + " | GPU " + gpuUtil
                            + " | VRAM " + gpuMem
                            + " (" + vram + ")"
                            + (gpuName.isBlank() ? "" : " | " + gpuName)
                            + (driverModel.isBlank() ? "" : " | " + driverModel)
            );
        }

        if (operationsDiagnosticsArea != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("Health\n");
            sb.append("- Torch: ").append(health.path("torch_ok").asBoolean(false) ? "OK" : "Missing").append("\n");
            sb.append("- CUDA ready: ").append(health.path("torch_cuda").asBoolean(false)).append("\n");
            sb.append("- Active jobs: ").append(health.path("active_jobs").asInt(0)).append("\n");
            sb.append("- Driver model: ").append(trimToEmpty(health.path("nvidia_driver_model").asText("-"))).append("\n\n");
            sb.append("System metrics\n");
            sb.append("- GPU temperature: ").append(formatMetric(metrics.path("gpu_temp_c").asDouble(Double.NaN), "C")).append("\n");
            sb.append("- GPU power: ").append(formatMetric(metrics.path("gpu_power_w").asDouble(Double.NaN), "W")).append("\n");
            sb.append("- VRAM free: ").append(formatMetric(metrics.path("vram_free_gb").asDouble(Double.NaN), "GB")).append("\n");
            sb.append("- Compute apps:\n");
            JsonNode apps = metrics.path("gpu_compute_apps");
            if (apps.isArray() && apps.size() > 0) {
                int count = Math.min(8, apps.size());
                for (int i = 0; i < count; i++) {
                    JsonNode app = apps.get(i);
                    sb.append("  • pid ").append(app.path("pid").asText("?"))
                            .append(" | ").append(trimToEmpty(app.path("process_name").asText("?")))
                            .append(" | ").append(formatMetric(app.path("used_memory_gb").asDouble(Double.NaN), "GB"))
                            .append("\n");
                }
            } else {
                sb.append("  • no active compute processes\n");
            }
            operationsDiagnosticsArea.setText(sb.toString());
            operationsDiagnosticsArea.positionCaret(0);
        }
    }

    private String formatMetric(double value, String unit) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "n/a";
        }
        String suffix = trimToEmpty(unit);
        if (suffix.equals("%")) {
            return String.format(Locale.ROOT, "%.0f%%", value);
        }
        if (suffix.isBlank()) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.2f %s", value, suffix);
    }

    private void initializeWorkspace() {
        loadWorkspaceState();
        refreshProjectsView();
        refreshProjectFiles();
        refreshDashboardData();
        refreshWorkspaceSettingsUi();
    }

    private void reloadWorkspaceFromCurrentAppDataDir() {
        loadWorkspaceState();
        refreshProjectsView();
        refreshProjectFiles();
        refreshDashboardData();
        refreshWorkspaceSettingsUi();
    }

    private void refreshWorkspaceSettingsUi() {
        Path workspaceRoot = workspaceRootPath();
        boolean running = controllerRunning;
        if (settingsWorkspaceRootField != null) {
            settingsWorkspaceRootField.setText(workspaceRoot == null ? "" : workspaceRoot.toString());
        }
        if (settingsWorkspaceRootInfoLabel != null) {
            settingsWorkspaceRootInfoLabel.setText(
                    "Projects: " + projectsById.size()
                            + " | Active: " + (trimToEmpty(activeProjectId).isBlank() ? "none" : trimToEmpty(activeProjectId))
                            + (workspaceMigrationRunning ? " | Migration in progress..." : "")
            );
        }
        if (settingsChangeWorkspaceRootButton != null) {
            settingsChangeWorkspaceRootButton.setDisable(running || workspaceMigrationRunning);
        }
    }

    private Stage createWorkspaceMigrationProgressStage(Stage owner, Task<?> task) {
        ProgressBar progressBarControl = new ProgressBar();
        progressBarControl.setPrefWidth(420);
        progressBarControl.progressProperty().bind(task.progressProperty());

        Label title = new Label("Moving workspace projects...");
        Label detail = new Label("Preparing...");
        detail.textProperty().bind(task.messageProperty());
        detail.setWrapText(true);

        VBox content = new VBox(10, title, progressBarControl, detail);
        content.setPadding(new Insets(14, 14, 14, 14));
        content.getStyleClass().addAll("app-shell", themeClassFromName(currentTheme));

        Stage stage = new Stage();
        stage.setTitle("Workspace Migration");
        stage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(new Scene(content, 480, 140));
        applyThemeToStage(stage);
        stage.setResizable(false);
        return stage;
    }

    private WorkspaceMigrationResult migrateWorkspaceRoot(
            Path sourceRoot,
            Path targetRoot,
            List<ProjectWorkspace> snapshot,
            String snapshotActiveProjectId,
            WorkspaceMigrationReporter reporter
    ) throws Exception {
        Path normalizedSource = sourceRoot.toAbsolutePath().normalize();
        Path normalizedTarget = targetRoot.toAbsolutePath().normalize();
        if (Objects.equals(normalizedSource, normalizedTarget)) {
            return new WorkspaceMigrationResult(normalizedTarget, snapshot, snapshotActiveProjectId, List.of());
        }

        Files.createDirectories(normalizedTarget);
        Files.createDirectories(normalizedTarget.resolve(WORKSPACE_PROJECTS_DIR));

        List<ProjectWorkspace> migratedProjects = new ArrayList<>();
        List<WorkspaceMigrationFailure> failures = new ArrayList<>();
        int total = Math.max(snapshot.size(), 1);
        long startedAtMillis = System.currentTimeMillis();

        int processed = 0;
        for (ProjectWorkspace workspace : snapshot) {
            String projectId = trimToEmpty(workspace.projectId());
            Path sourceProjectRoot = workspace.rootPath();
            Path targetProjectRoot = normalizedTarget.resolve(WORKSPACE_PROJECTS_DIR).resolve(projectId);
            try {
                if (sourceProjectRoot == null || !Files.exists(sourceProjectRoot)) {
                    throw new IllegalStateException("Project root does not exist.");
                }
                if (Files.exists(targetProjectRoot)) {
                    throw new IllegalStateException("Target project folder already exists.");
                }
                moveDirectoryRobust(sourceProjectRoot, targetProjectRoot);
                migratedProjects.add(new ProjectWorkspace(
                        workspace.projectId(),
                        workspace.name(),
                        targetProjectRoot.toString(),
                        workspace.createdAt(),
                        nowStamp(),
                        workspace.lastOpenedAt()
                ));
            } catch (Exception ex) {
                failures.add(new WorkspaceMigrationFailure(projectId, trimToEmpty(ex.getMessage())));
                migratedProjects.add(workspace);
            }
            processed += 1;
            double progress = processed / (double) total;
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
            long remainingMillis = progress <= 0.0 ? 0L : Math.max(0L, (long) (elapsed / progress - elapsed));
            reporter.report(
                    processed,
                    total,
                    "Moved " + processed + "/" + snapshot.size()
                            + " | Remaining: " + formatRemainingDuration(remainingMillis)
            );
        }
        reporter.report(total, total, "Migration finished. Applying workspace metadata...");

        return new WorkspaceMigrationResult(normalizedTarget, migratedProjects, snapshotActiveProjectId, failures);
    }

    private void moveDirectoryRobust(Path source, Path target) throws Exception {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (Objects.equals(normalizedSource, normalizedTarget)) {
            return;
        }
        Files.createDirectories(normalizedTarget.getParent());
        try {
            Files.move(normalizedSource, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (Exception ignored) {
            // Fallback to non-atomic move/copy.
        }
        try {
            Files.move(normalizedSource, normalizedTarget);
            return;
        } catch (Exception ignored) {
            // Cross-volume fallback.
        }

        copyDirectoryRecursively(normalizedSource, normalizedTarget);
        deleteDirectoryRecursively(normalizedSource);
    }

    private void copyDirectoryRecursively(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteDirectoryRecursively(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String formatRemainingDuration(long remainingMillis) {
        long totalSeconds = Math.max(0L, remainingMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02dm %02ds", minutes, seconds);
    }

    private void loadWorkspaceState() {
        projectsById.clear();
        activeProjectId = "";
        activeProjectRoot = null;
        activeEditedFilePath = null;

        Path workspaceMeta = workspaceMetaPath();
        try {
            Files.createDirectories(workspaceProjectsRootPath());
            if (!Files.isRegularFile(workspaceMeta)) {
                saveWorkspaceState();
                return;
            }
            JsonNode root = mapper.readTree(Files.readString(workspaceMeta, StandardCharsets.UTF_8));
            JsonNode projectsNode = root.path("projects");
            if (projectsNode.isArray()) {
                for (JsonNode node : projectsNode) {
                    String id = trimToEmpty(node.path("project_id").asText(""));
                    String name = trimToEmpty(node.path("name").asText(""));
                    if (id.isBlank() || name.isBlank()) {
                        continue;
                    }
                    String rootDir = trimToEmpty(node.path("root_dir").asText(""));
                    if (rootDir.isBlank()) {
                        rootDir = workspaceProjectsRootPath().resolve(id).toString();
                    }
                    ProjectWorkspace workspace = new ProjectWorkspace(
                            id,
                            name,
                            rootDir,
                            trimToEmpty(node.path("created_at").asText(nowStamp())),
                            trimToEmpty(node.path("updated_at").asText(nowStamp())),
                            trimToEmpty(node.path("last_opened_at").asText(""))
                    );
                    projectsById.put(id, workspace);
                }
            }
            activeProjectId = trimToEmpty(root.path("active_project_id").asText(""));
            if (activeProjectId.isBlank() || !projectsById.containsKey(activeProjectId)) {
                activeProjectId = projectsById.keySet().stream().findFirst().orElse("");
            }
            setActiveProject(activeProjectId, false, false);
        } catch (Exception ex) {
            addTechnicalLog("WARN", "Workspace metadata load failed: " + ex.getMessage());
            projectsById.clear();
            activeProjectId = "";
            activeProjectRoot = null;
            activeEditedFilePath = null;
        }
    }

    private void saveWorkspaceState() {
        Path workspaceMeta = workspaceMetaPath();
        try {
            Files.createDirectories(workspaceMeta.getParent());
            ObjectNode root = mapper.createObjectNode();
            root.put("version", 1);
            root.put("active_project_id", trimToEmpty(activeProjectId));
            ArrayNode projects = mapper.createArrayNode();
            for (ProjectWorkspace workspace : projectsById.values()) {
                ObjectNode item = projects.addObject();
                item.put("project_id", workspace.projectId());
                item.put("name", workspace.name());
                item.put("root_dir", workspace.rootDir());
                item.put("created_at", workspace.createdAt());
                item.put("updated_at", workspace.updatedAt());
                item.put("last_opened_at", workspace.lastOpenedAt());
            }
            root.set("projects", projects);
            Files.writeString(workspaceMeta, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            addTechnicalLog("WARN", "Workspace metadata save failed: " + ex.getMessage());
        }
    }

    private void refreshProjectsView() {
        projectRows.clear();
        for (ProjectWorkspace workspace : projectsById.values()) {
            String status = Objects.equals(workspace.projectId(), activeProjectId) ? "Active" : "-";
            Path rootPath = workspace.rootPath();
            String projectSize = formatBytes(directorySizeBytes(rootPath));
            projectRows.add(new ProjectRow(
                    workspace.projectId(),
                    workspace.name(),
                    status,
                    trimTimestamp(workspace.updatedAt()),
                    projectSize,
                    workspace.rootDir()
            ));
        }
        if (projectTable != null) {
            ProjectRow toSelect = null;
            for (ProjectRow row : projectRows) {
                if (Objects.equals(row.getProjectId(), activeProjectId)) {
                    toSelect = row;
                    break;
                }
            }
            if (toSelect != null) {
                projectTable.getSelectionModel().select(toSelect);
            } else {
                projectTable.getSelectionModel().clearSelection();
            }
        }
        refreshActiveProjectLabels();
        onProjectSelectionChanged(projectTable == null ? null : projectTable.getSelectionModel().getSelectedItem());
        setRunning(controllerRunning);
    }

    private void refreshActiveProjectLabels() {
        ProjectWorkspace active = projectsById.get(activeProjectId);
        String label = active == null
                ? "Active project: none"
                : "Active project: " + active.name() + " (" + active.projectId() + ")";
        if (activeProjectLabel != null) {
            activeProjectLabel.setText(label);
        }
        if (filesActiveProjectLabel != null) {
            filesActiveProjectLabel.setText(active == null ? "Project: none" : "Project: " + active.name());
        }
    }

    private void refreshProjectFiles() {
        workspaceFileRows.clear();
        selectedWorkspaceFileRow = null;
        activeEditedFilePath = null;
        loadedEditedFileText = "";
        editorDirty = false;
        if (projectFileEditorArea != null) {
            projectFileEditorArea.setText("");
            projectFileEditorArea.setEditable(false);
        }
        if (projectSidecarPreviewArea != null) {
            projectSidecarPreviewArea.setText("");
        }

        if (activeProjectRoot == null) {
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Preview/editor: no active project selected.");
            }
            refreshWorkspaceFileFilter();
            return;
        }

        List<Path> candidates = new ArrayList<>();
        for (Path root : List.of(activeProjectRoot.resolve("input"), activeProjectRoot.resolve("output"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(candidates::add);
            } catch (Exception ex) {
                addTechnicalLog("WARN", "Project file refresh failed in " + root + ": " + ex.getMessage());
            }
        }

        candidates.sort((left, right) -> {
            try {
                int byModified = Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
                if (byModified != 0) {
                    return byModified;
                }
            } catch (Exception ignored) {
                // Keep deterministic fallback order.
            }
            String leftRel = activeProjectRoot.relativize(left).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            String rightRel = activeProjectRoot.relativize(right).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            return leftRel.compareTo(rightRel);
        });

        for (Path path : candidates) {
            workspaceFileRows.add(new WorkspaceFileRow(
                    activeProjectRoot.relativize(path).toString().replace('\\', '/'),
                    fileType(path),
                    formatBytes(safeSize(path)),
                    formatFileTime(path),
                    path.toAbsolutePath().normalize().toString()
            ));
        }

        if (fileEditorStatusLabel != null) {
            fileEditorStatusLabel.setText("Preview/editor: select a text file (.txt/.srt/.json/.md) from the gallery.");
        }
        refreshWorkspaceFileFilter();
        if (projectFilesTable != null) {
            projectFilesTable.getSelectionModel().clearSelection();
        }
        updateFilesSummaryButtonState();
        onWorkspaceFileSelectionChanged(null);
        refreshDashboardData();
        setRunning(controllerRunning);
    }

    private void refreshDashboardData() {
        if (dashboardActiveProjectLabel == null) {
            return;
        }

        refreshDashboardProjectGallery();

        if (activeProjectRoot == null) {
            dashboardActiveProjectLabel.setText("Active project: none");
            if (dashboardProjectStatsLabel != null) {
                dashboardProjectStatsLabel.setText("Input: 0 | Output: 0 | Transcripts: 0 | Job snapshots: 0");
            }
            dashboardRecentFileRows.clear();
            if (dashboardRecentFilesTable != null) {
                dashboardRecentFilesTable.getSelectionModel().clearSelection();
            }
            if (dashboardTimelineInfoLabel != null) {
                dashboardTimelineInfoLabel.setText("Timeline: no data yet.");
            }
            if (dashboardTimelineArea != null) {
                dashboardTimelineArea.setText("No active project selected.");
            }
            onDashboardRecentSelectionChanged(null);
            if (dashboardOpenWizardButton != null) {
                boolean running = controllerRunning;
                dashboardOpenWizardButton.setDisable(running || activeProjectRoot == null);
            }
            return;
        }

        ProjectWorkspace active = projectsById.get(activeProjectId);
        String activeName = active == null ? activeProjectId : active.name();
        dashboardActiveProjectLabel.setText("Active project: " + activeName + " (" + trimToEmpty(activeProjectId) + ")");

        long inputCount = countRegularFiles(activeProjectRoot.resolve("input"));
        long outputCount = countRegularFiles(activeProjectRoot.resolve("output"));
        long transcriptCount = countTranscriptArtifacts(activeProjectRoot.resolve("output"));
        long jobCount = countRegularFiles(activeProjectRoot.resolve("jobs"));
        if (dashboardProjectStatsLabel != null) {
            dashboardProjectStatsLabel.setText(
                    "Input: " + inputCount
                            + " | Output: " + outputCount
                            + " | Transcripts: " + transcriptCount
                            + " | Job snapshots: " + jobCount
            );
        }

        dashboardRecentFileRows.clear();
        for (Path path : collectRecentProjectArtifacts(activeProjectRoot, 40)) {
            String relative = activeProjectRoot.relativize(path).toString().replace('\\', '/');
            dashboardRecentFileRows.add(new WorkspaceFileRow(
                    relative,
                    fileType(path),
                    formatBytes(safeSize(path)),
                    formatFileTime(path),
                    path.toAbsolutePath().normalize().toString()
            ));
        }

        Path timelinePath = activeProjectRoot.resolve("jobs").resolve("timeline.jsonl");
        refreshDashboardTimeline(timelinePath, 120);
        onDashboardRecentSelectionChanged(
                dashboardRecentFilesTable == null
                        ? null
                        : dashboardRecentFilesTable.getSelectionModel().getSelectedItem()
        );
        if (dashboardOpenWizardButton != null) {
            boolean running = controllerRunning;
            dashboardOpenWizardButton.setDisable(running || activeProjectRoot == null);
        }
    }

    private void refreshDashboardProjectGallery() {
        if (dashboardProjectGalleryBox == null) {
            return;
        }

        boolean runActionLocked = controllerRunning || isGlobalRunLockedByOtherController();
        if (dashboardProjectGalleryBox.getParent() instanceof javafx.scene.layout.Region region) {
            double wrapLength = Math.max(640.0, region.getWidth() - 24.0);
            dashboardProjectGalleryBox.setPrefWrapLength(wrapLength);
        }
        List<ProjectDashboardCard> cards = new ArrayList<>();
        for (ProjectWorkspace workspace : projectsById.values()) {
            cards.add(buildProjectDashboardCard(workspace));
        }
        cards.sort(
                Comparator.comparingLong(ProjectDashboardCard::sortEpoch).reversed()
                        .thenComparing(card -> trimToEmpty(card.projectName()).toLowerCase(Locale.ROOT))
                        .thenComparing(card -> trimToEmpty(card.projectId()).toLowerCase(Locale.ROOT))
        );

        dashboardProjectGalleryBox.getChildren().clear();
        dashboardProjectGalleryBox.getChildren().add(buildCreateProjectPlaceholderCard(controllerRunning));
        for (ProjectDashboardCard card : cards) {
            dashboardProjectGalleryBox.getChildren().add(buildProjectGalleryCard(card, runActionLocked));
        }

        if (dashboardGalleryInfoLabel != null) {
            if (cards.isEmpty()) {
                dashboardGalleryInfoLabel.setText("No projects yet. Use the Create Project card to start.");
            } else {
                dashboardGalleryInfoLabel.setText("Projects: " + cards.size() + " (sorted by last activity)");
            }
        }
    }

    private Node buildCreateProjectPlaceholderCard(boolean running) {
        VBox box = new VBox(8);
        box.getStyleClass().addAll("project-gallery-card", "project-gallery-card-create");
        box.setMinWidth(320.0);
        box.setPrefWidth(340.0);
        box.setMaxWidth(360.0);
        box.setPadding(new Insets(10, 10, 10, 10));
        box.setAlignment(Pos.CENTER);
        box.setFocusTraversable(true);
        box.setAccessibleText("Create Project");
        box.setDisable(running);

        Label plusLabel = new Label("+");
        plusLabel.getStyleClass().add("project-gallery-plus");

        Label textLabel = new Label("Create Project");
        textLabel.getStyleClass().add("project-gallery-create-label");

        box.getChildren().addAll(plusLabel, textLabel);

        box.setOnMouseClicked(event -> {
            if (!running) {
                onCreateProject();
            }
        });
        box.setOnKeyPressed(event -> {
            if (running) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.ENTER || code == KeyCode.SPACE) {
                onCreateProject();
                event.consume();
            }
        });
        return box;
    }

    private Node buildProjectGalleryCard(ProjectDashboardCard card, boolean runActionLocked) {
        VBox box = new VBox(6);
        box.getStyleClass().add("project-gallery-card");
        box.setMinWidth(320.0);
        box.setPrefWidth(340.0);
        box.setMaxWidth(360.0);
        box.setPadding(new Insets(10, 10, 10, 10));

        Label title = new Label(trimToEmpty(card.projectName()) + " (" + trimToEmpty(card.projectId()) + ")");
        title.getStyleClass().add("section-title");

        Label activityLabel = new Label("Last activity: " + trimToEmpty(card.lastActivity()));
        activityLabel.getStyleClass().add("small-label");

        Label activityDateLabel = new Label("Date: " + trimToEmpty(card.lastActivityAt()));
        activityDateLabel.getStyleClass().add("small-label");

        Label statsLabel = new Label(
                "Input: " + card.inputCount()
                        + " | Output: " + card.outputCount()
                        + " | Transcripts: " + card.transcriptCount()
                        + " | Size: " + formatBytes(card.diskBytes())
        );
        statsLabel.getStyleClass().add("small-label");

        HBox actions = new HBox(8);
        Button openProjectButton = new Button("Open project");
        openProjectButton.getStyleClass().add("accent-btn");
        openProjectButton.setDisable(false);
        openProjectButton.setOnAction(event ->
                openProjectWindowForProject(card.projectId(), false, PROJECT_SECTION_OVERVIEW)
        );

        Button wizardButton = new Button("Open wizard");
        wizardButton.setDisable(runActionLocked);
        wizardButton.setOnAction(event -> openProjectWizard(card.projectId()));

        Button folderButton = new Button("Open folder");
        folderButton.setDisable(card.rootPath() == null);
        folderButton.setOnAction(event -> {
            if (card.rootPath() != null) {
                openPath(card.rootPath().toString());
            }
        });

        actions.getChildren().addAll(openProjectButton, wizardButton, folderButton);
        box.getChildren().addAll(title, activityLabel, activityDateLabel, statsLabel, actions);
        return box;
    }

    private ProjectDashboardCard buildProjectDashboardCard(ProjectWorkspace workspace) {
        Path root = workspace == null ? null : workspace.rootPath();
        long inputCount = countRegularFiles(root == null ? null : root.resolve("input"));
        long outputCount = countRegularFiles(root == null ? null : root.resolve("output"));
        long transcriptCount = countTranscriptArtifacts(root == null ? null : root.resolve("output"));
        long diskBytes = directorySizeBytes(root);

        ActivitySnapshot best = new ActivitySnapshot("No activity yet", null);
        best = newerActivity(best, new ActivitySnapshot("Project created", parseStamp(workspace == null ? "" : workspace.createdAt())));
        best = newerActivity(best, new ActivitySnapshot("Project updated", parseStamp(workspace == null ? "" : workspace.updatedAt())));
        best = newerActivity(best, new ActivitySnapshot("Project opened", parseStamp(workspace == null ? "" : workspace.lastOpenedAt())));
        best = newerActivity(best, readLastTimelineActivity(root == null ? null : root.resolve("jobs").resolve("timeline.jsonl")));

        LocalDateTime filesUpdatedAt = latestFileUpdate(root);
        best = newerActivity(best, new ActivitySnapshot("Files updated", filesUpdatedAt));

        LocalDateTime bestAt = best.at();
        String lastAt = bestAt == null ? "-" : DASHBOARD_ACTIVITY_FMT.format(bestAt);
        long sortEpoch = bestAt == null
                ? 0L
                : bestAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new ProjectDashboardCard(
                workspace == null ? "" : workspace.projectId(),
                workspace == null ? "" : workspace.name(),
                root,
                inputCount,
                outputCount,
                transcriptCount,
                diskBytes,
                trimToEmpty(best.label()),
                lastAt,
                sortEpoch
        );
    }

    private ActivitySnapshot newerActivity(ActivitySnapshot current, ActivitySnapshot candidate) {
        if (candidate == null || candidate.at() == null) {
            return current;
        }
        if (current == null || current.at() == null || candidate.at().isAfter(current.at())) {
            return candidate;
        }
        return current;
    }

    private ActivitySnapshot readLastTimelineActivity(Path timelinePath) {
        if (timelinePath == null || !Files.isRegularFile(timelinePath)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(timelinePath, StandardCharsets.UTF_8);
            for (int idx = lines.size() - 1; idx >= 0; idx -= 1) {
                String line = trimToEmpty(lines.get(idx));
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode row = mapper.readTree(line);
                    LocalDateTime ts = parseStamp(row.path("ts").asText(""));
                    String message = trimToEmpty(row.path("line").asText(""));
                    if (message.isBlank()) {
                        String event = trimToEmpty(row.path("event").asText(""));
                        String status = trimToEmpty(row.path("status").asText(""));
                        String step = trimToEmpty(row.path("step").asText(""));
                        message = buildTimelineActivityLabel(event, status, step);
                    }
                    if (message.isBlank()) {
                        message = "Job event";
                    }
                    return new ActivitySnapshot(trimForDashboard(message, 90), ts);
                } catch (Exception ignored) {
                    // Continue scanning previous line.
                }
            }
        } catch (Exception ignored) {
            // Timeline read errors are non-blocking for dashboard rendering.
        }
        return null;
    }

    private static String buildTimelineActivityLabel(String event, String status, String step) {
        String eventText = trimToEmpty(event);
        String statusText = trimToEmpty(status);
        String stepText = trimToEmpty(step);
        StringBuilder sb = new StringBuilder();
        if (!eventText.isBlank()) {
            sb.append(eventText);
        }
        if (!statusText.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(statusText);
        }
        if (!stepText.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(stepText);
        }
        return sb.toString();
    }

    private static String trimForDashboard(String text, int maxLen) {
        String safe = trimToEmpty(text);
        if (safe.length() <= maxLen) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private LocalDateTime latestFileUpdate(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return null;
        }
        long newestEpochMillis = -1L;
        for (Path root : List.of(
                projectRoot.resolve("input"),
                projectRoot.resolve("output"),
                projectRoot.resolve("jobs")
        )) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                long localMax = stream
                        .filter(Files::isRegularFile)
                        .mapToLong(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis();
                            } catch (Exception ignored) {
                                return -1L;
                            }
                        })
                        .max()
                        .orElse(-1L);
                if (localMax > newestEpochMillis) {
                    newestEpochMillis = localMax;
                }
            } catch (Exception ignored) {
                // Ignore one subtree and keep scanning.
            }
        }
        if (newestEpochMillis < 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(newestEpochMillis), ZoneId.systemDefault());
    }

    private long directorySizeBytes(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(MainController::safeSize)
                    .sum();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static LocalDateTime parseStamp(String value) {
        String safe = trimTimestamp(value);
        if (safe.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(safe, STAMP_FMT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void refreshDashboardTimeline(Path timelinePath, int maxLines) {
        if (dashboardTimelineArea == null) {
            return;
        }

        if (timelinePath == null || !Files.isRegularFile(timelinePath)) {
            dashboardTimelineArea.setText("No timeline data yet. Timeline is created after the first job event.");
            if (dashboardTimelineInfoLabel != null) {
                dashboardTimelineInfoLabel.setText("Timeline: no data yet.");
            }
            return;
        }

        try {
            List<String> lines = Files.readAllLines(timelinePath, StandardCharsets.UTF_8);
            int total = lines.size();
            int start = Math.max(0, total - Math.max(1, maxLines));
            List<String> tail = lines.subList(start, total);
            StringBuilder sb = new StringBuilder();
            for (String raw : tail) {
                String line = trimToEmpty(raw);
                if (line.isBlank()) {
                    continue;
                }
                String formatted = formatTimelineLine(line);
                sb.append(formatted).append("\n");
            }
            if (sb.length() == 0) {
                sb.append("Timeline file exists, but no readable entries were found.");
            }
            dashboardTimelineArea.setText(sb.toString().trim());
            dashboardTimelineArea.positionCaret(0);
            if (dashboardTimelineInfoLabel != null) {
                dashboardTimelineInfoLabel.setText(
                        "Timeline: " + total + " entries (showing last " + (total - start) + ")."
                );
            }
        } catch (Exception ex) {
            dashboardTimelineArea.setText("Failed to load timeline: " + ex.getMessage());
            if (dashboardTimelineInfoLabel != null) {
                dashboardTimelineInfoLabel.setText("Timeline: read error.");
            }
            addTechnicalLog("WARN", "Dashboard timeline load failed: " + ex.getMessage());
        }
    }

    private String formatTimelineLine(String line) {
        try {
            JsonNode row = mapper.readTree(line);
            String ts = trimTimestamp(row.path("ts").asText(""));
            String event = trimToEmpty(row.path("event").asText(""));
            String status = trimToEmpty(row.path("status").asText(""));
            String step = trimToEmpty(row.path("step").asText(""));
            String overall = row.has("overall_pct")
                    ? String.format(Locale.ROOT, "%.0f%%", row.path("overall_pct").asDouble(0.0))
                    : "";
            String textLine = trimToEmpty(row.path("line").asText(""));

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(ts.isBlank() ? "-" : ts).append("] ");
            sb.append(event.isBlank() ? "event" : event);
            if (!status.isBlank()) {
                sb.append(" | ").append(status);
            }
            if (!step.isBlank()) {
                sb.append(" | ").append(step);
            }
            if (!overall.isBlank()) {
                sb.append(" | ").append(overall);
            }
            if (!textLine.isBlank()) {
                sb.append(" | ").append(textLine);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return line;
        }
    }

    private List<Path> collectRecentProjectArtifacts(Path projectRoot, int limit) {
        if (projectRoot == null) {
            return List.of();
        }

        List<Path> candidates = new ArrayList<>();
        List<Path> roots = List.of(projectRoot.resolve("output"));

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(candidates::add);
            } catch (Exception ignored) {
                // Ignore one subtree and keep the rest.
            }
        }

        candidates.sort((left, right) -> {
            try {
                return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
            } catch (Exception ignored) {
                return right.toString().compareToIgnoreCase(left.toString());
            }
        });

        if (limit <= 0 || candidates.size() <= limit) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, limit));
    }

    private long countRegularFiles(Path root) {
        if (root == null || !Files.exists(root)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long countTranscriptArtifacts(Path outputRoot) {
        if (outputRoot == null || !Files.exists(outputRoot)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(outputRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".txt") || name.endsWith(".md"))
                    .count();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void setActiveProject(String projectId, boolean persist, boolean logChange) {
        String normalized = trimToEmpty(projectId);
        String previousProjectId = trimToEmpty(activeProjectId);
        if (!previousProjectId.isBlank() && !Objects.equals(previousProjectId, normalized)) {
            saveActiveModuleState();
        }
        if (normalized.isBlank() || !projectsById.containsKey(normalized)) {
            activeProjectId = "";
            activeProjectRoot = null;
            activeEditedFilePath = null;
            refreshActiveProjectLabels();
            refreshJobsSilently();
            applySettingsScopeContext();
            return;
        }

        ProjectWorkspace existing = projectsById.get(normalized);
        Path root = existing.rootPath();
        if (root == null) {
            activeProjectId = "";
            activeProjectRoot = null;
            activeEditedFilePath = null;
            refreshActiveProjectLabels();
            return;
        }

        try {
            ensureProjectStructure(root);
        } catch (Exception ex) {
            addTechnicalLog("ERROR", "Cannot prepare project directories: " + ex.getMessage());
            return;
        }

        ProjectWorkspace effective = existing;
        if (persist) {
            String now = nowStamp();
            effective = new ProjectWorkspace(
                    existing.projectId(),
                    existing.name(),
                    root.toString(),
                    existing.createdAt(),
                    now,
                    now
            );
            projectsById.put(effective.projectId(), effective);
        }
        activeProjectId = effective.projectId();
        activeProjectRoot = root;
        activeEditedFilePath = null;
        wizardSourceImported = false;
        wizardYoutubeDownloadConfirmed = false;
        applyActiveProjectToRunFields();
        activateModule(activeModule, false, true);
        invalidatePreflightState();
        refreshActiveProjectLabels();
        refreshDashboardData();
        refreshJobsSilently();
        applySettingsScopeContext();
        updateProjectWorkspaceWindowHeader();
        if (persist) {
            saveWorkspaceState();
        }
        if (logChange) {
            addUserLog("INFO", "Active project: " + effective.name());
        }
    }

    private void initializeProjectSettingsFromGlobalDefaults(String projectId) {
        String normalizedProjectId = trimToEmpty(projectId);
        if (normalizedProjectId.isBlank()) {
            return;
        }
        for (String module : SUPPORTED_MODULES) {
            String globalPrefix = modulePrefPrefix(module);
            String projectPrefix = projectModulePrefPrefix(normalizedProjectId, module);
            if (preferences.hasAnyWithPrefix(projectPrefix)) {
                continue;
            }
            if (preferences.hasAnyWithPrefix(globalPrefix)) {
                preferences.copyPrefix(globalPrefix, projectPrefix, true);
            }
        }
        preferences.flush();
    }

    private void applyActiveProjectToRunFields() {
        if (activeProjectRoot == null) {
            return;
        }
        boolean autoProjectOutput = preferences.getBoolean(PREF_PROJECT_AUTO_OUTPUT, true);
        if (autoProjectOutput && outputDirField != null) {
            outputDirField.setText(activeProjectRoot.resolve("output").toString());
        }
        if (localPathField != null && trimToEmpty(localPathField.getText()).isBlank()) {
            localPathField.setText(activeProjectRoot.resolve("input").toString());
        }
    }

    private void onProjectSelectionChanged(ProjectRow selected) {
        boolean hasSelection = selected != null;
        boolean running = controllerRunning;
        if (projectSelectButton != null) {
            projectSelectButton.setDisable(running || !hasSelection);
        }
        if (projectDeleteButton != null) {
            projectDeleteButton.setDisable(running || !hasSelection);
        }
        if (projectOpenFolderButton != null) {
            projectOpenFolderButton.setDisable(running || (!hasSelection && activeProjectRoot == null));
        }
    }

    private void onDashboardRecentSelectionChanged(WorkspaceFileRow selected) {
        boolean running = controllerRunning;
        if (dashboardOpenSelectedRecentButton != null) {
            dashboardOpenSelectedRecentButton.setDisable(running || selected == null);
        }
    }

    private void onWorkspaceFileSelectionChanged(WorkspaceFileRow selected) {
        selectedWorkspaceFileRow = selected;
        boolean running = controllerRunning;
        if (filesOpenSelectedButton != null) {
            filesOpenSelectedButton.setDisable(running || selected == null);
        }
        updateFilesSummaryButtonState();
        updateEditorButtonsState();

        // Prevent losing edits when changing selection.
        maybeAutosaveEditedFile();

        if (selected == null) {
            activeEditedFilePath = null;
            loadedEditedFileText = "";
            editorDirty = false;
            if (projectFileEditorArea != null) {
                projectFileEditorArea.setText("");
                projectFileEditorArea.setEditable(false);
            }
            if (projectSidecarPreviewArea != null) {
                projectSidecarPreviewArea.setText("");
            }
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Preview/editor: select a text file (.txt/.srt/.json/.md) from the gallery.");
            }
            refreshFilesGallery();
            updateEditorButtonsState();
            return;
        }

        Path path;
        try {
            path = Path.of(selected.getAbsolutePath()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            activeEditedFilePath = null;
            loadedEditedFileText = "";
            editorDirty = false;
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Invalid file path: " + selected.getAbsolutePath());
            }
            if (projectSidecarPreviewArea != null) {
                projectSidecarPreviewArea.setText("");
            }
            refreshFilesGallery();
            updateEditorButtonsState();
            return;
        }

        activeEditedFilePath = null;
        if (!isEditableTextFile(path)) {
            if (projectFileEditorArea != null) {
                projectFileEditorArea.setText("Binary/unsupported preview in built-in editor.\nUse 'Open selected' to open externally.");
                projectFileEditorArea.setEditable(false);
            }
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Not editable here: " + path.getFileName());
            }
            loadedEditedFileText = "";
            editorDirty = false;
            loadRelatedSidecarPreview(path);
            refreshFilesGallery();
            updateEditorButtonsState();
            return;
        }

        long size = safeSize(path);
        if (size > EDITOR_MAX_BYTES) {
            if (projectFileEditorArea != null) {
                projectFileEditorArea.setText("File is too large for in-app editor (" + formatBytes(size) + ").");
                projectFileEditorArea.setEditable(false);
            }
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Large file, preview blocked: " + path.getFileName());
            }
            loadedEditedFileText = "";
            editorDirty = false;
            loadRelatedSidecarPreview(path);
            refreshFilesGallery();
            updateEditorButtonsState();
            return;
        }

        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            loadedEditedFileText = text;
            if (projectFileEditorArea != null) {
                projectFileEditorArea.setText(text);
                projectFileEditorArea.positionCaret(0);
                projectFileEditorArea.setEditable(true);
            }
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Editing: " + path.getFileName());
            }
            activeEditedFilePath = path;
            editorDirty = false;
            loadRelatedSidecarPreview(path);
            refreshFilesGallery();
            updateEditorButtonsState();
        } catch (Exception ex) {
            addTechnicalLog("WARN", "Unable to read project file: " + ex.getMessage());
            if (projectFileEditorArea != null) {
                projectFileEditorArea.setText("Failed to load file.");
                projectFileEditorArea.setEditable(false);
            }
            if (projectSidecarPreviewArea != null) {
                projectSidecarPreviewArea.setText("");
            }
            if (fileEditorStatusLabel != null) {
                fileEditorStatusLabel.setText("Read failed: " + path.getFileName());
            }
            activeEditedFilePath = null;
            loadedEditedFileText = "";
            editorDirty = false;
            refreshFilesGallery();
            updateEditorButtonsState();
        }
    }

    private void ensureProjectStructure(Path projectRoot) throws Exception {
        Files.createDirectories(projectRoot);
        Files.createDirectories(projectRoot.resolve("input"));
        Files.createDirectories(projectRoot.resolve("output"));
        Files.createDirectories(projectRoot.resolve("jobs"));
        Files.createDirectories(projectRoot.resolve("logs"));
        Files.createDirectories(projectRoot.resolve("assets"));
        Files.createDirectories(projectRoot.resolve("temp"));
        Path projectMeta = projectRoot.resolve("project.json");
        if (!Files.exists(projectMeta)) {
            ObjectNode projectNode = mapper.createObjectNode();
            projectNode.put("project_id", projectRoot.getFileName().toString());
            projectNode.put("name", projectRoot.getFileName().toString());
            projectNode.put("created_at", nowStamp());
            Files.writeString(
                    projectMeta,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projectNode),
                    StandardCharsets.UTF_8
            );
        }
    }

    private Path workspaceRootPath() {
        String configuredRoot = trimToEmpty(preferences.get(PREF_WORKSPACE_ROOT, ""));
        if (!configuredRoot.isBlank()) {
            try {
                return Path.of(configuredRoot).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // Fall back to default app data workspace root.
            }
        }
        return AppRuntimePaths.resolveDefaultWorkspaceRoot(currentAppDataPath());
    }

    private Path workspaceMetaPath() {
        return workspaceRootPath().resolve(WORKSPACE_META_FILE);
    }

    private Path workspaceProjectsRootPath() {
        return workspaceRootPath().resolve(WORKSPACE_PROJECTS_DIR);
    }

    private Path currentAppDataPath() {
        String fromBackend = trimToEmpty(appDataDir);
        if (!fromBackend.isBlank()) {
            try {
                return Path.of(fromBackend).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // fallback to local resolution
            }
        }
        return resolveRuntimeAppDataDir().toAbsolutePath().normalize();
    }

    private String sanitizeProjectName(String value) {
        String normalized = trimToEmpty(value).replaceAll("[\\r\\n]+", " ");
        normalized = normalized.replaceAll("\\s{2,}", " ");
        return normalized;
    }

    private String buildProjectId(String projectName) {
        String base = sanitizeProjectName(projectName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) {
            base = "project";
        }
        String candidate = base;
        int suffix = 2;
        while (projectsById.containsKey(candidate) || Files.exists(workspaceProjectsRootPath().resolve(candidate))) {
            candidate = base + "-" + suffix;
            suffix += 1;
        }
        return candidate;
    }

    private static String fileType(Path path) {
        String fileName = path == null ? "" : trimToEmpty(path.getFileName().toString());
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "file";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static String formatFileTime(Path path) {
        try {
            LocalDateTime dt = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault());
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return "-";
        }
    }

    private static boolean isEditableTextFile(Path path) {
        String ext = fileType(path);
        return Set.of("txt", "srt", "vtt", "md", "json", "csv", "log", "yaml", "yml", "ass").contains(ext);
    }

    private static Path resolveUniqueTargetPath(Path directory, Path fileName) {
        Path candidate = directory.resolve(fileName.getFileName().toString());
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String name = trimToEmpty(fileName.getFileName().toString());
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int idx = 2;
        while (true) {
            Path next = directory.resolve(stem + "-" + idx + ext);
            if (!Files.exists(next)) {
                return next;
            }
            idx += 1;
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw ex;
        }
    }

    @FXML
    private void onModuleSwitcherChanged() {
        if (moduleSwitcherSync) {
            return;
        }
        String selectedLabel = safeValue(moduleSwitcherBox);
        String moduleId = moduleLabelsToId.get(selectedLabel);
        if (moduleId == null || moduleId.isBlank()) {
            return;
        }
        activateModule(moduleId, true, true);
    }

    @FXML
    private void onModulePrevious() {
        String raw = trimToEmpty(previousModule);
        if (raw.isBlank()) {
            addUserLog("WARN", "No previous module available.");
            return;
        }
        String candidate = normalizeModuleId(raw);
        if (Objects.equals(candidate, activeModule)) {
            addUserLog("WARN", "No previous module available.");
            return;
        }
        activateModule(candidate, true, true);
    }

    @FXML
    private void onModuleOffline() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_OFFLINE, true);
    }

    @FXML
    private void onModuleYoutube() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_YOUTUBE, true);
    }

    @FXML
    private void onModuleSpeaker() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_SPEAKER, true);
    }

    @FXML
    private void onModuleConference() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_CONFERENCE, true);
    }

    @FXML
    private void onModuleYoutubeSubtitles() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_YOUTUBE_SUBS, true);
    }

    @FXML
    private void onModuleYoutubeDub() {
        showShellSection(SECTION_MODULES);
        activateModule(MODULE_YOUTUBE_DUB, true);
    }

    @FXML
    private void onModuleSettings() {
        showShellSection(SECTION_MODULES);
        selectModule(settingsTab);
    }

    @FXML
    private void onOpenAppSettings() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("App Settings");
        dialog.setHeaderText("Global application settings");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ComboBox<String> languageSelector = new ComboBox<>();
        if (uiLanguageBox != null) {
            languageSelector.setItems(FXCollections.observableArrayList(uiLanguageBox.getItems()));
            languageSelector.getSelectionModel().select(normalizeUiLanguage(safeValue(uiLanguageBox)));
        }
        languageSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || uiLanguageBox == null) {
                return;
            }
            uiLanguageBox.getSelectionModel().select(normalizeUiLanguage(newVal));
            onUiLanguageChanged();
        });

        ComboBox<String> themeSelector = new ComboBox<>();
        if (themeBox != null) {
            themeSelector.setItems(FXCollections.observableArrayList(themeBox.getItems()));
            themeSelector.getSelectionModel().select(normalizeThemeName(safeValue(themeBox)));
        }
        themeSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || themeBox == null) {
                return;
            }
            themeBox.getSelectionModel().select(normalizeThemeName(newVal));
            onThemeChanged();
        });

        Button refreshCacheButton = new Button("Refresh model cache");
        refreshCacheButton.setOnAction(event -> onRefreshModels());

        Button repairRuntimeButton = new Button("Repair runtime");
        repairRuntimeButton.setOnAction(event -> onRepairRuntime());

        Button openAppDataButton = new Button("Open app data");
        openAppDataButton.setOnAction(event -> onOpenData());

        Button openOutputButton = new Button("Open output");
        openOutputButton.setOnAction(event -> onOpenOutput());

        TextField workspaceField = new TextField(workspaceRootPath().toString());
        workspaceField.setEditable(false);
        workspaceField.getStyleClass().add("readonly-preview-field");
        workspaceField.setPrefWidth(520.0);

        Button changeWorkspaceButton = new Button("Change...");
        changeWorkspaceButton.setOnAction(event -> {
            onChangeWorkspaceRoot();
            workspaceField.setText(workspaceRootPath().toString());
        });

        GridPane appearanceGrid = new GridPane();
        appearanceGrid.setHgap(10.0);
        appearanceGrid.setVgap(8.0);
        appearanceGrid.add(new Label("UI language"), 0, 0);
        appearanceGrid.add(languageSelector, 1, 0);
        appearanceGrid.add(new Label("Theme"), 0, 1);
        appearanceGrid.add(themeSelector, 1, 1);

        HBox runtimeActions = new HBox(8.0, refreshCacheButton, repairRuntimeButton, openAppDataButton, openOutputButton);
        runtimeActions.setAlignment(Pos.CENTER_LEFT);

        HBox workspaceRow = new HBox(8.0, new Label("Workspace root"), workspaceField, changeWorkspaceButton);
        workspaceRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(workspaceField, Priority.ALWAYS);

        VBox content = new VBox(
                12.0,
                new Label("Appearance"),
                appearanceGrid,
                new Label("Runtime tools"),
                runtimeActions,
                workspaceRow
        );
        content.setPadding(new Insets(8.0, 4.0, 4.0, 4.0));
        content.getStyleClass().add("card-pane");

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(760.0);
        Stage owner = getMainStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        applyThemeToDialog(dialog);
        dialog.showAndWait();
    }

    @FXML
    private void onThemeChanged() {
        if (themeInitializing) {
            return;
        }
        applyTheme(themeBox.getValue(), true);
    }

    @FXML
    private void onUiLanguageChanged() {
        String normalized = normalizeUiLanguage(safeValue(uiLanguageBox));
        if (Objects.equals(currentUiLanguage, normalized)) {
            return;
        }
        currentUiLanguage = normalized;
        preferences.put(PREF_UI_LANGUAGE, normalized);
        preferences.flush();
        addUserLog("INFO", "UI language set to '" + normalized + "'.");
    }

    @FXML
    private void onChangeWorkspaceRoot() {
        if (workspaceMigrationRunning) {
            return;
        }
        Stage stage = getStage();
        if (stage == null) {
            return;
        }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose workspace root");
        Path currentRoot = workspaceRootPath();
        try {
            Path initial = Files.isDirectory(currentRoot)
                    ? currentRoot
                    : (currentRoot == null ? null : currentRoot.getParent());
            if (initial != null && Files.isDirectory(initial)) {
                chooser.setInitialDirectory(initial.toFile());
            }
        } catch (Exception ignored) {
            // Best effort only.
        }

        File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }

        Path targetRoot;
        try {
            targetRoot = selected.toPath().toAbsolutePath().normalize();
        } catch (Exception ex) {
            addUserLog("ERROR", "Invalid workspace root.");
            return;
        }
        if (Objects.equals(currentRoot, targetRoot)) {
            addUserLog("INFO", "Workspace root is already set to this location.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Move workspace root");
        confirm.setHeaderText("Move all project data to new workspace root?");
        confirm.setContentText(
                "Current: " + currentRoot + "\n"
                        + "New: " + targetRoot + "\n\n"
                        + "This will move project folders and update workspace metadata.\n"
                        + "A progress window with remaining time will be shown.\n"
                        + "If a project move fails, migration continues and failed projects are listed."
        );
        confirm.initOwner(stage);
        applyThemeToDialog(confirm);
        Optional<ButtonType> decision = confirm.showAndWait();
        if (decision.isEmpty() || decision.get() != ButtonType.OK) {
            return;
        }

        List<ProjectWorkspace> snapshot = new ArrayList<>(projectsById.values());
        String snapshotActiveProjectId = trimToEmpty(activeProjectId);
        Task<WorkspaceMigrationResult> task = new Task<>() {
            @Override
            protected WorkspaceMigrationResult call() throws Exception {
                return migrateWorkspaceRoot(currentRoot, targetRoot, snapshot, snapshotActiveProjectId, (processed, total, message) -> {
                    updateProgress(processed, total);
                    updateMessage(message);
                });
            }
        };

        Stage progressStage = createWorkspaceMigrationProgressStage(stage, task);
        workspaceMigrationRunning = true;
        refreshWorkspaceSettingsUi();

        task.setOnSucceeded(event -> {
            workspaceMigrationRunning = false;
            progressStage.close();
            WorkspaceMigrationResult result = task.getValue();
            preferences.put(PREF_WORKSPACE_ROOT, result.targetRoot().toString());
            preferences.flush();

            projectsById.clear();
            for (ProjectWorkspace workspace : result.projects()) {
                projectsById.put(workspace.projectId(), workspace);
            }

            String nextActive = trimToEmpty(result.activeProjectId());
            if (nextActive.isBlank() || !projectsById.containsKey(nextActive)) {
                nextActive = projectsById.keySet().stream().findFirst().orElse("");
            }
            setActiveProject(nextActive, false, false);
            saveWorkspaceState();
            refreshProjectsView();
            refreshProjectFiles();
            refreshDashboardData();
            refreshWorkspaceSettingsUi();
            applySettingsScopeContext();

            int failureCount = result.failures().size();
            if (failureCount == 0) {
                addUserLog("SUCCESS", "Workspace root moved to: " + result.targetRoot());
                return;
            }

            StringBuilder details = new StringBuilder();
            result.failures().stream().limit(12).forEach(failure ->
                    details.append("- ").append(failure.projectId()).append(": ").append(failure.message()).append("\n")
            );
            if (failureCount > 12) {
                details.append("... and ").append(failureCount - 12).append(" more");
            }
            Alert partial = new Alert(Alert.AlertType.WARNING);
            partial.setTitle("Workspace migration completed with issues");
            partial.setHeaderText("Some projects could not be moved");
            partial.setContentText(details.toString().trim());
            partial.initOwner(stage);
            applyThemeToDialog(partial);
            partial.showAndWait();
            addUserLog("WARN", "Workspace moved with " + failureCount + " failed project(s).");
        });

        task.setOnFailed(event -> {
            workspaceMigrationRunning = false;
            progressStage.close();
            refreshWorkspaceSettingsUi();
            Throwable ex = task.getException();
            String message = ex == null ? "Unknown migration error." : rootMessage(ex);
            addTechnicalLog("ERROR", "Workspace migration failed: " + message);
            addUserLog("ERROR", "Workspace migration failed: " + message);
        });

        Thread worker = new Thread(task, "tm-workspace-migration");
        worker.setDaemon(true);
        worker.start();
        progressStage.show();
    }

    @FXML
    private void onResetProjectModuleFromDefaults() {
        String projectId = trimToEmpty(activeProjectId);
        if (projectId.isBlank()) {
            addUserLog("WARN", "No active project selected.");
            return;
        }
        String module = normalizeModuleId(activeModule);
        String projectPrefix = projectModulePrefPrefix(projectId, module);
        String globalPrefix = modulePrefPrefix(module);
        preferences.removeByPrefix(projectPrefix);
        if (preferences.hasAnyWithPrefix(globalPrefix)) {
            preferences.copyPrefix(globalPrefix, projectPrefix, true);
        }
        preferences.flush();

        boolean loaded = loadModuleState(module);
        if (!loaded) {
            applyModuleDefaults(module);
            saveActiveModuleState();
        }
        refreshModulePresetList(null);
        addUserLog("INFO", "Project module settings reset from global defaults (" + moduleLabel(module) + ").");
    }

    @FXML
    private void onSaveModuleAsGlobalDefaults() {
        String module = normalizeModuleId(activeModule);
        saveActiveModuleState();
        String sourcePrefix = effectiveModulePrefPrefix(module);
        String globalPrefix = modulePrefPrefix(module);
        if (!preferences.hasAnyWithPrefix(sourcePrefix)) {
            addUserLog("WARN", "Nothing to save as global defaults.");
            return;
        }
        preferences.removeByPrefix(globalPrefix);
        preferences.copyPrefix(sourcePrefix, globalPrefix, true);
        preferences.flush();
        addUserLog("SUCCESS", "Global defaults updated from current module state (" + moduleLabel(module) + ").");
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
        if (ENFORCE_GUIDED_WIZARD) {
            onRunProjectWizard();
            return;
        }
        if (runtimeBootstrapRunning) {
            addUserLog("WARN", "Online runtime setup is in progress. Wait until it completes.");
            return;
        }
        if (!ensureWorkflowProjectReady()) {
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
        startPipelineRequest(params, "manual");
    }

    /**
     * Starts pipeline with mandatory preflight gate.
     *
     * Sequence:
     * 1) Acquire global run lock
     * 2) Run preflight gate (interactive when needed)
     * 3) Send run_pipeline request
     * 4) Initialize current job UI state
     */
    private void startPipelineRequest(ObjectNode params, String trigger) {
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }
        if (!acquireGlobalRunLock()) {
            addUserLog("WARN", "Another project job is already running. Wait until it finishes.");
            return;
        }
        ObjectNode workingParams = params == null ? mapper.createObjectNode() : params.deepCopy();

        setRunning(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        stepLabel.setText("preflight_check");
        statusLabel.setText("Preflight");
        resetEtaDisplay();

        runPreflightGateAsync(workingParams, true, trigger)
                .thenCompose(continueRun -> {
                    if (!continueRun) {
                        releaseGlobalRunLockIfOwned();
                        return CompletableFuture.completedFuture(null);
                    }
                    Platform.runLater(() -> {
                        statusLabel.setText("Starting");
                        stepLabel.setText("run_pipeline");
                    });
                    return backendClient.sendRequest("run_pipeline", workingParams);
                })
                .thenAccept(result -> Platform.runLater(() -> {
                    if (result == null || result.isNull()) {
                        setRunning(false);
                        releaseGlobalRunLockIfOwned();
                        statusLabel.setText("Ready");
                        stepLabel.setText("-");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                        return;
                    }
                    currentJobId = result.path("job_id").asText("");
                    String moduleId = normalizeModuleId(workingParams.path("module").asText(activeModule));
                    String sourceMode = workingParams.path("source").path("mode").asText(sourceModeBox.getValue());
                    statusLabel.setText("Running");
                    showJobProgressWindow(currentJobId, moduleLabel(moduleId), sourceMode);
                    if ("replay".equals(trigger)) {
                        addUserLog("INFO", "Replay started: " + shortJobId(currentJobId));
                    } else {
                        addUserLog("INFO", "Job started: " + shortJobId(currentJobId));
                    }
                    upsertJobRow(currentJobId, "running", moduleLabel(moduleId), sourceMode, nowStamp());
                    refreshJobsSilently();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setRunning(false);
                        releaseGlobalRunLockIfOwned();
                        statusLabel.setText("Error");
                        stepLabel.setText("-");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                        addTechnicalLog("ERROR", "Failed to start job: " + rootMessage(ex));
                        if (jobProgressWindow != null && jobProgressWindow.isShowing()) {
                            jobProgressWindow.markFailed(rootMessage(ex));
                        }
                    });
                    return null;
                });
    }

    @FXML
    private void onRunPreflight() {
        if (controllerRunning) {
            addUserLog("WARN", "A job is running. Wait until it finishes.");
            return;
        }
        if (isGlobalRunLockedByOtherController()) {
            addUserLog("WARN", "Another project job is running. Wait until it finishes.");
            return;
        }
        if (!ensureWorkflowProjectReady()) {
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
        statusLabel.setText("Preflight");
        stepLabel.setText("preflight_check");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        resetEtaDisplay();

        ObjectNode workingParams = params == null ? mapper.createObjectNode() : params.deepCopy();
        runPreflightGateAsync(workingParams, false, "manual_preflight")
                .thenAccept(result -> Platform.runLater(() -> {
                    statusLabel.setText(lastPreflightOk ? "Preflight OK" : "Preflight issues");
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

    private CompletableFuture<Boolean> runPreflightGateAsync(
            ObjectNode workingParams,
            boolean requireContinueForStart,
            String trigger
    ) {
        // First refresh checks, then always resolve user decision in the gate dialog.
        addUserLog("INFO", "Running preflight gate (" + trimToEmpty(trigger) + ")...");
        return runPreflightAsync(workingParams, true)
                .thenCompose(ok -> showPreflightGateDialogAsync(workingParams, requireContinueForStart, trigger));
    }

    /**
     * Shows preflight gate modal used for quick fixes / re-run / continue decisions.
     */
    private CompletableFuture<Boolean> showPreflightGateDialogAsync(
            ObjectNode workingParams,
            boolean requireContinueForStart,
            String trigger
    ) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        Platform.runLater(() -> {
            Stage owner = getStage();
            Stage dialog = new Stage();
            if (owner != null) {
                dialog.initOwner(owner);
            }
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle("Preflight gate");

            Label title = new Label("Preflight checks");
            title.getStyleClass().add("section-title");
            Label subtitle = new Label(
                    requireContinueForStart
                            ? "Job start is blocked until all FAIL checks are resolved."
                            : "Manual preflight run for current project state."
            );
            subtitle.setWrapText(true);
            subtitle.getStyleClass().add("small-label");

            Label summary = new Label();
            summary.getStyleClass().add("small-label");

            ObservableList<PreflightCheckRow> gateRows = FXCollections.observableArrayList();
            gateRows.setAll(snapshotPreflightRows(preflightCheckRows));

            TableView<PreflightCheckRow> gateTable = new TableView<>(gateRows);
            gateTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT_COLUMNS);
            TableColumn<PreflightCheckRow, String> nameColumn = new TableColumn<>("Check");
            nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
            nameColumn.setPrefWidth(170.0);
            TableColumn<PreflightCheckRow, String> statusColumn = new TableColumn<>("Status");
            statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
            statusColumn.setPrefWidth(100.0);
            TableColumn<PreflightCheckRow, String> messageColumn = new TableColumn<>("Message");
            messageColumn.setCellValueFactory(new PropertyValueFactory<>("message"));
            messageColumn.setPrefWidth(430.0);
            TableColumn<PreflightCheckRow, String> fixColumn = new TableColumn<>("Suggested fix");
            fixColumn.setCellValueFactory(new PropertyValueFactory<>("suggestedFix"));
            fixColumn.setPrefWidth(220.0);
            gateTable.getColumns().setAll(nameColumn, statusColumn, messageColumn, fixColumn);
            gateTable.setPrefHeight(260.0);

            Button applyFixButton = new Button("Apply selected fix");
            Button rerunButton = new Button("Re-run checks");
            Button cancelButton = new Button("Cancel");
            Button continueButton = new Button(requireContinueForStart ? "Continue" : "Close");
            continueButton.getStyleClass().add("accent-btn");
            final boolean[] rerunRequired = {false};

            Runnable refreshSummaryAndActions = () -> {
                long failCount = gateRows.stream()
                        .filter(row -> "fail".equalsIgnoreCase(trimToEmpty(row.getStatus())))
                        .count();
                long warnCount = gateRows.stream()
                        .filter(row -> "warn".equalsIgnoreCase(trimToEmpty(row.getStatus())))
                        .count();

                if (failCount == 0L) {
                    if (warnCount > 0L) {
                        summary.setText("Preflight OK for start (" + warnCount + " warning(s)).");
                    } else {
                        summary.setText("Preflight OK. No blocking issues.");
                    }
                } else {
                    summary.setText("Blocking issues: " + failCount + " fail, " + warnCount + " warn.");
                }
                if (requireContinueForStart && rerunRequired[0]) {
                    summary.setText(summary.getText() + " Re-run checks after quick fix.");
                }

                PreflightCheckRow selected = gateTable.getSelectionModel().getSelectedItem();
                boolean hasFix = selected != null && !trimToEmpty(selected.getSuggestedFix()).isBlank();
                applyFixButton.setDisable(!hasFix);
                if (requireContinueForStart) {
                    continueButton.setDisable(failCount > 0L || rerunRequired[0]);
                } else {
                    continueButton.setDisable(false);
                }
            };
            gateTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> refreshSummaryAndActions.run());

            applyFixButton.setOnAction(event -> {
                PreflightCheckRow selected = gateTable.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    addUserLog("WARN", "Select a preflight check first.");
                    return;
                }
                applyPreflightQuickFix(selected);
                synchronizePreflightWorkingParams(workingParams, trigger);
                addUserLog("INFO", "Preflight quick fix applied for '" + selected.getName() + "'.");
                rerunRequired[0] = true;
                if (requireContinueForStart) {
                    continueButton.setDisable(true);
                }
                summary.setText("Quick fix applied. Re-run checks to refresh status.");
            });

            rerunButton.setOnAction(event -> {
                rerunButton.setDisable(true);
                applyFixButton.setDisable(true);
                cancelButton.setDisable(true);
                continueButton.setDisable(true);
                summary.setText("Re-running preflight checks...");

                runPreflightAsync(workingParams, true)
                        .thenAccept(ok -> Platform.runLater(() -> {
                            gateRows.setAll(snapshotPreflightRows(preflightCheckRows));
                            rerunRequired[0] = false;
                            refreshSummaryAndActions.run();
                            rerunButton.setDisable(false);
                            cancelButton.setDisable(false);
                            addUserLog("INFO", "Preflight re-run finished in gate popup.");
                        }))
                        .exceptionally(ex -> {
                            Platform.runLater(() -> {
                                rerunButton.setDisable(false);
                                cancelButton.setDisable(false);
                                refreshSummaryAndActions.run();
                                addTechnicalLog("ERROR", "Preflight re-run failed: " + rootMessage(ex));
                            });
                            return null;
                        });
            });

            cancelButton.setOnAction(event -> {
                if (!decision.isDone()) {
                    addUserLog("WARN", "Preflight gate cancelled by user.");
                    decision.complete(false);
                }
                dialog.close();
            });

            continueButton.setOnAction(event -> {
                if (requireContinueForStart) {
                    long failCount = gateRows.stream()
                            .filter(row -> "fail".equalsIgnoreCase(trimToEmpty(row.getStatus())))
                            .count();
                    if (failCount > 0L) {
                        return;
                    }
                    addUserLog("INFO", "Preflight gate continued by user (" + trimToEmpty(trigger) + ").");
                } else {
                    addUserLog("INFO", "Manual preflight dialog closed.");
                }
                if (!decision.isDone()) {
                    decision.complete(true);
                }
                dialog.close();
            });

            dialog.setOnCloseRequest(event -> {
                if (!decision.isDone()) {
                    addUserLog("WARN", "Preflight gate cancelled by user.");
                    decision.complete(false);
                }
            });

            HBox actions = new HBox(8.0, applyFixButton, rerunButton, new Region(), cancelButton, continueButton);
            HBox.setHgrow(actions.getChildren().get(2), Priority.ALWAYS);
            VBox root = new VBox(10.0, title, subtitle, summary, gateTable, actions);
            root.setPadding(new Insets(12.0));
            root.getStyleClass().add("app-shell");
            dialog.setScene(new Scene(root, 980.0, 430.0));
            dialog.setMinWidth(900.0);
            dialog.setMinHeight(400.0);
            applyThemeToStage(dialog);

            refreshSummaryAndActions.run();
            dialog.show();
            dialog.toFront();
            dialog.requestFocus();
        });
        return decision;
    }

    private List<PreflightCheckRow> snapshotPreflightRows(List<PreflightCheckRow> sourceRows) {
        if (sourceRows == null || sourceRows.isEmpty()) {
            return List.of();
        }
        List<PreflightCheckRow> rows = new ArrayList<>();
        for (PreflightCheckRow row : sourceRows) {
            rows.add(new PreflightCheckRow(
                    trimToEmpty(row.getName()),
                    trimToEmpty(row.getStatus()),
                    trimToEmpty(row.getMessage()),
                    trimToEmpty(row.getSuggestedFix())
            ));
        }
        return rows;
    }

    private void synchronizePreflightWorkingParams(ObjectNode workingParams, String trigger) {
        if (workingParams == null) {
            return;
        }
        String normalizedTrigger = trimToEmpty(trigger).toLowerCase(Locale.ROOT);
        if (!"replay".equals(normalizedTrigger)) {
            ObjectNode refreshed = buildPipelineParams();
            workingParams.removeAll();
            workingParams.setAll(refreshed);
            return;
        }

        ObjectNode source = ensureObjectNode(workingParams, "source");
        ObjectNode output = ensureObjectNode(workingParams, "output");
        if (sourceModeBox != null) {
            source.put("mode", safeValue(sourceModeBox));
        }
        if (localPathField != null) {
            source.put("path", trimToEmpty(localPathField.getText()));
        }
        if (youtubeUrlField != null) {
            source.put("url", trimToEmpty(youtubeUrlField.getText()));
        }
        if (outputDirField != null) {
            output.put("out_dir", trimToEmpty(outputDirField.getText()));
        }
        if (outputPrefixField != null) {
            output.put("output_prefix", trimToEmpty(outputPrefixField.getText()));
        }
        if (useGpuBox != null) {
            workingParams.put("use_gpu", useGpuBox.isSelected());
        }
    }

    private static ObjectNode ensureObjectNode(ObjectNode parent, String fieldName) {
        if (parent == null) {
            return null;
        }
        JsonNode existing = parent.path(fieldName);
        if (existing.isObject()) {
            return (ObjectNode) existing;
        }
        return parent.putObject(fieldName);
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
        applyThemeToDialog(repairDialog);

        Optional<ButtonType> choice = repairDialog.showAndWait();
        if (choice.isEmpty() || choice.get() != startRepairButton) {
            addUserLog("INFO", "Runtime repair cancelled.");
            return;
        }

        showRuntimeBootstrapWindow(target, false, true, false);
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

    private void ensureJobProgressWindow() {
        if (jobProgressWindow != null) {
            return;
        }
        jobProgressWindow = new JobProgressWindow();
        jobProgressWindow.applyThemeClass(themeClassFromName(currentTheme));
    }

    private void showJobProgressWindow(String jobId, String moduleName, String sourceMode) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        ensureJobProgressWindow();
        jobProgressWindow.showForJob(jobId, moduleName, sourceMode, this::onCancel);
    }

    private void updateJobProgressWindowFromEvent(String jobId, JsonNode payload, double overall, boolean indeterminate, String step) {
        if (jobProgressWindow == null || jobId == null || jobId.isBlank() || !jobId.equals(currentJobId)) {
            return;
        }

        JsonNode stepPctNode = payload.path("step_pct");
        Double stepPct = stepPctNode.isNumber() ? stepPctNode.asDouble(0.0) : null;
        int itemIndex = payload.path("item_index").asInt(0);
        int totalItems = payload.path("total_items").asInt(0);
        String overallEtaText = etaLabel == null ? "--:--" : trimToEmpty(etaLabel.getText());

        jobProgressWindow.updateProgress(
                step,
                stepPct,
                overall,
                indeterminate,
                itemIndex,
                totalItems,
                overallEtaText
        );
    }

    private void ensureFilesSummaryWindow() {
        if (filesSummaryStage != null) {
            return;
        }
        Stage owner = getStage();
        filesSummaryStage = new Stage();
        if (owner != null) {
            filesSummaryStage.initOwner(owner);
        }
        filesSummaryStage.initModality(Modality.WINDOW_MODAL);
        filesSummaryStage.setTitle("AI Summary");

        Label title = new Label("Generate AI Summary");
        title.getStyleClass().add("section-title");

        filesSummarySelectedFileLabel = new Label("Selected file: -");
        filesSummarySelectedFileLabel.getStyleClass().add("small-label");
        filesSummarySelectedFileLabel.setWrapText(true);

        filesSummaryLangPicker = new ComboBox<>(FXCollections.observableArrayList(summaryLangBox.getItems()));
        filesSummaryLangPicker.setMaxWidth(Double.MAX_VALUE);
        filesSummaryLangPicker.getSelectionModel().select(safeValue(summaryLangBox));

        filesSummaryTierPicker = new ComboBox<>(FXCollections.observableArrayList(
                summaryModelTierBox == null
                        ? List.of("low", "medium", "high")
                        : summaryModelTierBox.getItems()
        ));
        filesSummaryTierPicker.setMaxWidth(Double.MAX_VALUE);
        filesSummaryTierPicker.getSelectionModel().select(
                summaryModelTierBox == null ? "medium" : safeValue(summaryModelTierBox)
        );

        filesSummaryStatusLabel = new Label("Ready.");
        filesSummaryStatusLabel.getStyleClass().add("small-label");

        filesSummaryProgressBar = new ProgressBar(0.0);
        filesSummaryProgressBar.setMaxWidth(Double.MAX_VALUE);

        filesSummaryLogArea = new TextArea();
        filesSummaryLogArea.setEditable(false);
        filesSummaryLogArea.setWrapText(true);
        filesSummaryLogArea.setPrefRowCount(12);

        filesSummaryStartButton = new Button("Start");
        filesSummaryStartButton.getStyleClass().add("accent-btn");
        filesSummaryStartButton.setOnAction(event -> startFilesSummaryOperation());

        filesSummaryOpenOutputButton = new Button("Open output");
        filesSummaryOpenOutputButton.setDisable(true);
        filesSummaryOpenOutputButton.setOnAction(event -> {
            if (!trimToEmpty(activeSummaryOutputPath).isBlank()) {
                openPath(activeSummaryOutputPath);
            }
        });

        Button closeButton = new Button("Close");
        closeButton.setOnAction(event -> filesSummaryStage.hide());

        HBox actions = new HBox(8.0, filesSummaryStartButton, filesSummaryOpenOutputButton, closeButton);

        VBox root = new VBox(
                8.0,
                title,
                filesSummarySelectedFileLabel,
                new Label("Summary language"),
                filesSummaryLangPicker,
                new Label("Model tier"),
                filesSummaryTierPicker,
                filesSummaryStatusLabel,
                filesSummaryProgressBar,
                new Label("Logs"),
                filesSummaryLogArea,
                actions
        );
        root.setPadding(new Insets(12.0));
        root.getStyleClass().addAll("app-shell", "card-pane", themeClassFromName(currentTheme));

        Scene scene = new Scene(root, 760, 560);
        scene.getStylesheets().add(getClass().getResource("/com/transcribemate/v2/fx/styles.css").toExternalForm());
        filesSummaryStage.setScene(scene);
    }

    private void showFilesSummaryWindow(WorkspaceFileRow selected) {
        ensureFilesSummaryWindow();
        if (selected != null && filesSummarySelectedFileLabel != null) {
            filesSummarySelectedFileLabel.setText("Selected file: " + trimToEmpty(selected.getRelativePath()));
        }
        if (filesSummaryLangPicker != null) {
            selectComboValue(filesSummaryLangPicker, safeValue(summaryLangBox));
        }
        if (filesSummaryTierPicker != null && summaryModelTierBox != null) {
            selectComboValue(filesSummaryTierPicker, safeValue(summaryModelTierBox));
        }
        if (filesSummaryStatusLabel != null) {
            filesSummaryStatusLabel.setText("Ready.");
        }
        if (filesSummaryProgressBar != null) {
            filesSummaryProgressBar.setProgress(0.0);
        }
        if (filesSummaryLogArea != null) {
            filesSummaryLogArea.clear();
        }
        if (filesSummaryOpenOutputButton != null) {
            filesSummaryOpenOutputButton.setDisable(true);
        }
        if (filesSummaryStartButton != null) {
            filesSummaryStartButton.setDisable(false);
        }
        activeSummaryOperationId = "";
        activeSummaryOutputPath = "";
        updateFilesSummaryButtonState();
        applyThemeToStage(filesSummaryStage);
        filesSummaryStage.show();
        filesSummaryStage.toFront();
    }

    private void startFilesSummaryOperation() {
        WorkspaceFileRow selected = currentSelectedWorkspaceFile();
        if (!isAiSummaryEligibleFile(selected)) {
            if (filesSummaryStatusLabel != null) {
                filesSummaryStatusLabel.setText("Select one output .txt transcript file.");
            }
            return;
        }
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }
        ProjectWorkspace activeWorkspace = projectsById.get(activeProjectId);
        if (activeWorkspace == null || activeProjectRoot == null) {
            addUserLog("WARN", "Project is required.");
            return;
        }

        activeSummaryOperationId = UUID.randomUUID().toString();
        activeSummaryOutputPath = "";
        if (filesSummaryStartButton != null) {
            filesSummaryStartButton.setDisable(true);
        }
        if (filesSummaryOpenOutputButton != null) {
            filesSummaryOpenOutputButton.setDisable(true);
        }
        if (filesSummaryStatusLabel != null) {
            filesSummaryStatusLabel.setText("Starting...");
        }
        if (filesSummaryProgressBar != null) {
            filesSummaryProgressBar.setProgress(0.0);
        }
        if (filesSummaryLogArea != null) {
            filesSummaryLogArea.clear();
        }
        appendFilesSummaryLog("Request submitted for: " + trimToEmpty(selected.getRelativePath()));
        updateFilesSummaryButtonState();

        ObjectNode params = mapper.createObjectNode();
        params.put("operation_id", activeSummaryOperationId);
        params.put("transcript_path", trimToEmpty(selected.getAbsolutePath()));
        params.put("summary_lang", filesSummaryLangPicker == null ? safeValue(summaryLangBox) : safeValue(filesSummaryLangPicker));
        params.put("summary_ai_tier", filesSummaryTierPicker == null ? "medium" : safeValue(filesSummaryTierPicker));
        params.set("project", buildProjectContextParamsNode(activeWorkspace, activeProjectRoot));

        backendClient.sendRequest("summarize_transcript", params)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (!Objects.equals(trimToEmpty(result.path("operation_id").asText("")), trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    String outputPath = trimToEmpty(result.path("output_path").asText(""));
                    if (!outputPath.isBlank()) {
                        markFilesSummaryCompleted(outputPath);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (trimToEmpty(activeSummaryOperationId).isBlank()) {
                            return;
                        }
                        markFilesSummaryFailed(rootMessage(ex));
                    });
                    return null;
                });
    }

    private ObjectNode buildProjectContextParamsNode(ProjectWorkspace workspace, Path root) {
        ObjectNode project = mapper.createObjectNode();
        if (workspace == null || root == null) {
            return project;
        }
        Path projectRoot = root.toAbsolutePath().normalize();
        project.put("project_id", workspace.projectId());
        project.put("name", workspace.name());
        project.put("root_dir", projectRoot.toString());
        project.put("input_dir", projectRoot.resolve("input").toString());
        project.put("output_dir", projectRoot.resolve("output").toString());
        project.put("jobs_dir", projectRoot.resolve("jobs").toString());
        project.put("logs_dir", projectRoot.resolve("logs").toString());
        project.put("timeline_path", projectRoot.resolve("jobs").resolve("timeline.jsonl").toString());
        return project;
    }

    private void appendFilesSummaryLog(String line) {
        if (filesSummaryLogArea == null) {
            return;
        }
        String text = trimToEmpty(line);
        if (text.isBlank()) {
            return;
        }
        filesSummaryLogArea.appendText(text + "\n");
        filesSummaryLogArea.setScrollTop(Double.MAX_VALUE);
    }

    private void updateFilesSummaryProgress(String step, double overallPct, boolean indeterminate) {
        if (filesSummaryStatusLabel != null) {
            filesSummaryStatusLabel.setText(step + " (" + String.format(Locale.ROOT, "%.0f", overallPct) + "%)");
        }
        if (filesSummaryProgressBar != null) {
            if (indeterminate) {
                filesSummaryProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            } else {
                filesSummaryProgressBar.setProgress(Math.max(0.0, Math.min(1.0, overallPct / 100.0)));
            }
        }
    }

    private void markFilesSummaryCompleted(String outputPath) {
        activeSummaryOutputPath = trimToEmpty(outputPath);
        if (filesSummaryStatusLabel != null) {
            filesSummaryStatusLabel.setText("Completed.");
        }
        if (filesSummaryProgressBar != null) {
            filesSummaryProgressBar.setProgress(1.0);
        }
        if (filesSummaryStartButton != null) {
            filesSummaryStartButton.setDisable(false);
        }
        if (filesSummaryOpenOutputButton != null) {
            filesSummaryOpenOutputButton.setDisable(activeSummaryOutputPath.isBlank());
        }
        appendFilesSummaryLog("Summary ready: " + activeSummaryOutputPath);
        addUserLog("SUCCESS", "AI summary completed: " + activeSummaryOutputPath);
        activeSummaryOperationId = "";
        refreshProjectFiles();
        updateFilesSummaryButtonState();
    }

    private void markFilesSummaryFailed(String error) {
        if (filesSummaryStatusLabel != null) {
            filesSummaryStatusLabel.setText("Failed.");
        }
        if (filesSummaryProgressBar != null) {
            filesSummaryProgressBar.setProgress(0.0);
        }
        if (filesSummaryStartButton != null) {
            filesSummaryStartButton.setDisable(false);
        }
        if (filesSummaryOpenOutputButton != null) {
            filesSummaryOpenOutputButton.setDisable(true);
        }
        String message = trimToEmpty(error);
        appendFilesSummaryLog("ERROR: " + (message.isBlank() ? "Unknown error." : message));
        addTechnicalLog("ERROR", "AI summary failed: " + (message.isBlank() ? "Unknown error." : message));
        activeSummaryOperationId = "";
        activeSummaryOutputPath = "";
        updateFilesSummaryButtonState();
    }

    @FXML
    private void onRefreshJobs() {
        refreshJobs(true);
    }

    @FXML
    private void onReplaySelectedJob() {
        if (backendClient == null) {
            return;
        }

        JobRow selected = jobHistoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            addUserLog("WARN", "Select a job first.");
            return;
        }

        replayJobButton.setDisable(true);
        ObjectNode params = mapper.createObjectNode();
        params.put("job_id", selected.getJobId());
        backendClient.sendRequest("get_job", params)
                .thenAccept(result -> Platform.runLater(() -> {
                    replayJobButton.setDisable(false);
                    JsonNode requestNode = result.path("request");
                    if (!requestNode.isObject()) {
                        addTechnicalLog("ERROR", "Replay failed: selected job has no request payload.");
                        return;
                    }

                    ObjectNode replayParams = ((ObjectNode) requestNode).deepCopy();
                    String replayModule = normalizeModuleId(replayParams.path("module").asText(activeModule));
                    activateModule(replayModule, false, true);
                    addUserLog(
                            "INFO",
                            "Replaying job "
                                    + shortJobId(selected.getJobId())
                                    + " in "
                                    + moduleLabel(replayModule)
                                    + "."
                    );
                    startPipelineRequest(replayParams, "replay");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        replayJobButton.setDisable(false);
                        addTechnicalLog("ERROR", "Replay failed: " + rootMessage(ex));
                    });
                    return null;
                });
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
    private void onApplySpeakerMappingToLastOutput() {
        if (backendClient == null) {
            addTechnicalLog("ERROR", "Backend is not initialized.");
            return;
        }
        if (lastDiarizationSidecarPath == null) {
            addUserLog("WARN", "No diarization output available yet. Complete a speaker-aware job first.");
            return;
        }
        if (!Files.isRegularFile(lastDiarizationSidecarPath)) {
            addUserLog("WARN", "Last diarization sidecar was not found: " + lastDiarizationSidecarPath);
            updateSpeakerMappingButtonState(false);
            return;
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("sidecar_path", lastDiarizationSidecarPath.toString());
        ObjectNode speakerMap = params.putObject("speaker_map");
        collectSpeakerProfiles().forEach(speakerMap::put);

        updateSpeakerMappingButtonState(true);
        backendClient.sendRequest("apply_speaker_mapping", params)
                .thenAccept(result -> Platform.runLater(() -> {
                    int rewrittenCount = result.path("rewritten_count").asInt(0);
                    JsonNode mapNode = result.path("speaker_map");
                    if (mapNode.isObject()) {
                        mergeSpeakerProfilesFromMapNode(mapNode);
                    }
                    addUserLog(
                            "SUCCESS",
                            "Speaker mapping applied to output files"
                                    + (rewrittenCount > 0 ? " (" + rewrittenCount + " files updated)." : ".")
                    );
                    JsonNode rewritten = result.path("rewritten_paths");
                    if (rewritten.isArray()) {
                        for (JsonNode pathNode : rewritten) {
                            String path = trimToEmpty(pathNode.asText(""));
                            if (!path.isBlank()) {
                                addUserLog("INFO", "Updated: " + path);
                            }
                        }
                    }
                    updateSpeakerMappingButtonState(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        addTechnicalLog("ERROR", "Speaker mapping update failed: " + rootMessage(ex));
                        updateSpeakerMappingButtonState(false);
                    });
                    return null;
                });
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
        if (userLogArea != null) {
            userLogArea.clear();
        }
        if (technicalLogArea != null) {
            technicalLogArea.clear();
        }
        if (filteredLogArea != null) {
            filteredLogArea.clear();
        }
        selectedJobLogFilter = "";
        if (logsJobFilterLabel != null) {
            logsJobFilterLabel.setText("Log scope: all jobs");
        }
        if (logsStreamInfoLabel != null) {
            logsStreamInfoLabel.setText("Showing 0 entries.");
        }
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

        if (summaryModelTierBox != null) {
            summaryModelTierBox.setItems(FXCollections.observableArrayList("low", "medium", "high"));
            summaryModelTierBox.getSelectionModel().select("medium");
        }

        targetLangBox.setItems(FXCollections.observableArrayList(
                "en->cs", "en->sk", "en->de", "en->pl", "en->fr", "en->es", "en->it", "en->ru", "en->uk", "en->pt"
        ));
        targetLangBox.getSelectionModel().select("en->cs");

        subtitleModeBox.setItems(FXCollections.observableArrayList("soft", "hard"));
        subtitleModeBox.getSelectionModel().select("soft");

        diarizationBackendBox.setItems(FXCollections.observableArrayList(
                DIARIZATION_BACKEND_FAST,
                DIARIZATION_BACKEND_ACCURATE
        ));
        diarizationBackendBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return diarizationBackendLabel(value);
            }

            @Override
            public String fromString(String value) {
                return normalizeDiarizationBackend(value);
            }
        });
        diarizationBackendBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : diarizationBackendLabel(item));
            }
        });
        diarizationBackendBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : diarizationBackendLabel(item));
            }
        });
        diarizationBackendBox.getSelectionModel().select(DIARIZATION_BACKEND_ACCURATE);

        diarizationAccuracyBox.setItems(FXCollections.observableArrayList(
                DIARIZATION_ACCURACY_LOW,
                DIARIZATION_ACCURACY_BALANCED,
                DIARIZATION_ACCURACY_HIGH,
                DIARIZATION_ACCURACY_MAXIMUM
        ));
        diarizationAccuracyBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return diarizationAccuracyProfileLabel(value);
            }

            @Override
            public String fromString(String value) {
                return normalizeDiarizationAccuracyProfile(value);
            }
        });
        diarizationAccuracyBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : diarizationAccuracyProfileLabel(item));
            }
        });
        diarizationAccuracyBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : diarizationAccuracyProfileLabel(item));
            }
        });
        diarizationAccuracyBox.getSelectionModel().select(DIARIZATION_ACCURACY_BALANCED);

        modelField.setItems(FXCollections.observableArrayList(WHISPER_MODELS));
        selectModel("large-v3");

        batchSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 128, 16));
        splitMinutesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 720, 0));
        subtitleSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(8, 96, 24));
        subtitleOutlineWidthSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 12, 2));
        diarizationMinSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, 0));
        diarizationMaxSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 32, 0));

        autoModelBox.setSelected(false);
        useGpuBox.setText(AppRuntimePaths.isMac()
                ? "Prefer GPU (CUDA/NVIDIA only; macOS uses CPU-only in this release)"
                : "Prefer GPU (CUDA/NVIDIA)");
        useGpuBox.setSelected(!AppRuntimePaths.isMac());
        cleanTextBox.setSelected(false);
        exportMdBox.setSelected(false);
        summaryPackBox.setSelected(false);
        if (summaryAiBox != null) {
            summaryAiBox.setSelected(false);
        }
        if (summaryModelTierBox != null) {
            summaryModelTierBox.setDisable(summaryAiBox == null || !summaryAiBox.isSelected());
        }
        notifyDoneBox.setSelected(true);
        keepOriginalsBox.setSelected(false);
        translateSubtitlesBox.setSelected(true);

        diarizationEnabledBox.setSelected(false);
        diarizationReviewBox.setSelected(false);
        diarizationIncludeUnmappedBox.setSelected(true);
        diarizationPrefixSrtBox.setSelected(true);
        diarizationProfilePrefillBox.setSelected(true);

        subtitleFontField.setText("Arial");
        outputDirField.setText(Path.of(System.getProperty("user.home"), "Downloads").toString());
        conferenceTitleField.setText("");
        conferenceDateField.setText("");

        subtitleColorPicker.setValue(javafx.scene.paint.Color.WHITE);
        subtitleOutlineColorPicker.setValue(javafx.scene.paint.Color.BLACK);

        if (summaryAiBox != null) {
            summaryAiBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (summaryModelTierBox != null) {
                    summaryModelTierBox.setDisable(newVal == null || !newVal);
                }
                updateFilesSummaryButtonState();
            });
        }
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

        jobHistoryTable.setItems(filteredJobRows);
        jobIdColumn.setCellValueFactory(new PropertyValueFactory<>("jobId"));
        jobStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        jobModeColumn.setCellValueFactory(new PropertyValueFactory<>("mode"));
        jobSourceColumn.setCellValueFactory(new PropertyValueFactory<>("source"));
        jobCreatedColumn.setCellValueFactory(new PropertyValueFactory<>("created"));

        if (projectTable != null) {
            projectTable.setItems(projectRows);
            projectNameColumn.setCellValueFactory(new PropertyValueFactory<>("projectName"));
            projectStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
            projectUpdatedColumn.setCellValueFactory(new PropertyValueFactory<>("updated"));
            if (projectSizeColumn != null) {
                projectSizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
            }
            projectPathColumn.setCellValueFactory(new PropertyValueFactory<>("path"));
            projectTable.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> onProjectSelectionChanged(newItem));
        }

        if (projectFilesTable != null) {
            projectFilesTable.setItems(filteredWorkspaceFileRows);
            projectFileRelativePathColumn.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
            projectFileTypeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
            projectFileSizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
            projectFileModifiedColumn.setCellValueFactory(new PropertyValueFactory<>("modified"));
            projectFilesTable.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> onWorkspaceFileSelectionChanged(newItem));
        }

        if (preflightChecksTable != null) {
            preflightChecksTable.setItems(preflightCheckRows);
            preflightNameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
            preflightStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
            preflightMessageColumn.setCellValueFactory(new PropertyValueFactory<>("message"));
            preflightFixColumn.setCellValueFactory(new PropertyValueFactory<>("suggestedFix"));
            preflightChecksTable.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> updatePreflightFixButtonState());
        }

        if (dashboardRecentFilesTable != null) {
            dashboardRecentFilesTable.setItems(dashboardRecentFileRows);
            dashboardRecentPathColumn.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
            dashboardRecentTypeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
            dashboardRecentModifiedColumn.setCellValueFactory(new PropertyValueFactory<>("modified"));
            dashboardRecentFilesTable.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> onDashboardRecentSelectionChanged(newItem));
        }

        if (operationsJobsTable != null) {
            operationsJobsTable.setItems(filteredJobRows);
            operationsJobIdColumn.setCellValueFactory(new PropertyValueFactory<>("jobId"));
            operationsJobStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
            operationsJobModeColumn.setCellValueFactory(new PropertyValueFactory<>("mode"));
            operationsJobCreatedColumn.setCellValueFactory(new PropertyValueFactory<>("created"));
            operationsJobsTable.getSelectionModel().selectedItemProperty()
                    .addListener((obs, oldVal, newVal) -> {
                        if (newVal != null && jobHistoryTable != null) {
                            jobHistoryTable.getSelectionModel().select(newVal);
                        }
                    });
        }
    }

    private void setupFilters() {
        logLevelFilterBox.setItems(FXCollections.observableArrayList("ALL", "INFO", "WARN", "ERROR", "SUCCESS", "DEBUG"));
        logLevelFilterBox.getSelectionModel().select("ALL");

        logSearchField.textProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        logLevelFilterBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        showUserLogsBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        showTechnicalLogsBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        if (logsErrorsOnlyBox != null) {
            logsErrorsOnlyBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        }
        if (logsPauseAutoscrollBox != null) {
            logsPauseAutoscrollBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        }
        if (logsLinkSelectedJobBox != null) {
            logsLinkSelectedJobBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshFilteredLogs());
        }

        if (jobsStatusFilterBox != null) {
            jobsStatusFilterBox.setItems(FXCollections.observableArrayList(
                    "ALL",
                    "queued",
                    "running",
                    "completed",
                    "failed",
                    "cancelled"
            ));
            jobsStatusFilterBox.getSelectionModel().select("ALL");
            jobsStatusFilterBox.valueProperty().addListener((obs, oldVal, newVal) -> refreshJobFilters());
        }
        if (jobsSearchField != null) {
            jobsSearchField.textProperty().addListener((obs, oldVal, newVal) -> refreshJobFilters());
        }
        if (jobsActiveModuleOnlyBox != null) {
            jobsActiveModuleOnlyBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
                refreshJobsSilently();
                refreshJobFilters();
            });
        }

        if (settingsSearchField != null) {
            settingsSearchField.textProperty().addListener((obs, oldVal, newVal) -> applySettingsSearchFilter());
        }

        if (filesSearchField != null) {
            filesSearchField.textProperty().addListener((obs, oldVal, newVal) -> refreshWorkspaceFileFilter());
        }
        if (filesSearchContentBox != null) {
            filesSearchContentBox.selectedProperty().addListener((obs, oldVal, newVal) -> refreshWorkspaceFileFilter());
        }
    }

    private void setupModuleNavigation() {
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            syncModuleButtons();
            if (newTab == operationsTab) {
                refreshOperationsDiagnostics();
            }
        });
    }

    private void setupShellNavigation() {
        if (dashboardPane != null && dashboardProjectGalleryBox != null) {
            dashboardPane.widthProperty().addListener((obs, oldVal, newVal) -> {
                double wrapLength = Math.max(640.0, newVal.doubleValue() - 56.0);
                dashboardProjectGalleryBox.setPrefWrapLength(wrapLength);
            });
        }
        showShellSection(SECTION_DASHBOARD);
    }

    private void applyGuidedWizardUi() {
        setNodeVisibleManaged(legacyModuleFlowCard, false);
        setNodeVisibleManaged(moduleSelectionRow, false);
        setNodeVisibleManaged(moduleQuickActionsPane, false);
        setNodeVisibleManaged(moduleFlowTitleLabel, false);
        setNodeVisibleManaged(moduleFlowLabel, false);
        setNodeVisibleManaged(moduleFlowActionButton, false);
        setNodeVisibleManaged(startButton, false);
        setNodeVisibleManaged(preflightButton, false);
        setNodeVisibleManaged(autoPreflightBox, false);
        setNodeVisibleManaged(wizardSourceButton, false);
        setNodeVisibleManaged(wizardOutputButton, false);
        setNodeVisibleManaged(wizardPreflightButton, false);
        setNodeVisibleManaged(wizardRunButton, false);
    }

    private void applyUiTestShellOverrides() {
        if (!AppRuntimePaths.isUiTestMode()) {
            return;
        }
        setNodeVisibleManaged(actionRowPane, true);
        setNodeVisibleManaged(workspaceNavRowPane, true);
        setNodeVisibleManaged(startButton, true);
        setNodeVisibleManaged(preflightButton, true);
        setNodeVisibleManaged(autoPreflightBox, true);
    }

    private void applyRunReadOnlyPreviewMode() {
        if (!RUN_SOURCE_OUTPUT_READONLY_PREVIEW) {
            return;
        }

        if (localPathField != null) {
            localPathField.setEditable(false);
            if (!localPathField.getStyleClass().contains("readonly-preview-field")) {
                localPathField.getStyleClass().add("readonly-preview-field");
            }
        }
        if (youtubeUrlField != null) {
            youtubeUrlField.setEditable(false);
            if (!youtubeUrlField.getStyleClass().contains("readonly-preview-field")) {
                youtubeUrlField.getStyleClass().add("readonly-preview-field");
            }
        }
        if (outputDirField != null) {
            outputDirField.setEditable(false);
            if (!outputDirField.getStyleClass().contains("readonly-preview-field")) {
                outputDirField.getStyleClass().add("readonly-preview-field");
            }
        }
        if (outputPrefixField != null) {
            outputPrefixField.setEditable(false);
            if (!outputPrefixField.getStyleClass().contains("readonly-preview-field")) {
                outputPrefixField.getStyleClass().add("readonly-preview-field");
            }
        }
    }

    private void setupThemeSelector() {
        themeInitializing = true;
        themeBox.setItems(FXCollections.observableArrayList(THEME_LIGHT, THEME_DARK, THEME_DRACULA));
        String storedTheme = normalizeThemeName(preferences.get(PREF_THEME, THEME_LIGHT));
        themeBox.getSelectionModel().select(storedTheme);
        applyTheme(storedTheme, false);
        themeInitializing = false;
    }

    private void setupUiLanguageSelector() {
        if (uiLanguageBox == null) {
            return;
        }
        uiLanguageBox.setItems(FXCollections.observableArrayList(UI_LANG_EN, UI_LANG_CS));
        String stored = normalizeUiLanguage(preferences.get(PREF_UI_LANGUAGE, UI_LANG_EN));
        uiLanguageBox.getSelectionModel().select(stored);
        currentUiLanguage = stored;
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
        refreshModulePresetList(null);
    }

    private void setupModuleSwitcher() {
        if (moduleSwitcherBox == null) {
            return;
        }
        moduleSwitcherSync = true;
        moduleSwitcherBox.setItems(FXCollections.observableArrayList(moduleLabelsToId.keySet()));
        String currentLabel = moduleLabel(activeModule);
        if (moduleSwitcherBox.getItems().contains(currentLabel)) {
            moduleSwitcherBox.getSelectionModel().select(currentLabel);
        } else if (!moduleSwitcherBox.getItems().isEmpty()) {
            moduleSwitcherBox.getSelectionModel().selectFirst();
        }
        moduleSwitcherSync = false;
        updateModulePreviousButtonState();
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
        refreshModulePresetList(null);
    }

    @FXML
    private void onSaveModulePreset() {
        TextInputDialog dialog = new TextInputDialog(trimToEmpty(safeValue(settingsPresetBox)));
        dialog.setTitle("Save Module Preset");
        dialog.setHeaderText("Save preset for " + moduleLabel(activeModule));
        dialog.setContentText("Preset name:");
        Stage owner = getStage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        applyThemeToDialog(dialog);

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }

        String presetName = sanitizePresetName(result.get());
        if (presetName.isBlank()) {
            addUserLog("WARN", "Preset name cannot be empty.");
            return;
        }

        saveActiveModuleState();
        ObjectNode presets = loadModulePresetsNode(activeModule);
        presets.set(presetName, captureCurrentModuleStateNode());
        saveModulePresetsNode(activeModule, presets);
        preferences.put(effectiveModulePrefPrefix(activeModule) + PREF_MODULE_SELECTED_PRESET, presetName);
        preferences.flush();
        refreshModulePresetList(presetName);
        addUserLog("SUCCESS", "Preset saved: " + presetName + " (" + moduleLabel(activeModule) + ")");
    }

    @FXML
    private void onLoadModulePreset() {
        String presetName = sanitizePresetName(safeValue(settingsPresetBox));
        if (presetName.isBlank()) {
            addUserLog("WARN", "Select a preset to load.");
            return;
        }

        ObjectNode presets = loadModulePresetsNode(activeModule);
        JsonNode state = presets.get(presetName);
        if (state == null || !state.isObject()) {
            addUserLog("ERROR", "Preset not found: " + presetName);
            refreshModulePresetList(null);
            return;
        }

        applyModuleStateFromNode(state);
        enforceModuleConstraints(activeModule, true);
        saveActiveModuleState();
        preferences.put(effectiveModulePrefPrefix(activeModule) + PREF_MODULE_SELECTED_PRESET, presetName);
        preferences.flush();
        refreshModulePresetList(presetName);
        addUserLog("INFO", "Preset loaded: " + presetName + " (" + moduleLabel(activeModule) + ")");
    }

    @FXML
    private void onDeleteModulePreset() {
        String presetName = sanitizePresetName(safeValue(settingsPresetBox));
        if (presetName.isBlank()) {
            addUserLog("WARN", "Select a preset to delete.");
            return;
        }

        ObjectNode presets = loadModulePresetsNode(activeModule);
        if (presets.remove(presetName) == null) {
            addUserLog("WARN", "Preset not found: " + presetName);
            refreshModulePresetList(null);
            return;
        }
        saveModulePresetsNode(activeModule, presets);
        preferences.put(effectiveModulePrefPrefix(activeModule) + PREF_MODULE_SELECTED_PRESET, "");
        preferences.flush();
        refreshModulePresetList(null);
        addUserLog("INFO", "Preset deleted: " + presetName + " (" + moduleLabel(activeModule) + ")");
    }

    private void restoreUiPreferences() {
        restoringPreferences = true;
        try {
            String storedLanguage = normalizeUiLanguage(preferences.get(PREF_UI_LANGUAGE, UI_LANG_EN));
            currentUiLanguage = storedLanguage;
            if (uiLanguageBox != null) {
                uiLanguageBox.getSelectionModel().select(storedLanguage);
            }

            String storedTheme = normalizeThemeName(preferences.get(PREF_THEME, THEME_LIGHT));
            themeInitializing = true;
            themeBox.getSelectionModel().select(storedTheme);
            themeInitializing = false;
            applyTheme(storedTheme, false);

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

            String savedDiarizationBackend = normalizeDiarizationBackend(
                    preferences.get(PREF_DIARIZATION_BACKEND, safeValue(diarizationBackendBox))
            );
            if (!savedDiarizationBackend.isBlank() && diarizationBackendBox.getItems().contains(savedDiarizationBackend)) {
                diarizationBackendBox.getSelectionModel().select(savedDiarizationBackend);
            }

            String savedDiarizationAccuracy = normalizeDiarizationAccuracyProfile(
                    preferences.get(PREF_DIARIZATION_ACCURACY_PROFILE, safeValue(diarizationAccuracyBox))
            );
            if (!savedDiarizationAccuracy.isBlank() && diarizationAccuracyBox.getItems().contains(savedDiarizationAccuracy)) {
                diarizationAccuracyBox.getSelectionModel().select(savedDiarizationAccuracy);
            }
        } finally {
            restoringPreferences = false;
        }
        refreshWorkspaceSettingsUi();
    }

    private void registerPreferenceListeners() {
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
                preferences.put(PREF_DIARIZATION_BACKEND, normalizeDiarizationBackend(newVal));
                preferences.flush();
            }
        });
        diarizationAccuracyBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!restoringPreferences && newVal != null) {
                preferences.put(PREF_DIARIZATION_ACCURACY_PROFILE, normalizeDiarizationAccuracyProfile(newVal));
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
        if (!isProjectWorkspaceDetached()) {
            showShellSection(SECTION_MODULES);
        }
        mainTabs.getSelectionModel().select(tab);
    }

    private void showShellSection(String sectionId) {
        String normalized = switch (trimToEmpty(sectionId).toLowerCase(Locale.ROOT)) {
            case SECTION_DASHBOARD -> SECTION_DASHBOARD;
            case SECTION_PROJECTS -> SECTION_PROJECTS;
            case SECTION_FILES -> SECTION_FILES;
            default -> SECTION_MODULES;
        };

        activeSection = normalized;
        setNodeVisibleManaged(dashboardPane, SECTION_DASHBOARD.equals(normalized));
        setNodeVisibleManaged(projectsPane, SECTION_PROJECTS.equals(normalized));
        boolean filesDetachedToProjectWindow = filesPane != null
                && filesPane.getParent() != null
                && filesPane.getParent() != workspaceStack;
        if (filesDetachedToProjectWindow) {
            setNodeVisibleManaged(filesPane, true);
        } else {
            setNodeVisibleManaged(filesPane, SECTION_FILES.equals(normalized));
        }
        boolean modulesDetachedToProjectWindow = modulesPane != null
                && modulesPane.getParent() != null
                && modulesPane.getParent() != workspaceStack;
        if (modulesDetachedToProjectWindow) {
            setNodeVisibleManaged(modulesPane, true);
        } else {
            setNodeVisibleManaged(modulesPane, SECTION_MODULES.equals(normalized));
        }
        syncMainNavigation();
    }

    private void syncMainNavigation() {
        Tab selectedTab = mainTabs == null ? null : mainTabs.getSelectionModel().getSelectedItem();

        boolean dashboardActive = SECTION_DASHBOARD.equals(activeSection);
        boolean projectsActive = SECTION_PROJECTS.equals(activeSection);
        boolean filesActive = SECTION_FILES.equals(activeSection);
        boolean modulesActive = SECTION_MODULES.equals(activeSection)
                && selectedTab != operationsTab
                && selectedTab != jobsTab
                && selectedTab != logsTab
                && selectedTab != settingsTab;
        boolean operationsActive = SECTION_MODULES.equals(activeSection)
                && (selectedTab == operationsTab || selectedTab == jobsTab || selectedTab == logsTab);
        boolean jobsActive = SECTION_MODULES.equals(activeSection) && selectedTab == jobsTab;
        boolean logsActive = SECTION_MODULES.equals(activeSection) && selectedTab == logsTab;
        boolean settingsActive = SECTION_MODULES.equals(activeSection) && selectedTab == settingsTab;

        setMainNavButtonActive(navDashboardButton, dashboardActive);
        setMainNavButtonActive(navProjectsButton, projectsActive);
        setMainNavButtonActive(navFilesButton, filesActive);
        setMainNavButtonActive(navModulesButton, modulesActive);
        setMainNavButtonActive(navOperationsButton, operationsActive);
        setMainNavButtonActive(navJobsButton, jobsActive);
        setMainNavButtonActive(navLogsButton, logsActive);
        setMainNavButtonActive(navSettingsButton, settingsActive);
    }

    private void setMainNavButtonActive(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.getStyleClass().remove("main-nav-btn-active");
        if (active) {
            button.getStyleClass().add("main-nav-btn-active");
        }
    }

    private void syncModuleButtons() {
        setModuleButtonActive(runModuleButton, MODULE_OFFLINE.equals(activeModule));
        setModuleButtonActive(advancedModuleButton, MODULE_YOUTUBE.equals(activeModule));
        setModuleButtonActive(diarizationModuleButton, MODULE_SPEAKER.equals(activeModule));
        setModuleButtonActive(logsModuleButton, MODULE_CONFERENCE.equals(activeModule));
        setModuleButtonActive(jobsModuleButton, MODULE_YOUTUBE_SUBS.equals(activeModule));
        setModuleButtonActive(youtubeDubModuleButton, MODULE_YOUTUBE_DUB.equals(activeModule));
        setModuleButtonActive(settingsModuleButton, mainTabs.getSelectionModel().getSelectedItem() == settingsTab);
        syncMainNavigation();
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

    private void updateModuleContextLabels() {
        String label = moduleLabel(activeModule);
        if (runModuleContextLabel != null) {
            runModuleContextLabel.setText(
                    "Active module: " + label
                            + ". Source/Output values are read-only here and managed by wizard."
            );
        }
        if (settingsScopeLabel != null) {
            settingsScopeLabel.setText("Module settings - " + label);
        }
        applySettingsScopeContext();
    }

    private void applySettingsScopeContext() {
        String projectId = trimToEmpty(activeProjectId);
        ProjectWorkspace activeWorkspace = projectsById.get(projectId);
        boolean hasProject = activeWorkspace != null && activeProjectRoot != null;
        boolean running = controllerRunning;
        if (settingsScopeValueLabel != null) {
            if (hasProject) {
                settingsScopeValueLabel.setText("Active project: " + activeWorkspace.name() + " (" + projectId + ")");
            } else {
                settingsScopeValueLabel.setText("Global defaults (no active project)");
            }
        }
        if (settingsResetProjectModuleButton != null) {
            settingsResetProjectModuleButton.setDisable(running || !hasProject || workspaceMigrationRunning);
        }
        if (settingsSaveGlobalDefaultsButton != null) {
            settingsSaveGlobalDefaultsButton.setDisable(running || workspaceMigrationRunning);
        }
    }

    private void configureModuleFlow(String title, String details, String actionKey, String actionText) {
        if (moduleFlowTitleLabel != null) {
            moduleFlowTitleLabel.setText(title);
        }
        if (moduleFlowLabel != null) {
            moduleFlowLabel.setText(details);
        }
        moduleFlowActionKey = actionKey == null ? "" : actionKey;
        boolean running = controllerRunning;
        boolean hasActiveProject = activeProjectRoot != null && !trimToEmpty(activeProjectId).isBlank();
        if (moduleFlowActionButton != null) {
            moduleFlowActionButton.setText(actionText == null ? "Action" : actionText);
            moduleFlowActionButton.setDisable(running || !hasActiveProject);
        }
    }

    private void activateModule(String moduleId, boolean logChange) {
        activateModule(moduleId, logChange, false);
    }

    private void activateModule(String moduleId, boolean logChange, boolean keepCurrentTab) {
        String normalized = normalizeModuleId(moduleId);
        Tab selectedTab = mainTabs.getSelectionModel().getSelectedItem();
        if (!Objects.equals(activeModule, normalized)) {
            if (!trimToEmpty(activeModule).isBlank()) {
                previousModule = activeModule;
            }
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
            if (SECTION_MODULES.equals(activeSection)) {
                selectModule(selectedTab);
            } else {
                mainTabs.getSelectionModel().select(selectedTab);
            }
        }
        syncModuleButtons();
        syncSettingsModuleSelector();
        syncModuleSwitcher();
        refreshModulePresetList(trimToEmpty(preferences.get(effectiveModulePrefPrefix(activeModule) + PREF_MODULE_SELECTED_PRESET, "")));
        updateModuleSpecificUiVisibility();
        updateModuleContextLabels();
        applyActiveProjectToRunFields();
        invalidatePreflightState();
        updateWizardState();
        refreshJobsSilently();
        refreshJobFilters();

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

    private void syncModuleSwitcher() {
        if (moduleSwitcherBox == null) {
            return;
        }
        String activeLabel = moduleLabel(activeModule);
        if (Objects.equals(moduleSwitcherBox.getValue(), activeLabel)) {
            updateModulePreviousButtonState();
            return;
        }
        moduleSwitcherSync = true;
        moduleSwitcherBox.getSelectionModel().select(activeLabel);
        moduleSwitcherSync = false;
        updateModulePreviousButtonState();
    }

    private void updateModulePreviousButtonState() {
        if (modulePreviousButton == null) {
            return;
        }
        boolean running = controllerRunning;
        boolean hasPrevious = !trimToEmpty(previousModule).isBlank()
                && !Objects.equals(normalizeModuleId(previousModule), activeModule);
        modulePreviousButton.setDisable(running || !hasPrevious);
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

    /**
     * Applies module schema to tabs/cards and then delegates field-level visibility.
     */
    private void updateModuleSpecificUiVisibility() {
        ModuleUiSchema schema = resolveModuleUiSchema(activeModule);

        setTabVisible(runTab, schema.allowsTab("run"));
        setTabVisible(advancedTab, schema.allowsTab("advanced"));
        setTabVisible(diarizationTab, schema.allowsTab("diarization"));
        setTabVisible(logsTab, schema.allowsTab("logs"));
        setTabVisible(jobsTab, schema.allowsTab("jobs"));
        // Project shell integrates jobs/logs directly. Operations stays hidden as legacy surface.
        setTabVisible(operationsTab, false);
        setTabVisible(settingsTab, schema.allowsTab("settings"));

        setNodeVisibleManaged(runModuleContextCard, schema.allowsTab("run"));
        setNodeVisibleManaged(runWizardCard, schema.allowsTab("run"));
        setNodeVisibleManaged(runPreflightCard, schema.allowsTab("run"));
        setNodeVisibleManaged(runSourceCard, schema.allowsSection("run_source_card"));
        setNodeVisibleManaged(runOutputCard, schema.allowsSection("run_output_card"));
        setNodeVisibleManaged(advancedSubtitlesCard, schema.allowsSection("advanced_subtitles_card"));
        setNodeVisibleManaged(advancedConferenceCard, schema.allowsSection("advanced_conference_card"));
        settingsAppearanceAllowed = schema.allowsSection("settings_appearance_card");
        settingsRuntimeAllowed = schema.allowsSection("settings_runtime_card");
        settingsCoreAllowed = schema.allowsSection("settings_core_card");
        settingsModuleFlowAllowed = schema.allowsSection("settings_module_flow_card");
        settingsModuleScopeAllowed = schema.allowsSection("settings_module_scope_row");
        settingsPresetRowAllowed = schema.allowsSection("settings_preset_row");
        applySettingsSearchFilter();

        setNodeVisibleManaged(simpleHintCard, false);

        applyFieldVisibility(schema);
    }

    /**
     * Applies field-level visibility and read-only preview rules for run/settings surfaces.
     */
    private void applyFieldVisibility(ModuleUiSchema schema) {
        boolean readOnlyPreview = RUN_SOURCE_OUTPUT_READONLY_PREVIEW;

        boolean showSourceMode = schema.allowsField("run.source_mode");
        setNodeVisibleManaged(sourceModeLabel, showSourceMode && !readOnlyPreview);
        setNodeVisibleManaged(sourceModeBox, showSourceMode && !readOnlyPreview);

        boolean showLocalPath = schema.allowsField("run.local_path");
        setNodeVisibleManaged(localPathLabel, showLocalPath);
        setNodeVisibleManaged(localPathField, showLocalPath);
        setNodeVisibleManaged(localPathBrowseButton, showLocalPath && !readOnlyPreview);

        boolean showYoutube = schema.allowsField("run.youtube_url");
        setNodeVisibleManaged(youtubeUrlLabel, showYoutube);
        setNodeVisibleManaged(youtubeUrlField, showYoutube);

        boolean showPlaylist = schema.allowsField("run.playlist");
        setNodeVisibleManaged(playlistBox, showPlaylist);
        if (playlistBox != null) {
            playlistBox.setDisable(readOnlyPreview);
        }

        boolean showQuality = schema.allowsField("run.quality");
        setNodeVisibleManaged(qualityLabel, showQuality);
        setNodeVisibleManaged(qualityBox, showQuality);
        if (qualityBox != null) {
            qualityBox.setDisable(readOnlyPreview);
        }

        boolean showOutputDir = schema.allowsField("run.output_dir");
        setNodeVisibleManaged(outputDirLabel, showOutputDir);
        setNodeVisibleManaged(outputDirField, showOutputDir);
        setNodeVisibleManaged(outputDirBrowseButton, showOutputDir && !readOnlyPreview);

        boolean showOutputMode = schema.allowsField("run.output_mode");
        setNodeVisibleManaged(outputModeLabel, showOutputMode);
        setNodeVisibleManaged(outputModeBox, showOutputMode);
        if (outputModeBox != null) {
            outputModeBox.setDisable(readOnlyPreview);
        }

        boolean showOutputPrefix = schema.allowsField("run.output_prefix");
        setNodeVisibleManaged(outputPrefixLabel, showOutputPrefix);
        setNodeVisibleManaged(outputPrefixField, showOutputPrefix);

        boolean showKeepOriginals = schema.allowsField("run.keep_originals");
        setNodeVisibleManaged(keepOriginalsBox, showKeepOriginals);
        if (keepOriginalsBox != null) {
            keepOriginalsBox.setDisable(readOnlyPreview);
        }

        setNodeVisibleManaged(settingsWhisperModelLabel, schema.allowsField("settings.model"));
        setNodeVisibleManaged(modelField, schema.allowsField("settings.model"));
        setNodeVisibleManaged(settingsModelOptionsBox, schema.allowsField("settings.model_options"));

        setNodeVisibleManaged(settingsSourceLangLabel, schema.allowsField("settings.source_lang"));
        setNodeVisibleManaged(sourceLangBox, schema.allowsField("settings.source_lang"));

        setNodeVisibleManaged(settingsSummaryLangLabel, schema.allowsField("settings.summary_lang"));
        setNodeVisibleManaged(summaryLangBox, schema.allowsField("settings.summary_lang"));

        boolean showSummaryAi = schema.allowsField("settings.summary_ai");
        setNodeVisibleManaged(settingsSummaryAiLabel, showSummaryAi);
        setNodeVisibleManaged(summaryAiBox, showSummaryAi);

        boolean showSummaryModelTier = schema.allowsField("settings.summary_model_tier");
        setNodeVisibleManaged(settingsSummaryModelTierLabel, showSummaryModelTier);
        setNodeVisibleManaged(summaryModelTierBox, showSummaryModelTier);

        setNodeVisibleManaged(settingsTargetLangLabel, schema.allowsField("settings.target_lang"));
        setNodeVisibleManaged(targetLangBox, schema.allowsField("settings.target_lang"));

        setNodeVisibleManaged(settingsBatchSizeLabel, schema.allowsField("settings.batch_size"));
        setNodeVisibleManaged(batchSizeSpinner, schema.allowsField("settings.batch_size"));

        setNodeVisibleManaged(settingsTextOptionsLabel, schema.allowsField("settings.text_options"));
        setNodeVisibleManaged(settingsTextOptionsBox, schema.allowsField("settings.text_options"));

        setNodeVisibleManaged(settingsSplitMinutesLabel, schema.allowsField("settings.split_minutes"));
        setNodeVisibleManaged(splitMinutesSpinner, schema.allowsField("settings.split_minutes"));

        setNodeVisibleManaged(settingsSpeakerModuleHintLabel, schema.allowsField("settings.speaker_hint"));
    }

    private void applySettingsSearchFilter() {
        String query = settingsSearchField == null
                ? ""
                : trimToEmpty(settingsSearchField.getText()).toLowerCase(Locale.ROOT);

        boolean appearanceMatch = query.isBlank()
                || containsAny(query, "theme", "appearance", "global", "ui", "language");
        boolean runtimeMatch = query.isBlank()
                || containsAny(query, "runtime", "repair", "path", "output", "data", "workspace", "root", "migrate");
        boolean coreMatch = query.isBlank()
                || containsAny(
                query,
                "module",
                "model",
                "language",
                "batch",
                "preset",
                "speaker",
                "text",
                "split",
                "gpu",
                "transcript",
                "subtitle",
                "translation"
        );
        boolean flowMatch = query.isBlank()
                || containsAny(query, "flow", "workflow", "module", "guide");

        setNodeVisibleManaged(settingsAppearanceCard, settingsAppearanceAllowed && appearanceMatch);
        setNodeVisibleManaged(settingsRuntimeCard, settingsRuntimeAllowed && runtimeMatch);
        setNodeVisibleManaged(settingsCoreCard, settingsCoreAllowed && coreMatch);
        setNodeVisibleManaged(settingsModuleFlowCard, settingsModuleFlowAllowed && flowMatch);
        setNodeVisibleManaged(settingsModuleScopeRow, settingsModuleScopeAllowed && coreMatch);
        setNodeVisibleManaged(settingsPresetRow, settingsPresetRowAllowed && coreMatch);
    }

    private void setTabVisible(Tab tab, boolean visible) {
        if (tab == null || mainTabs == null) {
            return;
        }
        boolean contains = mainTabs.getTabs().contains(tab);
        if (visible) {
            if (!contains) {
                int insertIndex = Math.min(preferredTabIndex(tab), mainTabs.getTabs().size());
                mainTabs.getTabs().add(insertIndex, tab);
            }
            return;
        }
        if (!contains) {
            return;
        }
        if (Objects.equals(mainTabs.getSelectionModel().getSelectedItem(), tab)) {
            mainTabs.getSelectionModel().select(runTab);
        }
        mainTabs.getTabs().remove(tab);
    }

    private int preferredTabIndex(Tab tab) {
        if (tab == runTab) {
            return 0;
        }
        if (tab == advancedTab) {
            return 1;
        }
        if (tab == diarizationTab) {
            return 2;
        }
        if (tab == logsTab) {
            return 3;
        }
        if (tab == jobsTab) {
            return 4;
        }
        if (tab == operationsTab) {
            return 5;
        }
        if (tab == settingsTab) {
            return 6;
        }
        return mainTabs.getTabs().size();
    }

    private static void setNodeVisibleManaged(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void saveActiveModuleState() {
        String module = normalizeModuleId(activeModule);
        String prefix = effectiveModulePrefPrefix(module);
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
        preferences.putBoolean(prefix + "summary_ai_enabled", summaryAiBox != null && summaryAiBox.isSelected());
        preferences.put(prefix + "summary_ai_tier", summaryModelTierBox == null ? "medium" : safeValue(summaryModelTierBox));
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
        preferences.put(prefix + "diarization_backend", normalizeDiarizationBackend(safeValue(diarizationBackendBox)));
        preferences.put(prefix + "diarization_accuracy", normalizeDiarizationAccuracyProfile(safeValue(diarizationAccuracyBox)));
        preferences.putInt(prefix + "diarization_min", valueOf(diarizationMinSpinner));
        preferences.putInt(prefix + "diarization_max", valueOf(diarizationMaxSpinner));
        preferences.putBoolean(prefix + "diarization_review", diarizationReviewBox.isSelected());
        preferences.putBoolean(prefix + "diarization_unmapped", diarizationIncludeUnmappedBox.isSelected());
        preferences.putBoolean(prefix + "diarization_prefix", diarizationPrefixSrtBox.isSelected());
        preferences.putBoolean(prefix + "diarization_prefill", diarizationProfilePrefillBox.isSelected());

        preferences.put(prefix + "speaker", trimToEmpty(speakerField.getText()));
        preferences.put(prefix + "topic", trimToEmpty(topicField.getText()));
        preferences.put(prefix + "conference_title", trimToEmpty(conferenceTitleField.getText()));
        preferences.put(prefix + "conference_date", trimToEmpty(conferenceDateField.getText()));
        preferences.put(prefix + "conference_rows", serializeConferenceRows());
        preferences.flush();
    }

    private boolean loadModuleState(String moduleId) {
        String module = normalizeModuleId(moduleId);
        String prefix = effectiveModulePrefPrefix(module);
        String activeProject = trimToEmpty(activeProjectId);
        if (!activeProject.isBlank() && !preferences.getBoolean(prefix + "initialized", false)) {
            String globalPrefix = modulePrefPrefix(module);
            if (preferences.hasAnyWithPrefix(globalPrefix)) {
                preferences.copyPrefix(globalPrefix, prefix, true);
                preferences.flush();
            }
        }
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
        if (summaryAiBox != null) {
            summaryAiBox.setSelected(preferences.getBoolean(prefix + "summary_ai_enabled", summaryAiBox.isSelected()));
        }
        if (summaryModelTierBox != null) {
            selectComboValue(
                    summaryModelTierBox,
                    preferences.get(prefix + "summary_ai_tier", safeValue(summaryModelTierBox))
            );
            summaryModelTierBox.setDisable(summaryAiBox == null || !summaryAiBox.isSelected());
        }
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
        selectComboValue(
                diarizationBackendBox,
                normalizeDiarizationBackend(preferences.get(prefix + "diarization_backend", safeValue(diarizationBackendBox)))
        );
        String moduleDiarizationAccuracyDefault = MODULE_SPEAKER.equals(module)
                ? DIARIZATION_ACCURACY_MAXIMUM
                : safeValue(diarizationAccuracyBox);
        selectComboValue(
                diarizationAccuracyBox,
                normalizeDiarizationAccuracyProfile(
                        preferences.get(prefix + "diarization_accuracy", moduleDiarizationAccuracyDefault)
                )
        );
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

        speakerField.setText(preferences.get(prefix + "speaker", trimToEmpty(speakerField.getText())));
        topicField.setText(preferences.get(prefix + "topic", trimToEmpty(topicField.getText())));
        conferenceTitleField.setText(preferences.get(prefix + "conference_title", trimToEmpty(conferenceTitleField.getText())));
        conferenceDateField.setText(preferences.get(prefix + "conference_date", trimToEmpty(conferenceDateField.getText())));
        deserializeConferenceRows(preferences.get(prefix + "conference_rows", ""));

        return true;
    }

    private void refreshModulePresetList(String preferredName) {
        if (settingsPresetBox == null) {
            return;
        }

        ObjectNode presets = loadModulePresetsNode(activeModule);
        List<String> names = new ArrayList<>();
        presets.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareToIgnoreCase);

        settingsPresetBox.setItems(FXCollections.observableArrayList(names));
        String selected = sanitizePresetName(preferredName);
        if (selected.isBlank()) {
            selected = sanitizePresetName(
                    preferences.get(effectiveModulePrefPrefix(activeModule) + PREF_MODULE_SELECTED_PRESET, "")
            );
        }
        if (!selected.isBlank() && names.contains(selected)) {
            settingsPresetBox.getSelectionModel().select(selected);
        } else if (!names.isEmpty()) {
            settingsPresetBox.getSelectionModel().selectFirst();
        } else {
            settingsPresetBox.getSelectionModel().clearSelection();
        }

        boolean hasPresets = !names.isEmpty();
        if (settingsLoadPresetButton != null) {
            settingsLoadPresetButton.setDisable(!hasPresets);
        }
        if (settingsDeletePresetButton != null) {
            settingsDeletePresetButton.setDisable(!hasPresets);
        }
    }

    private ObjectNode loadModulePresetsNode(String moduleId) {
        String prefix = effectiveModulePrefPrefix(moduleId);
        String raw = trimToEmpty(preferences.get(prefix + PREF_MODULE_PRESETS_JSON, "{}"));
        if (raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (parsed instanceof ObjectNode objectNode) {
                return objectNode.deepCopy();
            }
        } catch (Exception ignored) {
            // Keep default empty object.
        }
        return mapper.createObjectNode();
    }

    private void saveModulePresetsNode(String moduleId, ObjectNode presetsNode) {
        String prefix = effectiveModulePrefPrefix(moduleId);
        try {
            preferences.put(prefix + PREF_MODULE_PRESETS_JSON, mapper.writeValueAsString(presetsNode));
        } catch (Exception ignored) {
            preferences.put(prefix + PREF_MODULE_PRESETS_JSON, "{}");
        }
        preferences.flush();
    }

    private ObjectNode captureCurrentModuleStateNode() {
        ObjectNode node = mapper.createObjectNode();
        node.put("source_mode", safeValue(sourceModeBox));
        node.put("output_mode", safeValue(outputModeBox));
        node.put("local_path", trimToEmpty(localPathField.getText()));
        node.put("youtube_url", trimToEmpty(youtubeUrlField.getText()));
        node.put("output_dir", trimToEmpty(outputDirField.getText()));
        node.put("output_prefix", trimToEmpty(outputPrefixField.getText()));
        node.put("quality", safeValue(qualityBox));
        node.put("playlist", playlistBox.isSelected());
        node.put("keep_originals", keepOriginalsBox.isSelected());

        node.put("model", safeValue(modelField));
        node.put("auto_model", autoModelBox.isSelected());
        node.put("prefer_gpu", useGpuBox.isSelected());
        node.put("source_lang", safeValue(sourceLangBox));
        node.put("summary_lang", safeValue(summaryLangBox));
        node.put("summary_ai_enabled", summaryAiBox != null && summaryAiBox.isSelected());
        node.put("summary_ai_tier", summaryModelTierBox == null ? "medium" : safeValue(summaryModelTierBox));
        node.put("target_lang", safeValue(targetLangBox));
        node.put("batch_size", valueOf(batchSizeSpinner));
        node.put("clean_text", cleanTextBox.isSelected());
        node.put("export_md", exportMdBox.isSelected());
        node.put("summary_pack", summaryPackBox.isSelected());
        node.put("notify_done", notifyDoneBox.isSelected());
        node.put("split_minutes", valueOf(splitMinutesSpinner));

        node.put("subtitle_mode", safeValue(subtitleModeBox));
        node.put("subtitle_font", trimToEmpty(subtitleFontField.getText()));
        node.put("subtitle_size", valueOf(subtitleSizeSpinner));
        node.put("subtitle_color", toHex(subtitleColorPicker.getValue()));
        node.put("subtitle_outline_color", toHex(subtitleOutlineColorPicker.getValue()));
        node.put("subtitle_outline_width", valueOf(subtitleOutlineWidthSpinner));
        node.put("translate_subtitles", translateSubtitlesBox.isSelected());

        node.put("diarization_enabled", diarizationEnabledBox.isSelected());
        node.put("diarization_backend", normalizeDiarizationBackend(safeValue(diarizationBackendBox)));
        node.put("diarization_accuracy", normalizeDiarizationAccuracyProfile(safeValue(diarizationAccuracyBox)));
        node.put("diarization_min", valueOf(diarizationMinSpinner));
        node.put("diarization_max", valueOf(diarizationMaxSpinner));
        node.put("diarization_review", diarizationReviewBox.isSelected());
        node.put("diarization_unmapped", diarizationIncludeUnmappedBox.isSelected());
        node.put("diarization_prefix", diarizationPrefixSrtBox.isSelected());
        node.put("diarization_prefill", diarizationProfilePrefillBox.isSelected());

        node.put("speaker", trimToEmpty(speakerField.getText()));
        node.put("topic", trimToEmpty(topicField.getText()));
        node.put("conference_title", trimToEmpty(conferenceTitleField.getText()));
        node.put("conference_date", trimToEmpty(conferenceDateField.getText()));
        node.put("conference_rows", serializeConferenceRows());

        ObjectNode profileNode = node.putObject("speaker_profiles");
        collectSpeakerProfiles().forEach(profileNode::put);

        return node;
    }

    private void applyModuleStateFromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }

        selectComboValue(sourceModeBox, nodeText(node, "source_mode", safeValue(sourceModeBox)));
        selectComboValue(outputModeBox, nodeText(node, "output_mode", safeValue(outputModeBox)));
        localPathField.setText(nodeText(node, "local_path", localPathField.getText()));
        youtubeUrlField.setText(nodeText(node, "youtube_url", youtubeUrlField.getText()));
        outputDirField.setText(nodeText(node, "output_dir", outputDirField.getText()));
        outputPrefixField.setText(nodeText(node, "output_prefix", outputPrefixField.getText()));
        selectComboValue(qualityBox, nodeText(node, "quality", safeValue(qualityBox)));
        playlistBox.setSelected(nodeBool(node, "playlist", playlistBox.isSelected()));
        keepOriginalsBox.setSelected(nodeBool(node, "keep_originals", keepOriginalsBox.isSelected()));

        selectModel(nodeText(node, "model", safeValue(modelField)));
        autoModelBox.setSelected(nodeBool(node, "auto_model", autoModelBox.isSelected()));
        useGpuBox.setSelected(nodeBool(node, "prefer_gpu", useGpuBox.isSelected()));
        selectComboValue(sourceLangBox, nodeText(node, "source_lang", safeValue(sourceLangBox)));
        selectComboValue(summaryLangBox, nodeText(node, "summary_lang", safeValue(summaryLangBox)));
        if (summaryAiBox != null) {
            summaryAiBox.setSelected(nodeBool(node, "summary_ai_enabled", summaryAiBox.isSelected()));
        }
        if (summaryModelTierBox != null) {
            selectComboValue(summaryModelTierBox, nodeText(node, "summary_ai_tier", safeValue(summaryModelTierBox)));
            summaryModelTierBox.setDisable(summaryAiBox == null || !summaryAiBox.isSelected());
        }
        selectComboValue(targetLangBox, nodeText(node, "target_lang", safeValue(targetLangBox)));
        batchSizeSpinner.getValueFactory().setValue(nodeInt(node, "batch_size", valueOf(batchSizeSpinner)));
        cleanTextBox.setSelected(nodeBool(node, "clean_text", cleanTextBox.isSelected()));
        exportMdBox.setSelected(nodeBool(node, "export_md", exportMdBox.isSelected()));
        summaryPackBox.setSelected(nodeBool(node, "summary_pack", summaryPackBox.isSelected()));
        notifyDoneBox.setSelected(nodeBool(node, "notify_done", notifyDoneBox.isSelected()));
        splitMinutesSpinner.getValueFactory().setValue(nodeInt(node, "split_minutes", valueOf(splitMinutesSpinner)));

        selectComboValue(subtitleModeBox, nodeText(node, "subtitle_mode", safeValue(subtitleModeBox)));
        subtitleFontField.setText(nodeText(node, "subtitle_font", subtitleFontField.getText()));
        subtitleSizeSpinner.getValueFactory().setValue(nodeInt(node, "subtitle_size", valueOf(subtitleSizeSpinner)));
        subtitleColorPicker.setValue(parseColor(nodeText(node, "subtitle_color", toHex(subtitleColorPicker.getValue())), subtitleColorPicker.getValue()));
        subtitleOutlineColorPicker.setValue(
                parseColor(
                        nodeText(node, "subtitle_outline_color", toHex(subtitleOutlineColorPicker.getValue())),
                        subtitleOutlineColorPicker.getValue()
                )
        );
        subtitleOutlineWidthSpinner.getValueFactory().setValue(
                nodeInt(node, "subtitle_outline_width", valueOf(subtitleOutlineWidthSpinner))
        );
        translateSubtitlesBox.setSelected(nodeBool(node, "translate_subtitles", translateSubtitlesBox.isSelected()));

        diarizationEnabledBox.setSelected(nodeBool(node, "diarization_enabled", diarizationEnabledBox.isSelected()));
        selectComboValue(diarizationBackendBox, normalizeDiarizationBackend(nodeText(node, "diarization_backend", safeValue(diarizationBackendBox))));
        selectComboValue(
                diarizationAccuracyBox,
                normalizeDiarizationAccuracyProfile(nodeText(node, "diarization_accuracy", safeValue(diarizationAccuracyBox)))
        );
        diarizationMinSpinner.getValueFactory().setValue(nodeInt(node, "diarization_min", valueOf(diarizationMinSpinner)));
        diarizationMaxSpinner.getValueFactory().setValue(nodeInt(node, "diarization_max", valueOf(diarizationMaxSpinner)));
        diarizationReviewBox.setSelected(nodeBool(node, "diarization_review", diarizationReviewBox.isSelected()));
        diarizationIncludeUnmappedBox.setSelected(
                nodeBool(node, "diarization_unmapped", diarizationIncludeUnmappedBox.isSelected())
        );
        diarizationPrefixSrtBox.setSelected(nodeBool(node, "diarization_prefix", diarizationPrefixSrtBox.isSelected()));
        diarizationProfilePrefillBox.setSelected(
                nodeBool(node, "diarization_prefill", diarizationProfilePrefillBox.isSelected())
        );

        speakerField.setText(nodeText(node, "speaker", speakerField.getText()));
        topicField.setText(nodeText(node, "topic", topicField.getText()));
        conferenceTitleField.setText(nodeText(node, "conference_title", conferenceTitleField.getText()));
        conferenceDateField.setText(nodeText(node, "conference_date", conferenceDateField.getText()));
        deserializeConferenceRows(nodeText(node, "conference_rows", ""));

        JsonNode speakerProfilesNode = node.path("speaker_profiles");
        if (speakerProfilesNode.isObject()) {
            speakerProfiles.clear();
            speakerProfilesNode.fields().forEachRemaining(entry -> speakerProfiles.add(
                    new SpeakerProfileRow(normalizeSpeakerLabel(entry.getKey()), trimToEmpty(entry.getValue().asText("")))
            ));
        }
        refreshProfilePreview();
        updateSourceModeUi();
        updateTranslationUi();
    }

    private static String sanitizePresetName(String value) {
        String normalized = trimToEmpty(value).replaceAll("\\s+", " ");
        if (normalized.length() > 64) {
            return normalized.substring(0, 64).trim();
        }
        return normalized;
    }

    private static String nodeText(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asText(fallback);
    }

    private static boolean nodeBool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asBoolean(fallback);
    }

    private static int nodeInt(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        return value.asInt(fallback);
    }

    private static String normalizeModuleId(String value) {
        String module = trimToEmpty(value).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODULES.contains(module)) {
            return MODULE_OFFLINE;
        }
        return module;
    }

    private static String normalizeDiarizationBackend(String value) {
        String backend = trimToEmpty(value).toLowerCase(Locale.ROOT);
        return switch (backend) {
            case "advanced_pyannote" -> DIARIZATION_BACKEND_ACCURATE;
            case "stable_local" -> DIARIZATION_BACKEND_FAST;
            case "fast" -> DIARIZATION_BACKEND_FAST;
            case "accurate" -> DIARIZATION_BACKEND_ACCURATE;
            case DIARIZATION_BACKEND_FAST, DIARIZATION_BACKEND_ACCURATE -> backend;
            default -> DIARIZATION_BACKEND_ACCURATE;
        };
    }

    private static String diarizationBackendLabel(String value) {
        return switch (normalizeDiarizationBackend(value)) {
            case DIARIZATION_BACKEND_FAST -> "Fast";
            case DIARIZATION_BACKEND_ACCURATE -> "Accurate";
            default -> "Accurate";
        };
    }

    private static String normalizeDiarizationAccuracyProfile(String value) {
        String profile = trimToEmpty(value).toLowerCase(Locale.ROOT);
        return switch (profile) {
            case "small", "minimal", "low" -> DIARIZATION_ACCURACY_LOW;
            case "default", "medium", "normal", "balanced" -> DIARIZATION_ACCURACY_BALANCED;
            case "high" -> DIARIZATION_ACCURACY_HIGH;
            case "max", "best", "maximum" -> DIARIZATION_ACCURACY_MAXIMUM;
            default -> DIARIZATION_ACCURACY_BALANCED;
        };
    }

    private static String diarizationAccuracyProfileLabel(String value) {
        return switch (normalizeDiarizationAccuracyProfile(value)) {
            case DIARIZATION_ACCURACY_LOW -> "Low";
            case DIARIZATION_ACCURACY_BALANCED -> "Balanced";
            case DIARIZATION_ACCURACY_HIGH -> "High";
            case DIARIZATION_ACCURACY_MAXIMUM -> "Maximum";
            default -> "Balanced";
        };
    }

    private static String moduleLabel(String moduleId) {
        return ModuleRegistry.labelFor(normalizeModuleId(moduleId));
    }

    private static String modulePrefPrefix(String moduleId) {
        return PREF_MODULE_PREFIX + normalizeModuleId(moduleId) + ".";
    }

    private static String projectModulePrefPrefix(String projectId, String moduleId) {
        return PREF_PROJECT_PREFIX + trimToEmpty(projectId) + ".module." + normalizeModuleId(moduleId) + ".";
    }

    private String effectiveModulePrefPrefix(String moduleId) {
        String normalizedModule = normalizeModuleId(moduleId);
        String projectId = trimToEmpty(activeProjectId);
        if (projectId.isBlank()) {
            return modulePrefPrefix(normalizedModule);
        }
        return projectModulePrefPrefix(projectId, normalizedModule);
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
        if (jobProgressWindow != null) {
            jobProgressWindow.applyThemeClass(themeCssClass);
        }
        applyThemeToStage(filesSummaryStage);
        applyThemeToStage(projectWorkspaceStage);
        if (isLauncherController()) {
            for (ProjectWorkspaceHost host : new ArrayList<>(openProjectWorkspaceHosts.values())) {
                if (host != null && host.controller() != null) {
                    host.controller().applyTheme(normalizedTheme, false);
                }
            }
        }

        if (logChange) {
            addUserLog("INFO", "Theme switched to " + normalizedTheme + ".");
        }
    }

    private void applyThemeToDialog(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        DialogPane pane = dialog.getDialogPane();
        if (pane == null) {
            return;
        }
        if (rootPane != null && rootPane.getScene() != null) {
            pane.getStylesheets().setAll(rootPane.getScene().getStylesheets());
        }
        pane.getStyleClass().removeAll("theme-light", "theme-dark", "theme-dracula");
        if (!pane.getStyleClass().contains("app-shell")) {
            pane.getStyleClass().add("app-shell");
        }
        pane.getStyleClass().add(themeClassFromName(currentTheme));
    }

    private void applyThemeToStage(Stage stage) {
        if (stage == null || stage.getScene() == null) {
            return;
        }
        applyThemeToScene(stage.getScene());
    }

    private void applyThemeToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        if (rootPane != null && rootPane.getScene() != null) {
            scene.getStylesheets().setAll(rootPane.getScene().getStylesheets());
        }
        Node sceneRoot = scene.getRoot();
        if (sceneRoot == null) {
            return;
        }
        sceneRoot.getStyleClass().removeAll("theme-light", "theme-dark", "theme-dracula");
        if (!sceneRoot.getStyleClass().contains("app-shell")) {
            sceneRoot.getStyleClass().add("app-shell");
        }
        sceneRoot.getStyleClass().add(themeClassFromName(currentTheme));
    }

    private String validateInputs() {
        String localPath = trimToEmpty(localPathField.getText());
        String youtubeUrl = trimToEmpty(youtubeUrlField.getText());
        String outputDir = trimToEmpty(outputDirField.getText());

        if (trimToEmpty(activeProjectId).isBlank() || activeProjectRoot == null) {
            return "Project is required. Select or create a project first.";
        }

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

    /**
     * Builds backend request payload from current UI + active project context.
     *
     * Module-specific normalization is applied at the end via
     * {@link #applyActiveModulePayloadOverrides(ObjectNode, ObjectNode, ObjectNode, ObjectNode)}.
     */
    private ObjectNode buildPipelineParams() {
        ObjectNode params = mapper.createObjectNode();
        params.put("module", activeModule);

        ObjectNode project = params.putObject("project");
        ProjectWorkspace activeWorkspace = projectsById.get(activeProjectId);
        if (activeWorkspace != null && activeProjectRoot != null) {
            Path projectRoot = activeProjectRoot.toAbsolutePath().normalize();
            project.put("project_id", activeWorkspace.projectId());
            project.put("name", activeWorkspace.name());
            project.put("root_dir", projectRoot.toString());
            project.put("input_dir", projectRoot.resolve("input").toString());
            project.put("output_dir", projectRoot.resolve("output").toString());
            project.put("jobs_dir", projectRoot.resolve("jobs").toString());
            project.put("logs_dir", projectRoot.resolve("logs").toString());
            project.put("timeline_path", projectRoot.resolve("jobs").resolve("timeline.jsonl").toString());
        }

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
        text.put("summary_ai_enabled", summaryAiBox != null && summaryAiBox.isSelected());
        text.put("summary_ai_tier", summaryModelTierBox == null ? "medium" : safeValue(summaryModelTierBox));
        text.put("speaker", trimToEmpty(speakerField.getText()));
        text.put("topic", trimToEmpty(topicField.getText()));

        ObjectNode diarization = params.putObject("diarization");
        diarization.put("enabled", diarizationEnabledBox.isSelected());
        diarization.put("backend", normalizeDiarizationBackend(safeValue(diarizationBackendBox)));
        diarization.put("accuracy_profile", normalizeDiarizationAccuracyProfile(safeValue(diarizationAccuracyBox)));
        diarization.put("min_speakers", valueOf(diarizationMinSpinner));
        diarization.put("max_speakers", valueOf(diarizationMaxSpinner));
        diarization.put("review_after_file", diarizationReviewBox.isSelected());
        diarization.put("include_unmapped_speakers", diarizationIncludeUnmappedBox.isSelected());
        diarization.put("speaker_prefix_in_srt", diarizationPrefixSrtBox.isSelected());
        diarization.put("profile_prefill", diarizationProfilePrefillBox.isSelected());

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
        if (AppRuntimePaths.isUiTestMode()) {
            return;
        }
        ModuleComponent component = getActiveModuleComponent();
        if (component != null) {
            component.applyPayloadOverrides(source, output, translation, diarization);
        }
    }

    private CompletableFuture<Boolean> runPreflightAsync(ObjectNode params, boolean verbose) {
        return backendClient.sendRequest("preflight_check", params)
                .thenApply(result -> {
                    boolean ok = result.path("ok").asBoolean(false);
                    JsonNode checks = result.path("checks");
                    List<PreflightCheckRow> rows = new ArrayList<>();
                    if (checks.isArray()) {
                        for (JsonNode check : checks) {
                            String status = trimToEmpty(check.path("status").asText("-"));
                            String name = trimToEmpty(check.path("name").asText("-"));
                            String message = trimToEmpty(check.path("message").asText(""));
                            rows.add(new PreflightCheckRow(name, status, message, suggestedFixForCheck(name, status)));
                        }
                    }
                    Platform.runLater(() -> {
                        preflightCheckRows.setAll(rows);
                        lastPreflightOk = ok;
                        lastPreflightMillis = System.currentTimeMillis();
                        if (preflightSummaryLabel != null) {
                            long issues = rows.stream().filter(row -> "fail".equalsIgnoreCase(row.getStatus())).count();
                            long warns = rows.stream().filter(row -> "warn".equalsIgnoreCase(row.getStatus())).count();
                            preflightSummaryLabel.setText(
                                    ok
                                            ? "Preflight passed (" + rows.size() + " checks)."
                                            : "Preflight issues: " + issues + " fail, " + warns + " warn."
                            );
                        }
                        updatePreflightFixButtonState();
                        updateWizardState();
                        if (verbose) {
                            addUserLog("INFO", "Preflight checks:");
                            for (PreflightCheckRow row : rows) {
                                String level = switch (trimToEmpty(row.getStatus()).toLowerCase(Locale.ROOT)) {
                                    case "fail" -> "ERROR";
                                    case "warn" -> "WARN";
                                    default -> "INFO";
                                };
                                addUserLog(level, " - " + row.getName() + " [" + row.getStatus() + "]: " + row.getMessage());
                            }
                            if (ok) {
                                addUserLog("SUCCESS", "Preflight passed.");
                            } else {
                                addUserLog("ERROR", "Preflight found blocking issues.");
                            }
                        }
                    });
                    return ok;
                });
    }

    /**
     * Central backend event dispatcher (logs, progress, terminal job states, protocol errors).
     */
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
                    if (jobProgressWindow != null && jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        jobProgressWindow.updateLastLog(line);
                    }
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
                        updateJobProgressWindowFromEvent(jobId, payload, overall, indeterminate, step);
                    }
                }
                case "job.started" -> {
                    updateJobStatus(jobId, "running");
                    if (operationsStatusLabel != null) {
                        operationsStatusLabel.setText("Job running");
                    }
                    boolean affectsCurrent = false;
                    boolean canClaimCurrentJob = controllerRunning || ownsGlobalRunLock();
                    if (jobId != null
                            && !jobId.isBlank()
                            && (currentJobId == null || currentJobId.isBlank())
                            && canClaimCurrentJob) {
                        currentJobId = jobId;
                        affectsCurrent = true;
                    } else if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        affectsCurrent = true;
                    }
                    if (affectsCurrent) {
                        resetEtaDisplay();
                        String moduleId = normalizeModuleId(payload.path("request").path("module").asText(activeModule));
                        String sourceMode = payload.path("request").path("source").path("mode").asText("");
                        showJobProgressWindow(jobId, moduleLabel(moduleId), sourceMode);
                    }
                    addUserLog("INFO", withJobPrefix(jobId, "Job running..."));
                    refreshDashboardData();
                    refreshJobsSilently();
                }
                case "job.completed" -> {
                    updateJobStatus(jobId, "completed");
                    if (operationsStatusLabel != null) {
                        operationsStatusLabel.setText("Idle");
                    }
                    JsonNode result = payload.path("result");
                    String finalOut = result.path("final_output_dir").asText("");
                    Path sidecarPath = extractDiarizationSidecarPath(result);
                    if (sidecarPath != null) {
                        lastDiarizationSidecarPath = sidecarPath;
                        mergeSpeakerProfilesFromSidecar(sidecarPath);
                        updateSpeakerMappingButtonState(false);
                    }
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        releaseGlobalRunLockIfOwned();
                        statusLabel.setText("Completed");
                        stepLabel.setText("done");
                        progressBar.setProgress(1.0);
                        setEtaDone();
                        lastOutputDir = finalOut;
                    }
                    if (jobProgressWindow != null && jobProgressWindow.isShowing()) {
                        jobProgressWindow.markCompleted(finalOut);
                    }
                    addUserLog("SUCCESS", withJobPrefix(jobId, "Job completed. Output: " + finalOut));
                    if (notifyDoneBox.isSelected() && finalOut != null && !finalOut.isBlank()) {
                        openPath(finalOut);
                    }
                    if (sidecarPath != null) {
                        addUserLog("INFO", withJobPrefix(jobId, "Speaker sidecar ready: " + sidecarPath));
                        promptSpeakerMapping(sidecarPath);
                    }
                    refreshOperationsDiagnostics();
                    refreshDashboardData();
                    refreshJobsSilently();
                }
                case "job.failed" -> {
                    updateJobStatus(jobId, "failed");
                    if (operationsStatusLabel != null) {
                        operationsStatusLabel.setText("Failed");
                    }
                    JsonNode err = payload.path("error");
                    String message = err.path("message").asText("Pipeline failed");
                    addTechnicalLog("ERROR", withJobPrefix(jobId, message));
                    String traceback = trimToEmpty(err.path("traceback").asText(""));
                    if (!traceback.isBlank()) {
                        addTechnicalLog("ERROR", withJobPrefix(jobId, "Traceback:\n" + traceback));
                    }
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        releaseGlobalRunLockIfOwned();
                        statusLabel.setText("Failed");
                        stepLabel.setText("error");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                    }
                    if (jobProgressWindow != null && jobProgressWindow.isShowing()) {
                        jobProgressWindow.markFailed(message);
                    }
                    refreshOperationsDiagnostics();
                    refreshDashboardData();
                    refreshJobsSilently();
                }
                case "job.cancelled" -> {
                    updateJobStatus(jobId, "cancelled");
                    if (operationsStatusLabel != null) {
                        operationsStatusLabel.setText("Cancelled");
                    }
                    addUserLog("WARN", withJobPrefix(jobId, "Job cancelled."));
                    if (jobId != null && !jobId.isBlank() && jobId.equals(currentJobId)) {
                        setRunning(false);
                        releaseGlobalRunLockIfOwned();
                        statusLabel.setText("Cancelled");
                        stepLabel.setText("cancelled");
                        progressBar.setProgress(0.0);
                        resetEtaDisplay();
                    }
                    if (jobProgressWindow != null && jobProgressWindow.isShowing()) {
                        jobProgressWindow.markCancelled();
                    }
                    refreshOperationsDiagnostics();
                    refreshDashboardData();
                    refreshJobsSilently();
                }
                case "job.cancel_requested" -> {
                    addUserLog("INFO", withJobPrefix(jobId, "Cancel request accepted."));
                    if (jobProgressWindow != null && jobProgressWindow.isShowing()) {
                        jobProgressWindow.markCancelRequested();
                    }
                }
                case "summary.started" -> {
                    String operationId = trimToEmpty(payload.path("operation_id").asText(""));
                    if (!operationId.equals(trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    appendFilesSummaryLog("Summary started.");
                    updateFilesSummaryProgress("started", 0.0, false);
                }
                case "summary.progress" -> {
                    String operationId = trimToEmpty(payload.path("operation_id").asText(""));
                    if (!operationId.equals(trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    String step = trimToEmpty(payload.path("step").asText("running"));
                    double overall = payload.path("overall_pct").asDouble(0.0);
                    boolean indeterminate = payload.path("indeterminate").asBoolean(false);
                    updateFilesSummaryProgress(step, overall, indeterminate);
                }
                case "summary.log" -> {
                    String operationId = trimToEmpty(payload.path("operation_id").asText(""));
                    if (!operationId.equals(trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    appendFilesSummaryLog(payload.path("line").asText(""));
                }
                case "summary.completed" -> {
                    String operationId = trimToEmpty(payload.path("operation_id").asText(""));
                    if (!operationId.equals(trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    String outputPath = trimToEmpty(payload.path("output_path").asText(""));
                    markFilesSummaryCompleted(outputPath);
                }
                case "summary.failed" -> {
                    String operationId = trimToEmpty(payload.path("operation_id").asText(""));
                    if (!operationId.equals(trimToEmpty(activeSummaryOperationId))) {
                        return;
                    }
                    markFilesSummaryFailed(payload.path("error").asText("Summary failed."));
                }
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
        if (replayJobButton != null) {
            replayJobButton.setDisable(selected == null);
        }
        if (removeJobButton != null) {
            removeJobButton.setDisable(selected == null);
        }
        if (operationsJobsTable != null && operationsJobsTable.getSelectionModel().getSelectedItem() != selected) {
            if (selected == null) {
                operationsJobsTable.getSelectionModel().clearSelection();
            } else {
                operationsJobsTable.getSelectionModel().select(selected);
            }
        }

        selectedJobLogFilter = selected == null ? "" : shortJobId(selected.getJobId());
        if (logsLinkSelectedJobBox != null && logsLinkSelectedJobBox.isSelected()) {
            refreshFilteredLogs();
        } else if (logsJobFilterLabel != null) {
            if (trimToEmpty(selectedJobLogFilter).isBlank()) {
                logsJobFilterLabel.setText("Log scope: all jobs");
            } else {
                logsJobFilterLabel.setText("Log scope: selected job [" + selectedJobLogFilter + "]");
            }
        }

        if (selected == null || backendClient == null) {
            if (jobDetailArea != null && selected == null) {
                jobDetailArea.setText("");
            }
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
        ModuleUiSchema schema = resolveModuleUiSchema(activeModule);
        ObjectNode params = mapper.createObjectNode();
        boolean activeModuleOnly = jobsActiveModuleOnlyBox == null || jobsActiveModuleOnlyBox.isSelected();
        if (schema.jobsFilterModule() && activeModuleOnly) {
            params.put("module_id", activeModule);
        }
        if (!trimToEmpty(activeProjectId).isBlank()) {
            params.put("project_id", trimToEmpty(activeProjectId));
        }

        backendClient.sendRequest("list_jobs", params)
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
                        String projectHint = trimToEmpty(activeProjectId).isBlank()
                                ? ""
                                : " | project=" + trimToEmpty(activeProjectId);
                        addUserLog(
                                "INFO",
                                "Job history refreshed for "
                                        + moduleLabel(activeModule)
                                        + ". Jobs: "
                                        + jobsById.size()
                                        + projectHint
                        );
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
        refreshJobFilters();
    }

    private void refreshJobFilters() {
        String query = jobsSearchField == null
                ? ""
                : trimToEmpty(jobsSearchField.getText()).toLowerCase(Locale.ROOT);
        String statusFilter = jobsStatusFilterBox == null
                ? "ALL"
                : trimToEmpty(jobsStatusFilterBox.getValue()).toLowerCase(Locale.ROOT);
        boolean activeOnly = jobsActiveModuleOnlyBox != null && jobsActiveModuleOnlyBox.isSelected();
        String activeModuleLabel = moduleLabel(activeModule).toLowerCase(Locale.ROOT);

        filteredJobRows.setPredicate(row -> {
            if (row == null) {
                return false;
            }

            if (activeOnly) {
                String rowModule = trimToEmpty(row.getMode()).toLowerCase(Locale.ROOT);
                if (!rowModule.equals(activeModuleLabel)) {
                    return false;
                }
            }

            if (!statusFilter.isBlank() && !"all".equals(statusFilter)) {
                String rowStatus = trimToEmpty(row.getStatus()).toLowerCase(Locale.ROOT);
                if (!rowStatus.equals(statusFilter)) {
                    return false;
                }
            }

            if (!query.isBlank()) {
                String haystack = String.join(
                                " ",
                                trimToEmpty(row.getJobId()),
                                trimToEmpty(row.getStatus()),
                                trimToEmpty(row.getMode()),
                                trimToEmpty(row.getSource()),
                                trimToEmpty(row.getCreated())
                        )
                        .toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    return false;
                }
            }

            return true;
        });

        if (jobsInfoLabel != null) {
            jobsInfoLabel.setText("Showing " + filteredJobRows.size() + " of " + jobRows.size() + " jobs.");
        }
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

    private void mergeSpeakerProfilesFromMapNode(JsonNode mapNode) {
        if (mapNode == null || !mapNode.isObject()) {
            return;
        }
        Map<String, String> merged = collectSpeakerProfiles();
        mapNode.fields().forEachRemaining(entry -> {
            String label = normalizeSpeakerLabel(entry.getKey());
            if (label.isBlank()) {
                return;
            }
            String mappedName = trimToEmpty(entry.getValue().asText(""));
            String existing = trimToEmpty(merged.getOrDefault(label, ""));
            if (existing.isBlank() || !mappedName.isBlank()) {
                merged.put(label, mappedName);
            } else {
                merged.putIfAbsent(label, existing);
            }
        });
        applySpeakerProfilesMap(merged);
    }

    private void mergeSpeakerProfilesFromSidecar(Path sidecarPath) {
        if (sidecarPath == null || !Files.isRegularFile(sidecarPath)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(sidecarPath, StandardCharsets.UTF_8));
            Map<String, String> merged = collectSpeakerProfiles();

            JsonNode speakerMapNode = root.path("speaker_map");
            if (speakerMapNode.isObject()) {
                speakerMapNode.fields().forEachRemaining(entry -> {
                    String label = normalizeSpeakerLabel(entry.getKey());
                    if (label.isBlank()) {
                        return;
                    }
                    String mappedName = trimToEmpty(entry.getValue().asText(""));
                    String existing = trimToEmpty(merged.getOrDefault(label, ""));
                    if (existing.isBlank() || !mappedName.isBlank()) {
                        merged.put(label, mappedName);
                    } else {
                        merged.putIfAbsent(label, existing);
                    }
                });
            }

            JsonNode segmentsNode = root.path("segments");
            if (segmentsNode.isArray()) {
                for (JsonNode seg : segmentsNode) {
                    String label = normalizeSpeakerLabel(seg.path("speaker_id").asText(""));
                    if (!label.isBlank()) {
                        merged.putIfAbsent(label, "");
                    }
                }
            }

            applySpeakerProfilesMap(merged);
        } catch (Exception ex) {
            addTechnicalLog("WARN", "Could not load speaker labels from sidecar: " + rootMessage(ex));
        }
    }

    private int countSpeakersInSidecar(Path sidecarPath) {
        if (sidecarPath == null || !Files.isRegularFile(sidecarPath)) {
            return 0;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(sidecarPath, StandardCharsets.UTF_8));
            Set<String> labels = new LinkedHashSet<>();

            JsonNode speakerMapNode = root.path("speaker_map");
            if (speakerMapNode.isObject()) {
                speakerMapNode.fieldNames().forEachRemaining(label -> {
                    String normalized = normalizeSpeakerLabel(label);
                    if (!normalized.isBlank()) {
                        labels.add(normalized);
                    }
                });
            }

            JsonNode segmentsNode = root.path("segments");
            if (segmentsNode.isArray()) {
                for (JsonNode seg : segmentsNode) {
                    String normalized = normalizeSpeakerLabel(seg.path("speaker_id").asText(""));
                    if (!normalized.isBlank()) {
                        labels.add(normalized);
                    }
                }
            }
            return labels.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void applySpeakerProfilesMap(Map<String, String> profiles) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>(profiles.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, String> entry) -> speakerLabelSortKey(entry.getKey()))
                .thenComparing(Map.Entry::getKey));

        speakerProfiles.clear();
        for (Map.Entry<String, String> entry : sorted) {
            String label = normalizeSpeakerLabel(entry.getKey());
            if (label.isBlank()) {
                continue;
            }
            speakerProfiles.add(new SpeakerProfileRow(label, trimToEmpty(entry.getValue())));
        }
        refreshProfilePreview();
    }

    private Path extractDiarizationSidecarPath(JsonNode runResult) {
        if (runResult == null || runResult.isMissingNode()) {
            return null;
        }
        JsonNode artifacts = runResult.path("artifacts");
        if (!artifacts.isArray()) {
            return null;
        }
        for (JsonNode item : artifacts) {
            String pathText = trimToEmpty(item.path("path").asText(""));
            if (pathText.isBlank()) {
                continue;
            }
            String kind = trimToEmpty(item.path("kind").asText(""));
            String lowerPath = pathText.toLowerCase(Locale.ROOT);
            if ("sidecar".equalsIgnoreCase(kind) || lowerPath.endsWith(".diarization.json")) {
                try {
                    return Path.of(pathText).toAbsolutePath().normalize();
                } catch (Exception ignored) {
                    // Continue scanning artifacts.
                }
            }
        }
        return null;
    }

    private void promptSpeakerMapping(Path sidecarPath) {
        if (sidecarPath == null) {
            return;
        }
        int speakerCount = countSpeakersInSidecar(sidecarPath);
        if (speakerCount <= 0) {
            return;
        }

        if (speakerCount == 1) {
            addUserLog(
                    "WARN",
                    "Diarization produced a single label. Try backend 'local_cluster_accurate' or increase max speakers."
            );
        }

        Alert mapDialog = new Alert(Alert.AlertType.CONFIRMATION);
        mapDialog.setTitle("Speaker Mapping");
        mapDialog.setHeaderText("Diarization output is ready");
        mapDialog.setContentText(
                "Detected speaker labels: " + speakerCount + ".\n"
                        + "Open Diarization tab, set names (e.g. SPEAKER_00 -> Karel), then click 'Apply to Last Output'."
        );
        ButtonType mapNow = new ButtonType("Map now", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        mapDialog.getButtonTypes().setAll(mapNow, later);
        Stage owner = getStage();
        if (owner != null) {
            mapDialog.initOwner(owner);
        }
        applyThemeToDialog(mapDialog);
        Optional<ButtonType> choice = mapDialog.showAndWait();
        if (choice.isPresent() && choice.get() == mapNow) {
            selectModule(diarizationTab);
        }
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

    private void updateSourceModeUi() {
        if (RUN_SOURCE_OUTPUT_READONLY_PREVIEW) {
            if (localPathField != null) {
                localPathField.setDisable(false);
            }
            if (youtubeUrlField != null) {
                youtubeUrlField.setDisable(false);
            }
            if (sourceModeBox != null) {
                sourceModeBox.setDisable(true);
            }
            if (outputModeBox != null) {
                outputModeBox.setDisable(true);
            }
            if (localPathBrowseButton != null) {
                localPathBrowseButton.setDisable(true);
            }
            if (outputDirBrowseButton != null) {
                outputDirBrowseButton.setDisable(true);
            }
            if (playlistBox != null) {
                playlistBox.setDisable(true);
            }
            if (qualityBox != null) {
                qualityBox.setDisable(true);
            }
            if (keepOriginalsBox != null) {
                keepOriginalsBox.setDisable(true);
            }
            updateWizardState();
            return;
        }

        String mode = safeValue(sourceModeBox);
        boolean local = "local".equals(mode);

        localPathField.setDisable(!local);
        youtubeUrlField.setDisable(local);
        playlistBox.setDisable(local);
        qualityBox.setDisable(local);
        keepOriginalsBox.setDisable(local);
        updateWizardState();
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

    private void updateModuleSchemasFromCapabilities(JsonNode componentNodes) {
        if (componentNodes == null || !componentNodes.isArray()) {
            return;
        }

        moduleUiSchemas.clear();
        for (JsonNode item : componentNodes) {
            String moduleId = normalizeModuleId(item.path("module_id").asText(""));
            JsonNode schemaNode = item.path("ui_schema");
            if (!schemaNode.isObject()) {
                continue;
            }
            Set<String> tabs = parseSchemaSet(schemaNode.path("show_tabs"));
            tabs.retainAll(UI_SCHEMA_TABS);
            Set<String> sections = parseSchemaSet(schemaNode.path("show_sections"));
            Set<String> fields = parseSchemaSet(schemaNode.path("show_fields"));
            boolean jobsFilterModule = schemaNode.path("jobs_filter_module").asBoolean(true);

            moduleUiSchemas.put(
                    moduleId,
                    new ModuleUiSchema(
                            Collections.unmodifiableSet(tabs),
                            Collections.unmodifiableSet(sections),
                            Collections.unmodifiableSet(fields),
                            jobsFilterModule
                    )
            );
        }
    }

    private ModuleUiSchema resolveModuleUiSchema(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        ModuleUiSchema backendSchema = moduleUiSchemas.get(normalized);
        if (backendSchema != null) {
            return backendSchema;
        }
        ModuleComponent localComponent = moduleComponents.get(normalized);
        if (localComponent != null) {
            return toModuleUiSchema(localComponent.uiSchema());
        }
        return new ModuleUiSchema(Set.of(), Set.of(), Set.of(), true);
    }

    private ModuleUiSchema toModuleUiSchema(ModuleUiSchemaSpec spec) {
        if (spec == null) {
            return new ModuleUiSchema(Set.of(), Set.of(), Set.of(), true);
        }
        Set<String> tabs = new LinkedHashSet<>(spec.showTabs() == null ? Set.of() : spec.showTabs());
        tabs.retainAll(UI_SCHEMA_TABS);
        Set<String> sections = new LinkedHashSet<>(spec.showSections() == null ? Set.of() : spec.showSections());
        Set<String> fields = new LinkedHashSet<>(spec.showFields() == null ? Set.of() : spec.showFields());
        return new ModuleUiSchema(
                Collections.unmodifiableSet(tabs),
                Collections.unmodifiableSet(sections),
                Collections.unmodifiableSet(fields),
                spec.jobsFilterModule()
        );
    }

    private static Set<String> parseSchemaSet(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            String value = trimToEmpty(item.asText(""));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String resolveLocalVersion() {
        List<Path> candidates = new ArrayList<>();
        Path projectRoot = AppRuntimePaths.resolveProjectRoot();
        if (projectRoot != null) {
            candidates.add(projectRoot.resolve("version.txt"));
        }
        Path backendRoot = AppRuntimePaths.resolveBackendRoot();
        if (backendRoot != null) {
            candidates.add(backendRoot.resolve("version.txt"));
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
        controllerRunning = running;
        boolean hasActiveProject = activeProjectRoot != null && !trimToEmpty(activeProjectId).isBlank();
        boolean runActionLocked = running || isGlobalRunLockedByOtherController();
        startButton.setDisable(runActionLocked || !hasActiveProject);
        cancelButton.setDisable(!running);
        preflightButton.setDisable(runActionLocked || !hasActiveProject);
        moduleFlowActionButton.setDisable(runActionLocked || !hasActiveProject);
        runModuleButton.setDisable(runActionLocked);
        advancedModuleButton.setDisable(running);
        diarizationModuleButton.setDisable(running);
        logsModuleButton.setDisable(running);
        jobsModuleButton.setDisable(running);
        youtubeDubModuleButton.setDisable(running);
        settingsModuleButton.setDisable(running);
        if (wizardSourceButton != null) {
            wizardSourceButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (wizardOutputButton != null) {
            wizardOutputButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (wizardPreflightButton != null) {
            wizardPreflightButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (wizardRunButton != null) {
            wizardRunButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (wizardNextButton != null) {
            wizardNextButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (moduleSwitcherBox != null) {
            moduleSwitcherBox.setDisable(running);
        }
        updateModulePreviousButtonState();
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
        if (settingsPresetBox != null) {
            settingsPresetBox.setDisable(running);
        }
        if (settingsSearchField != null) {
            settingsSearchField.setDisable(running);
        }
        if (uiLanguageBox != null) {
            uiLanguageBox.setDisable(running || workspaceMigrationRunning);
        }
        if (settingsChangeWorkspaceRootButton != null) {
            settingsChangeWorkspaceRootButton.setDisable(running || workspaceMigrationRunning);
        }
        if (settingsResetProjectModuleButton != null) {
            settingsResetProjectModuleButton.setDisable(running || workspaceMigrationRunning || activeProjectRoot == null);
        }
        if (settingsSaveGlobalDefaultsButton != null) {
            settingsSaveGlobalDefaultsButton.setDisable(running || workspaceMigrationRunning);
        }
        if (jobsSearchField != null) {
            jobsSearchField.setDisable(running);
        }
        if (jobsStatusFilterBox != null) {
            jobsStatusFilterBox.setDisable(running);
        }
        if (jobsActiveModuleOnlyBox != null) {
            jobsActiveModuleOnlyBox.setDisable(running);
        }
        if (filesSearchField != null) {
            filesSearchField.setDisable(running);
        }
        if (filesSearchContentBox != null) {
            filesSearchContentBox.setDisable(running);
        }
        if (logsErrorsOnlyBox != null) {
            logsErrorsOnlyBox.setDisable(running);
        }
        if (logsPauseAutoscrollBox != null) {
            logsPauseAutoscrollBox.setDisable(running);
        }
        if (logsLinkSelectedJobBox != null) {
            logsLinkSelectedJobBox.setDisable(running);
        }
        if (settingsSavePresetButton != null) {
            settingsSavePresetButton.setDisable(running);
        }
        if (settingsLoadPresetButton != null) {
            settingsLoadPresetButton.setDisable(running || settingsPresetBox == null || settingsPresetBox.getItems().isEmpty());
        }
        if (settingsDeletePresetButton != null) {
            settingsDeletePresetButton.setDisable(running || settingsPresetBox == null || settingsPresetBox.getItems().isEmpty());
        }
        if (replayJobButton != null) {
            replayJobButton.setDisable(running || jobHistoryTable == null || jobHistoryTable.getSelectionModel().getSelectedItem() == null);
        }
        if (projectCreateButton != null) {
            projectCreateButton.setDisable(running);
        }
        if (projectSelectButton != null) {
            projectSelectButton.setDisable(running || projectTable == null || projectTable.getSelectionModel().getSelectedItem() == null);
        }
        if (projectDeleteButton != null) {
            projectDeleteButton.setDisable(running || projectTable == null || projectTable.getSelectionModel().getSelectedItem() == null);
        }
        if (projectOpenFolderButton != null) {
            projectOpenFolderButton.setDisable(running || (activeProjectRoot == null && (projectTable == null || projectTable.getSelectionModel().getSelectedItem() == null)));
        }
        if (projectRefreshButton != null) {
            projectRefreshButton.setDisable(running);
        }
        if (projectTable != null) {
            projectTable.setDisable(running);
        }
        if (filesImportButton != null) {
            filesImportButton.setDisable(running || activeProjectRoot == null);
        }
        if (filesRefreshButton != null) {
            filesRefreshButton.setDisable(running || activeProjectRoot == null);
        }
        if (filesOpenSelectedButton != null) {
            filesOpenSelectedButton.setDisable(running || currentSelectedWorkspaceFile() == null);
        }
        if (filesAiSummaryButton != null) {
            filesAiSummaryButton.setDisable(
                    running
                            || activeProjectRoot == null
                            || !isAiSummaryEligibleFile(currentSelectedWorkspaceFile())
                            || !trimToEmpty(activeSummaryOperationId).isBlank()
            );
        }
        if (filesOpenProjectFolderButton != null) {
            filesOpenProjectFolderButton.setDisable(running || activeProjectRoot == null);
        }
        if (filesHistoryButton != null) {
            filesHistoryButton.setDisable(running || activeEditedFilePath == null || activeProjectRoot == null);
        }
        if (filesGalleryHost != null) {
            filesGalleryHost.setDisable(running);
        }
        if (filesInputGalleryBox != null) {
            filesInputGalleryBox.setDisable(running);
        }
        if (filesOutputGalleryBox != null) {
            filesOutputGalleryBox.setDisable(running);
        }
        if (filesSummaryStartButton != null) {
            filesSummaryStartButton.setDisable(
                    running
                            || !isAiSummaryEligibleFile(currentSelectedWorkspaceFile())
                            || !trimToEmpty(activeSummaryOperationId).isBlank()
            );
        }
        if (filesSummaryOpenOutputButton != null) {
            filesSummaryOpenOutputButton.setDisable(running || trimToEmpty(activeSummaryOutputPath).isBlank());
        }
        if (projectFilesTable != null) {
            projectFilesTable.setDisable(running);
        }
        if (preflightChecksTable != null) {
            preflightChecksTable.setDisable(running);
        }
        if (operationsRefreshDiagnosticsButton != null) {
            operationsRefreshDiagnosticsButton.setDisable(running);
        }
        if (operationsOpenMonitorButton != null) {
            operationsOpenMonitorButton.setDisable(running);
        }
        if (operationsJobsTable != null) {
            operationsJobsTable.setDisable(running);
        }
        if (dashboardRefreshButton != null) {
            dashboardRefreshButton.setDisable(running);
        }
        if (dashboardOpenProjectButton != null) {
            dashboardOpenProjectButton.setDisable(running || activeProjectRoot == null);
        }
        if (dashboardImportFilesButton != null) {
            dashboardImportFilesButton.setDisable(running || activeProjectRoot == null);
        }
        if (dashboardOpenOutputFolderButton != null) {
            dashboardOpenOutputFolderButton.setDisable(running || activeProjectRoot == null);
        }
        if (dashboardOpenWizardButton != null) {
            dashboardOpenWizardButton.setDisable(runActionLocked || activeProjectRoot == null);
        }
        if (projectWorkspacePreflightButton != null) {
            projectWorkspacePreflightButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (projectWorkspaceRunWizardButton != null) {
            projectWorkspaceRunWizardButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (projectWorkspaceOverviewRunWizardButton != null) {
            projectWorkspaceOverviewRunWizardButton.setDisable(runActionLocked || !hasActiveProject);
        }
        if (projectWorkspaceSectionOverviewButton != null) {
            projectWorkspaceSectionOverviewButton.setDisable(running);
        }
        if (projectWorkspaceSectionWorkflowButton != null) {
            projectWorkspaceSectionWorkflowButton.setDisable(running);
        }
        if (projectWorkspaceSectionFilesButton != null) {
            projectWorkspaceSectionFilesButton.setDisable(running);
        }
        if (projectWorkspaceSectionJobsButton != null) {
            projectWorkspaceSectionJobsButton.setDisable(running);
        }
        if (projectWorkspaceSectionLogsButton != null) {
            projectWorkspaceSectionLogsButton.setDisable(running);
        }
        if (projectWorkspaceSectionSettingsButton != null) {
            projectWorkspaceSectionSettingsButton.setDisable(running);
        }
        if (dashboardOpenTimelineButton != null) {
            boolean hasTimeline = activeProjectRoot != null
                    && Files.isRegularFile(activeProjectRoot.resolve("jobs").resolve("timeline.jsonl"));
            dashboardOpenTimelineButton.setDisable(running || !hasTimeline);
        }
        if (dashboardOpenSelectedRecentButton != null) {
            dashboardOpenSelectedRecentButton.setDisable(
                    running
                            || dashboardRecentFilesTable == null
                            || dashboardRecentFilesTable.getSelectionModel().getSelectedItem() == null
            );
        }
        if (dashboardRecentFilesTable != null) {
            dashboardRecentFilesTable.setDisable(running);
        }
        refreshDashboardProjectGallery();
        updateEditorButtonsState();
        updateFilesSummaryButtonState();
        updateSpeakerMappingButtonState(running);
        updateWizardState();
        refreshWorkspaceSettingsUi();
        applySettingsScopeContext();
    }

    private void updateSpeakerMappingButtonState(boolean running) {
        if (applySpeakerMappingButton == null) {
            return;
        }
        boolean hasSidecar = lastDiarizationSidecarPath != null && Files.isRegularFile(lastDiarizationSidecarPath);
        applySpeakerMappingButton.setDisable(running || !hasSidecar);
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

    /**
     * Estimates overall ETA from incremental percent deltas.
     *
     * Uses rolling anchor points and conservative thresholds to avoid unstable
     * ETA jumps when progress updates are sparse.
     */
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

    /**
     * Compact ETA formatter shared by main progress and job progress window.
     */
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

    /**
     * Adapter exposing controlled UI operations to module implementations.
     */
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
            selectComboValue(diarizationBackendBox, normalizeDiarizationBackend(value));
        }

        @Override
        public void selectDiarizationAccuracyProfile(String value) {
            selectComboValue(diarizationAccuracyBox, normalizeDiarizationAccuracyProfile(value));
        }

        @Override
        public void setDiarizationMinSpeakers(int value) {
            if (diarizationMinSpinner.getValueFactory() != null) {
                diarizationMinSpinner.getValueFactory().setValue(Math.max(0, value));
            }
        }

        @Override
        public void setDiarizationMaxSpeakers(int value) {
            if (diarizationMaxSpinner.getValueFactory() != null) {
                diarizationMaxSpinner.getValueFactory().setValue(Math.max(0, value));
            }
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
        String normalizedLevel = normalizeLevel(level);
        String source = category == LogCategory.USER ? "ui.user" : "ui.technical";
        AppFileLogger.log(normalizedLevel, source, safeMessage);

        LogEntry entry = new LogEntry(LocalDateTime.now(), normalizedLevel, category, safeMessage);
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
            if (userLogArea != null) {
                userLogArea.appendText(entry.format() + "\n");
                userLogArea.setScrollTop(Double.MAX_VALUE);
            }
        } else {
            if (technicalLogArea != null) {
                technicalLogArea.appendText(entry.format() + "\n");
                technicalLogArea.setScrollTop(Double.MAX_VALUE);
            }
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
        if (userLogArea != null) {
            userLogArea.setText(userSb.toString());
            userLogArea.setScrollTop(Double.MAX_VALUE);
        }
        if (technicalLogArea != null) {
            technicalLogArea.setText(techSb.toString());
            technicalLogArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void refreshFilteredLogs() {
        String query = trimToEmpty(logSearchField.getText()).toLowerCase(Locale.ROOT);
        String selectedLevel = normalizeLevel(logLevelFilterBox.getValue());
        boolean includeUser = showUserLogsBox.isSelected();
        boolean includeTechnical = showTechnicalLogsBox.isSelected();
        boolean errorsOnly = logsErrorsOnlyBox != null && logsErrorsOnlyBox.isSelected();
        boolean followSelectedJob = logsLinkSelectedJobBox != null && logsLinkSelectedJobBox.isSelected();
        boolean pauseAutoscroll = logsPauseAutoscrollBox != null && logsPauseAutoscrollBox.isSelected();
        String jobFilterNeedle = followSelectedJob && !trimToEmpty(selectedJobLogFilter).isBlank()
                ? "[" + selectedJobLogFilter + "]"
                : "";

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (LogEntry entry : allLogs) {
            if (entry.category() == LogCategory.USER && !includeUser) {
                continue;
            }
            if (entry.category() == LogCategory.TECHNICAL && !includeTechnical) {
                continue;
            }
            if (errorsOnly && !"ERROR".equals(entry.level()) && !"WARN".equals(entry.level())) {
                continue;
            }
            if (!"ALL".equals(selectedLevel) && !entry.level().equals(selectedLevel)) {
                continue;
            }
            if (!query.isBlank() && !entry.message().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (!jobFilterNeedle.isBlank() && !entry.message().contains(jobFilterNeedle)) {
                continue;
            }
            sb.append(entry.format()).append("\n");
            shown += 1;
        }

        filteredLogArea.setText(sb.toString());
        if (!pauseAutoscroll) {
            filteredLogArea.setScrollTop(Double.MAX_VALUE);
        }
        if (operationsLogsArea != null) {
            operationsLogsArea.setText(sb.toString());
            operationsLogsArea.setScrollTop(Double.MAX_VALUE);
        }
        if (logsStreamInfoLabel != null) {
            logsStreamInfoLabel.setText("Showing " + shown + " of " + allLogs.size() + " entries.");
        }
        if (logsJobFilterLabel != null) {
            if (jobFilterNeedle.isBlank()) {
                logsJobFilterLabel.setText("Log scope: all jobs");
            } else {
                logsJobFilterLabel.setText("Log scope: selected job [" + selectedJobLogFilter + "]");
            }
        }
    }

    /**
     * Resolves bootstrap script/backend root from bundled app layout or dev layout.
     */
    private RuntimeBootstrapTarget resolveRuntimeBootstrapTarget() {
        Path appDataPath = resolveRuntimeAppDataDir();
        Set<Path> roots = new LinkedHashSet<>();
        String bootstrapScriptName = AppRuntimePaths.bootstrapScriptFileName();

        Path resolvedProjectRoot = AppRuntimePaths.resolveProjectRoot();
        if (resolvedProjectRoot != null) {
            roots.add(resolvedProjectRoot);
        }

        Path resolvedBackendRoot = AppRuntimePaths.resolveBackendRoot();
        if (resolvedBackendRoot != null) {
            roots.add(resolvedBackendRoot);
            Path backendParent = resolvedBackendRoot.getParent();
            if (backendParent != null) {
                roots.add(backendParent);
            }
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

            RuntimeBootstrapTarget directTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve(bootstrapScriptName),
                    normalizedRoot,
                    appDataPath
            );
            if (directTarget != null) {
                return directTarget;
            }

            RuntimeBootstrapTarget macAppTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("Contents").resolve("app").resolve("backend").resolve(bootstrapScriptName),
                    normalizedRoot.resolve("Contents").resolve("app").resolve("backend"),
                    appDataPath
            );
            if (macAppTarget != null) {
                return macAppTarget;
            }

            RuntimeBootstrapTarget bundledAppTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("app").resolve("backend").resolve(bootstrapScriptName),
                    normalizedRoot.resolve("app").resolve("backend"),
                    appDataPath
            );
            if (bundledAppTarget != null) {
                return bundledAppTarget;
            }

            RuntimeBootstrapTarget bundledTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("backend").resolve(bootstrapScriptName),
                    normalizedRoot.resolve("backend"),
                    appDataPath
            );
            if (bundledTarget != null) {
                return bundledTarget;
            }

            RuntimeBootstrapTarget devTarget = buildRuntimeBootstrapTarget(
                    normalizedRoot.resolve("scripts").resolve(bootstrapScriptName),
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
        return AppRuntimePaths.resolveAppDataDir();
    }

    /**
     * Opens runtime bootstrap dialog and executes the platform-specific bootstrap process.
     */
    private void showRuntimeBootstrapWindow(
            RuntimeBootstrapTarget target,
            boolean blockingLaunch,
            boolean repairMode,
            boolean fullRollbackOnFailure
    ) {
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
                ? "Reinstalling managed Python runtime, dependencies, AI models and FFmpeg."
                : "Installing managed Python runtime, dependencies, AI models and FFmpeg.");
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
        Button cancelSetupButton = new Button(repairMode ? "Cancel repair" : "Cancel setup");
        Button closeButton = new Button("Close");
        closeButton.setDisable(true);
        Button closeAppButton = new Button("Close application");
        closeAppButton.setVisible(false);
        closeAppButton.setManaged(false);
        closeAppButton.setDisable(true);

        HBox actions = new HBox(8.0, openLogButton, cancelSetupButton, closeButton, closeAppButton);
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
        applyThemeToStage(dialog);
        closeButton.setOnAction(event -> dialog.close());
        closeAppButton.setOnAction(event -> {
            dialog.close();
            Platform.exit();
        });
        AtomicBoolean cancelledByUser = new AtomicBoolean(false);
        AtomicReference<Process> bootstrapProcessRef = new AtomicReference<>();
        cancelSetupButton.setOnAction(event -> {
            if (!runtimeBootstrapRunning) {
                return;
            }

            cancelledByUser.set(true);
            cancelSetupButton.setDisable(true);
            status.setText("Cancellation requested. Stopping bootstrap process...");
            appendBootstrapLogLine(liveLogArea, "INFO: User requested cancellation.");
            addUserLog("WARN", repairMode ? "Runtime repair cancellation requested." : "Runtime setup cancellation requested.");

            Process runningProcess = bootstrapProcessRef.get();
            if (runningProcess != null && runningProcess.isAlive()) {
                try {
                    runningProcess.destroyForcibly();
                } catch (Exception ex) {
                    addTechnicalLog("WARN", "Could not terminate runtime bootstrap process: " + rootMessage(ex));
                }
            }
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

                if (cancelledByUser.get()) {
                    throw new IllegalStateException("Runtime bootstrap cancelled before process startup.");
                }

                List<String> command = new ArrayList<>();
                if (target.isPowerShellScript()) {
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
                } else {
                    command.add(resolveShellExecutable());
                    command.add(target.scriptPath().toString());
                    command.add("--with-models");
                    command.add("--install-diarization");
                    command.add("--download-ffmpeg");
                    command.add("--backend-root");
                    command.add(target.backendRoot().toString());
                    command.add("--progress-file");
                    command.add(target.progressFilePath().toString());
                    command.add("--log-file");
                    command.add(target.logFilePath().toString());
                }

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.directory(target.backendRoot().toFile());
                processBuilder.redirectErrorStream(true);
                processBuilder.environment().putIfAbsent("PYTHONUTF8", "1");
                processBuilder.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");

                Process process = processBuilder.start();
                bootstrapProcessRef.set(process);
                if (cancelledByUser.get() && process.isAlive()) {
                    process.destroyForcibly();
                }
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
                if (cancelledByUser.get() && exitCode == 0) {
                    exitCode = 130;
                }
            } catch (Exception ex) {
                if (!cancelledByUser.get()) {
                    processError = ex;
                }
            }

            int finalExitCode = exitCode;
            Exception finalProcessError = processError;
            boolean finalCancelledByUser = cancelledByUser.get();
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
                cancelSetupButton.setDisable(true);
                closeButton.setDisable(false);
                dialog.setOnCloseRequest(null);

                if (finalCancelledByUser) {
                    status.setText(repairMode ? "Runtime repair cancelled by user." : "Runtime setup cancelled by user.");
                    addTechnicalLog(
                            "WARN",
                            (repairMode ? "Runtime repair" : "Runtime setup")
                                    + " was cancelled by user. Log: " + target.logFilePath()
                    );
                    if (blockingLaunch) {
                        dialog.close();
                        if (fullRollbackOnFailure) {
                            runMandatoryRuntimeRollbackAndExit(target.appDataDir(), "Mandatory first-launch setup was cancelled.");
                        } else {
                            Platform.exit();
                        }
                    }
                    return;
                }

                if (finalProcessError != null) {
                    status.setText(repairMode ? "Runtime repair failed to start." : "Runtime setup failed to start.");
                    appendBootstrapLogLine(liveLogArea, "ERROR: " + rootMessage(finalProcessError));
                    addTechnicalLog(
                            "ERROR",
                            (repairMode ? "Runtime repair failed to start: " : "Online runtime setup failed to start: ")
                                    + rootMessage(finalProcessError)
                    );
                    if (blockingLaunch) {
                        dialog.close();
                        if (fullRollbackOnFailure) {
                            runMandatoryRuntimeRollbackAndExit(target.appDataDir(), "Mandatory first-launch setup failed.");
                        } else {
                            Platform.exit();
                        }
                    }
                    return;
                }

                if (finalExitCode == 0) {
                    progressBarLocal.setProgress(1.0);
                    status.setText(blockingLaunch || repairMode
                            ? "Runtime setup completed successfully. Restart the app."
                            : "Runtime setup completed successfully.");
                    if (blockingLaunch) {
                        closeButton.setDisable(true);
                        dialog.setOnCloseRequest(event -> event.consume());
                    }
                    closeAppButton.setDisable(false);
                    closeAppButton.setVisible(true);
                    closeAppButton.setManaged(true);
                    addUserLog("SUCCESS", repairMode
                            ? "Runtime repair completed successfully."
                            : "Online runtime setup completed successfully.");
                    if (blockingLaunch || repairMode || backendClient == null) {
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
                if (blockingLaunch) {
                    dialog.close();
                    if (fullRollbackOnFailure) {
                        runMandatoryRuntimeRollbackAndExit(target.appDataDir(), "Mandatory first-launch setup failed.");
                    } else {
                        Platform.exit();
                    }
                }
            });
        });
    }

    private void runMandatoryRuntimeRollbackAndExit(Path appDataDir, String reason) {
        Path rollbackRoot = appDataDir == null
                ? resolveRuntimeAppDataDir().toAbsolutePath().normalize()
                : appDataDir.toAbsolutePath().normalize();
        String details = trimToEmpty(reason);
        if (!details.isBlank()) {
            addTechnicalLog("WARN", details + " Starting rollback of: " + rollbackRoot);
        }
        addUserLog("WARN", "Rolling back incomplete first-launch runtime files...");

        CompletableFuture.runAsync(() -> {
            boolean deleted = deleteDirectoryWithRetries(rollbackRoot, 5);
            Platform.runLater(() -> {
                if (deleted) {
                    addTechnicalLog("INFO", "Rollback completed: " + rollbackRoot);
                } else {
                    addTechnicalLog("WARN", "Rollback could not fully remove: " + rollbackRoot);
                }
                Platform.exit();
            });
        });
    }

    private boolean deleteDirectoryWithRetries(Path root, int maxAttempts) {
        if (root == null) {
            return true;
        }
        if (!Files.exists(root)) {
            return true;
        }

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                if (Files.exists(root)) {
                    deleteDirectoryRecursively(root);
                }
                if (!Files.exists(root)) {
                    return true;
                }
            } catch (Exception ignored) {
                // retry below
            }

            try {
                Thread.sleep(Math.min(1000L, 200L * attempt));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return !Files.exists(root);
            }
        }

        try {
            for (String name : List.of("runtime", "cache", "assets", "workspace")) {
                Path candidate = root.resolve(name);
                if (Files.exists(candidate)) {
                    deleteDirectoryRecursively(candidate);
                }
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
        return !Files.exists(root);
    }

    /**
     * Parses script progress lines in format: TM_PROGRESS|<percent>|<message>.
     */
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

    private String resolveShellExecutable() {
        Path bash = Path.of("/bin/bash");
        if (Files.isRegularFile(bash)) {
            return bash.toString();
        }
        return "bash";
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
        if (projectWorkspaceStage != null && projectWorkspaceStage.isShowing()) {
            return projectWorkspaceStage;
        }
        return getMainStage();
    }

    private Stage getMainStage() {
        if (launcherMainStage != null) {
            return launcherMainStage;
        }
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
        Platform.runLater(this::onRunPreflight);
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

    private static String normalizeUiLanguage(String value) {
        String normalized = trimToEmpty(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case UI_LANG_CS -> UI_LANG_CS;
            default -> UI_LANG_EN;
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

    private static int speakerLabelSortKey(String value) {
        String label = normalizeSpeakerLabel(value);
        int idx = label.lastIndexOf('_');
        if (idx >= 0 && idx + 1 < label.length()) {
            String suffix = label.substring(idx + 1);
            try {
                return Integer.parseInt(suffix);
            } catch (Exception ignored) {
                // fallback below
            }
        }
        return Integer.MAX_VALUE;
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

    private static boolean containsAny(String query, String... keywords) {
        if (query == null || query.isBlank() || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && query.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String nowStamp() {
        return LocalDateTime.now().format(STAMP_FMT);
    }

    private static String trimTimestamp(String value) {
        String text = trimToEmpty(value);
        if (text.length() >= 19) {
            return text.substring(0, 19).replace('T', ' ');
        }
        return text;
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            AppFileLogger.log("ERROR", "ui.exception", "Unknown error (throwable was null).");
            return "Unknown error";
        }
        AppFileLogger.logException("ui.exception", throwable);
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return trimToEmpty(cursor.getMessage()).isBlank() ? cursor.toString() : cursor.getMessage();
    }

    /**
     * Lightweight JSON-backed key-value preferences store scoped under ui_preferences.
     *
     * The store is intentionally best-effort: IO failures should not crash runtime UI.
     */
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

        private synchronized boolean hasAnyWithPrefix(String prefix) {
            ensureLoaded();
            String normalizedPrefix = trimToEmpty(prefix);
            if (normalizedPrefix.isBlank()) {
                return false;
            }
            var fields = uiNode.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                if (key.startsWith(normalizedPrefix)) {
                    return true;
                }
            }
            return false;
        }

        private synchronized void copyPrefix(String sourcePrefix, String targetPrefix, boolean overwrite) {
            ensureLoaded();
            String source = trimToEmpty(sourcePrefix);
            String target = trimToEmpty(targetPrefix);
            if (source.isBlank() || target.isBlank()) {
                return;
            }
            List<String> keys = new ArrayList<>();
            uiNode.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                if (!key.startsWith(source)) {
                    continue;
                }
                String suffix = key.substring(source.length());
                String targetKey = target + suffix;
                if (!overwrite && uiNode.has(targetKey)) {
                    continue;
                }
                JsonNode value = uiNode.get(key);
                if (value != null) {
                    uiNode.set(targetKey, value.deepCopy());
                }
            }
        }

        private synchronized void removeByPrefix(String prefix) {
            ensureLoaded();
            String normalizedPrefix = trimToEmpty(prefix);
            if (normalizedPrefix.isBlank()) {
                return;
            }
            List<String> toRemove = new ArrayList<>();
            uiNode.fieldNames().forEachRemaining(key -> {
                if (key.startsWith(normalizedPrefix)) {
                    toRemove.add(key);
                }
            });
            toRemove.forEach(uiNode::remove);
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
