package com.transcribemate.v2.fx.modules.youtube_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardStepSpec;

import java.util.List;
import java.util.Set;

/**
 * Frontend module definition for transcript extraction from YouTube sources.
 */
public class YoutubeTranscriptModuleComponent extends AbstractModuleComponent {
    /**
     * Registers module identity, flow card text and schema visibility.
     */
    public YoutubeTranscriptModuleComponent() {
        super(
                "youtube_transcribe",
                "YouTube Transcript",
                new ModuleFlowSpec(
                        "Module: YouTube Transcript",
                        "1) Set YouTube URL and video/playlist mode.\n2) Tune model in Settings.\n3) Run Preflight and Start.",
                        "open_run",
                        "Open Run"
                ),
                new ModuleUiSchemaSpec(
                        Set.of("run", "logs", "jobs", "settings"),
                        Set.of(
                                "run_source_card",
                                "run_output_card",
                                "simple_hint_card",
                                "settings_appearance_card",
                                "settings_runtime_card",
                                "settings_core_card",
                                "settings_module_flow_card",
                                "settings_module_scope_row",
                                "settings_preset_row"
                        ),
                        Set.of(
                                "run.youtube_url",
                                "run.playlist",
                                "run.quality",
                                "run.output_dir",
                                "run.output_prefix",
                                "run.keep_originals",
                                "settings.model",
                                "settings.model_options",
                                "settings.source_lang",
                                "settings.summary_lang",
                                "settings.batch_size",
                                "settings.text_options",
                                "settings.split_minutes"
                        ),
                        true
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        // YouTube transcript mode keeps originals and disables diarization by default.
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("txt_only");
        ui.setDiarizationEnabled(false);
        ui.setKeepOriginals(true);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        // Keep module-required source/output selection fixed.
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("txt_only");
        ui.setDiarizationEnabled(false);
        if (!keepCurrentTab) {
            ui.selectRunTab();
        }
    }

    @Override
    public void applyPayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    ) {
        // Final payload normalization for backend contract.
        source.put("mode", "youtube");
        output.put("mode", "txt_only");
        translation.put("enabled", false);
        diarization.put("enabled", false);
    }

    @Override
    public ModuleWizardSpec wizardSpec() {
        // Guided wizard focuses on YouTube import, transcription tuning and start gate.
        return new ModuleWizardSpec(
                id(),
                "YouTube Transcript Wizard",
                List.of(
                        new ModuleWizardStepSpec("import_youtube", "YouTube source", "Enter YouTube URL, quality and playlist mode.", "import_youtube"),
                        new ModuleWizardStepSpec("transcription", "Transcription settings", "Set model and language defaults for this run.", "configure_transcription"),
                        new ModuleWizardStepSpec("output", "Output options", "Set output prefix and related output options.", "configure_output"),
                        new ModuleWizardStepSpec("preflight_start", "Preflight and start", "Run preflight gate and confirm job start.", "preflight_start")
                )
        );
    }
}
