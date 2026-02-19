package com.transcribemate.v2.fx.modules.speaker_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardStepSpec;

import java.util.List;
import java.util.Set;

/**
 * Frontend module definition for speaker-aware transcription with diarization.
 */
public class SpeakerTranscriptModuleComponent extends AbstractModuleComponent {
    /**
     * Registers module identity, visible schema and default diarization guidance.
     */
    public SpeakerTranscriptModuleComponent() {
        super(
                "speaker_transcribe",
                "Speaker Transcript",
                new ModuleFlowSpec(
                        "Module: Speaker Transcript",
                        "1) Configure local source in Run.\n2) Tune speaker options in Diarization (accuracy profile + backend + min/max speakers).\n3) Start and map detected speakers to names.",
                        "open_diarization",
                        "Open Diarization"
                ),
                new ModuleUiSchemaSpec(
                        Set.of("run", "diarization", "logs", "jobs", "settings"),
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
                                "run.local_path",
                                "run.output_dir",
                                "run.output_prefix",
                                "settings.model",
                                "settings.model_options",
                                "settings.source_lang",
                                "settings.batch_size",
                                "settings.speaker_hint",
                                "diarization.backend",
                                "diarization.accuracy",
                                "diarization.minmax",
                                "diarization.options",
                                "diarization.runtime",
                                "diarization.profiles",
                                "diarization.apply_mapping"
                        ),
                        true
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        // Speaker flow is diarization-first: enable mapping-related defaults out of the box.
        ui.selectSourceMode("local");
        ui.selectOutputMode("conference");
        ui.setDiarizationEnabled(true);
        ui.selectDiarizationAccuracyProfile("maximum");
        ui.selectDiarizationBackend("local_cluster_accurate");
        ui.setDiarizationMinSpeakers(2);
        ui.setDiarizationMaxSpeakers(8);
        ui.setDiarizationProfilePrefill(true);
        ui.setDiarizationPrefixSrt(true);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        // Hard-lock source/output modes required by this module.
        ui.selectSourceMode("local");
        ui.selectOutputMode("conference");
        ui.setDiarizationEnabled(true);
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
        // Final payload normalization before backend request is sent.
        source.put("mode", "local");
        output.put("mode", "conference");
        translation.put("enabled", false);
        diarization.put("enabled", true);
    }

    @Override
    public ModuleWizardSpec wizardSpec() {
        // Adds dedicated speaker configuration step between transcription and output.
        return new ModuleWizardSpec(
                id(),
                "Speaker Transcript Wizard",
                List.of(
                        new ModuleWizardStepSpec("import_local", "Import local source", "Copy audio/video into project input.", "import_local"),
                        new ModuleWizardStepSpec("transcription", "Transcription settings", "Set model and language defaults for this run.", "configure_transcription"),
                        new ModuleWizardStepSpec("speaker", "Speaker diarization", "Configure backend, accuracy and speaker limits.", "configure_speaker"),
                        new ModuleWizardStepSpec("output", "Output options", "Set output prefix and related output options.", "configure_output"),
                        new ModuleWizardStepSpec("preflight_start", "Preflight and start", "Run preflight gate and confirm job start.", "preflight_start")
                )
        );
    }
}
