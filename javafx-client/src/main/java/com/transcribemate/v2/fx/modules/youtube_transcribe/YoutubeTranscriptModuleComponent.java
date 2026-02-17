package com.transcribemate.v2.fx.modules.youtube_transcribe;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;

public class YoutubeTranscriptModuleComponent extends AbstractModuleComponent {
    public YoutubeTranscriptModuleComponent() {
        super(
                "youtube_transcribe",
                "YouTube Transcript",
                new ModuleFlowSpec(
                        "Module: YouTube Transcript",
                        "1) Set YouTube URL and video/playlist mode.\n2) Tune model in Settings.\n3) Run Preflight and Start.",
                        "open_run",
                        "Open Run"
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("txt_only");
        ui.setDiarizationEnabled(false);
        ui.setKeepOriginals(true);
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
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
        source.put("mode", "youtube");
        output.put("mode", "txt_only");
        translation.put("enabled", false);
        diarization.put("enabled", false);
    }
}
