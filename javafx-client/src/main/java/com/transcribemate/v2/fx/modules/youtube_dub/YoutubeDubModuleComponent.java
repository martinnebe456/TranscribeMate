package com.transcribemate.v2.fx.modules.youtube_dub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;
import com.transcribemate.v2.fx.wizard.ModuleWizardStepSpec;

import java.util.List;
import java.util.Set;

/**
 * Frontend module definition for YouTube dubbing workflows.
 */
public class YoutubeDubModuleComponent extends AbstractModuleComponent {
    /**
     * Registers module identity, flow guidance and schema selection.
     */
    public YoutubeDubModuleComponent() {
        super(
                "youtube_dub",
                "YouTube Dub",
                new ModuleFlowSpec(
                        "Module: YouTube Dub",
                        "1) Set YouTube source in Run.\n2) Choose target language in Settings.\n3) Start to generate translated voice-over and replace video audio.",
                        "open_advanced",
                        "Open Advanced"
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
                                "settings.model",
                                "settings.model_options",
                                "settings.source_lang",
                                "settings.target_lang",
                                "settings.batch_size"
                        ),
                        true
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        // Dubbing module enforces translated output and a default target language pair.
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("video_dub");
        ui.setDiarizationEnabled(false);
        ui.setTranslateSubtitles(true);
        ui.selectTargetLang("en->cs");
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        // Preserve hard module constraints when users switch from other modules.
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("video_dub");
        ui.setDiarizationEnabled(false);
        ui.setTranslateSubtitles(true);
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
        // Normalize payload to match YouTube dubbing backend expectations.
        source.put("mode", "youtube");
        output.put("mode", "video_dub");
        translation.put("enabled", true);
        diarization.put("enabled", false);
    }

    @Override
    public ModuleWizardSpec wizardSpec() {
        // Wizard includes dedicated dubbing settings step before final start.
        return new ModuleWizardSpec(
                id(),
                "YouTube Dub Wizard",
                List.of(
                        new ModuleWizardStepSpec("import_youtube", "YouTube source", "Enter YouTube URL, quality and playlist mode.", "import_youtube"),
                        new ModuleWizardStepSpec("dub", "Dub settings", "Configure target language and dubbing preferences.", "configure_dub"),
                        new ModuleWizardStepSpec("output", "Output options", "Set output prefix and related output options.", "configure_output"),
                        new ModuleWizardStepSpec("preflight_start", "Preflight and start", "Run preflight gate and confirm job start.", "preflight_start")
                )
        );
    }
}
