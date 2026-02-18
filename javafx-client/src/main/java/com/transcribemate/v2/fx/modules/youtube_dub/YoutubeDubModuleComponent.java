package com.transcribemate.v2.fx.modules.youtube_dub;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.modules.core.AbstractModuleComponent;
import com.transcribemate.v2.fx.modules.core.ModuleFlowSpec;
import com.transcribemate.v2.fx.modules.core.ModuleUiSchemaSpec;

import java.util.Set;

public class YoutubeDubModuleComponent extends AbstractModuleComponent {
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
        ui.selectSourceMode("youtube");
        ui.selectOutputMode("video_dub");
        ui.setDiarizationEnabled(false);
        ui.setTranslateSubtitles(true);
        ui.selectTargetLang("en->cs");
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
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
        source.put("mode", "youtube");
        output.put("mode", "video_dub");
        translation.put("enabled", true);
        diarization.put("enabled", false);
    }
}
