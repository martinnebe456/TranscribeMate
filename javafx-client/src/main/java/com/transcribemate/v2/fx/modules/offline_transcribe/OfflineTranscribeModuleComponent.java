package com.transcribemate.v2.fx.modules.offline_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;

public class OfflineTranscribeModuleComponent extends AbstractModuleComponent {
    public OfflineTranscribeModuleComponent() {
        super(
                "offline_transcribe",
                "Offline A/V Transcript",
                new ModuleFlowSpec(
                        "Module: Offline A/V",
                        "1) Select local audio/video source.\n2) Configure model in Settings.\n3) Run Preflight and Start.",
                        "open_run",
                        "Open Run"
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        ui.selectSourceMode("local");
        ui.selectOutputMode("txt_only");
        ui.setDiarizationEnabled(false);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
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
        source.put("mode", "local");
        output.put("mode", "txt_only");
        translation.put("enabled", false);
        diarization.put("enabled", false);
    }
}
