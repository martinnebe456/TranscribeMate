package com.transcribemate.v2.fx.modules.conference_mode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;

import java.util.Set;

public class ConferenceModeModuleComponent extends AbstractModuleComponent {
    public ConferenceModeModuleComponent() {
        super(
                "conference_mode",
                "Conference Mode",
                new ModuleFlowSpec(
                        "Module: Conference Mode",
                        "1) Set local source and sync conference file rows.\n2) Fill speaker/description/date per file.\n3) Start to export conference transcripts.",
                        "open_advanced",
                        "Open Conference"
                ),
                new ModuleUiSchemaSpec(
                        Set.of("run", "advanced", "logs", "jobs", "settings"),
                        Set.of(
                                "run_source_card",
                                "run_output_card",
                                "simple_hint_card",
                                "advanced_conference_card",
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
        ui.selectSourceMode("local");
        ui.selectOutputMode("conference");
        ui.setDiarizationEnabled(false);
        ui.setCleanText(true);
        ui.setSummaryPack(true);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        ui.selectSourceMode("local");
        ui.selectOutputMode("conference");
        if (!keepCurrentTab) {
            ui.selectAdvancedTab();
        }
    }

    @Override
    public void applyPayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    ) {
        source.put("mode", "local");
        output.put("mode", "conference");
        translation.put("enabled", false);
        if (diarization != null) {
            // Conference module leaves diarization flag unchanged by payload override.
        }
    }
}
