package com.transcribemate.v2.fx.modules.conference_mode;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;

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
