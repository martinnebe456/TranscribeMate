package com.transcribemate.v2.fx.modules.speaker_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;

public class SpeakerTranscriptModuleComponent extends AbstractModuleComponent {
    public SpeakerTranscriptModuleComponent() {
        super(
                "speaker_transcribe",
                "Speaker Transcript",
                new ModuleFlowSpec(
                        "Module: Speaker Transcript",
                        "1) Configure source in Run.\n2) Review diarization options in Diarization tab.\n3) Start and check speaker output.",
                        "open_diarization",
                        "Open Diarization"
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        ui.selectSourceMode("local");
        ui.selectOutputMode("conference");
        ui.setDiarizationEnabled(true);
        ui.selectDiarizationBackend("stable_local");
        ui.setDiarizationProfilePrefill(true);
        ui.setDiarizationPrefixSrt(true);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
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
        source.put("mode", "local");
        output.put("mode", "conference");
        translation.put("enabled", false);
        diarization.put("enabled", true);
    }
}
