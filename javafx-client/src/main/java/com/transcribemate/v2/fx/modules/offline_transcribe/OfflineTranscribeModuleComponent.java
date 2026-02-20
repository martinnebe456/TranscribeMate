package com.transcribemate.v2.fx.modules.offline_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardStepSpec;

import java.util.List;
import java.util.Set;

/**
 * Frontend module definition for local offline transcription.
 */
public class OfflineTranscribeModuleComponent extends AbstractModuleComponent {
    /**
     * Registers module identity, flow help text and visible settings schema.
     */
    public OfflineTranscribeModuleComponent() {
        super(
                "offline_transcribe",
                "Offline A/V Transcript",
                new ModuleFlowSpec(
                        "Module: Offline A/V",
                        "1) Select local audio/video source.\n2) Configure model in Settings.\n3) Run Preflight and Start.",
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
                                "run.local_path",
                                "run.output_dir",
                                "run.output_prefix",
                                "settings.model",
                                "settings.model_options",
                                "settings.source_lang",
                                "settings.summary_lang",
                                "settings.summary_ai",
                                "settings.summary_model_tier",
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
        // Offline mode always starts from local input and text-only output.
        ui.selectSourceMode("local");
        ui.selectOutputMode("txt_only");
        ui.setDiarizationEnabled(false);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        // Keep constrained run modes even if previous module persisted different values.
        ui.selectSourceMode("local");
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
        // Backend payload must match offline module contract.
        source.put("mode", "local");
        output.put("mode", "txt_only");
        translation.put("enabled", false);
        diarization.put("enabled", false);
    }

    @Override
    public ModuleWizardSpec wizardSpec() {
        // Keep wizard deterministic to reduce setup friction for common offline flow.
        return new ModuleWizardSpec(
                id(),
                "Offline A/V Wizard",
                List.of(
                        new ModuleWizardStepSpec("import_local", "Import local source", "Copy audio/video into project input.", "import_local"),
                        new ModuleWizardStepSpec("transcription", "Transcription settings", "Set model and language defaults for this run.", "configure_transcription"),
                        new ModuleWizardStepSpec("output", "Output options", "Set output prefix and related output options.", "configure_output"),
                        new ModuleWizardStepSpec("preflight_start", "Preflight and start", "Run preflight gate and confirm job start.", "preflight_start")
                )
        );
    }
}
