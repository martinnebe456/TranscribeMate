package com.transcribemate.v2.fx.modules.youtube_subtitles;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;

public class YoutubeSubtitlesModuleComponent extends AbstractModuleComponent {
    public YoutubeSubtitlesModuleComponent() {
        super(
                "youtube_subtitles",
                "YouTube Subtitles",
                new ModuleFlowSpec(
                        "Module: YouTube Subtitles",
                        "1) Set YouTube source in Run.\n2) Configure subtitle style + translation in Advanced.\n3) Start to render subtitled video.",
                        "open_advanced",
                        "Open Advanced"
                )
        );
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("video_subs");
        ui.setDiarizationEnabled(false);
        ui.setTranslateSubtitles(true);
        ui.selectSubtitleMode("soft");
        ui.selectTargetLang("en->cs");
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("video_subs");
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
        output.put("mode", "video_subs");
        diarization.put("enabled", false);
        if (translation != null) {
            // Translation remains user-controlled for subtitle rendering module.
        }
    }
}
