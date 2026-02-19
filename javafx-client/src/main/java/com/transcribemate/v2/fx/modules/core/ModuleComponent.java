package com.transcribemate.v2.fx.modules.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transcribemate.v2.fx.wizard.ModuleWizardSpec;

import java.util.List;

/**
 * Contract for one frontend module (offline, YouTube, conference, ...).
 *
 * Implementations define:
 * - identity/label used in UI and payloads
 * - UI schema visibility rules
 * - default field values and enforced constraints
 * - optional payload overrides and wizard steps
 */
public interface ModuleComponent {
    /**
     * Stable module identifier used in backend payloads and preferences.
     */
    String id();

    /**
     * Human-readable module label displayed in selectors.
     */
    String label();

    /**
     * User guidance shown in the module flow card.
     */
    ModuleFlowSpec flowSpec();

    /**
     * Optional UI visibility schema. Default means "show everything".
     */
    default ModuleUiSchemaSpec uiSchema() {
        return ModuleUiSchemaSpec.all();
    }

    /**
     * Optional wizard definition. Empty list means caller will use fallback steps.
     */
    default ModuleWizardSpec wizardSpec() {
        return new ModuleWizardSpec(
                id(),
                label() + " Wizard",
                List.of()
        );
    }

    /**
     * Apply module defaults when module is activated and no persisted state exists.
     */
    void applyDefaults(ModuleUiContext ui);

    /**
     * Enforce hard constraints after module switch (source/output modes, tab selection).
     */
    void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab);

    /**
     * Last-mile payload normalization before request is sent to backend.
     */
    void applyPayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    );

    /**
     * Minimal UI operations exposed to module implementations.
     *
     * MainController provides the concrete implementation and handles actual widgets.
     */
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
