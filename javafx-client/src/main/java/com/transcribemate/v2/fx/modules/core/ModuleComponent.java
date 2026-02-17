package com.transcribemate.v2.fx.modules.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ModuleComponent {
    String id();

    String label();

    ModuleFlowSpec flowSpec();

    void applyDefaults(ModuleUiContext ui);

    void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab);

    void applyPayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    );

    interface ModuleUiContext {
        void selectSourceMode(String value);

        void selectOutputMode(String value);

        void setDiarizationEnabled(boolean value);

        void selectDiarizationBackend(String value);

        void selectDiarizationAccuracyProfile(String value);

        void setDiarizationMinSpeakers(int value);

        void setDiarizationMaxSpeakers(int value);

        void setDiarizationProfilePrefill(boolean value);

        void setDiarizationPrefixSrt(boolean value);

        void setKeepOriginals(boolean value);

        void setCleanText(boolean value);

        void setSummaryPack(boolean value);

        void setTranslateSubtitles(boolean value);

        void selectSubtitleMode(String value);

        void selectTargetLang(String value);

        void selectRunTab();

        void selectAdvancedTab();

        void selectDiarizationTab();
    }
}
