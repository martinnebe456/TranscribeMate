package com.transcribemate.v2.fx.modules.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

public abstract class AbstractModuleComponent implements ModuleComponent {
    private final String id;
    private final String label;
    private final ModuleFlowSpec flowSpec;

    protected AbstractModuleComponent(String id, String label, ModuleFlowSpec flowSpec) {
        this.id = id;
        this.label = label;
        this.flowSpec = flowSpec;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public ModuleFlowSpec flowSpec() {
        return flowSpec;
    }

    @Override
    public void applyDefaults(ModuleUiContext ui) {
        // Default no-op.
    }

    @Override
    public void enforceConstraints(ModuleUiContext ui, boolean keepCurrentTab) {
        // Default no-op.
    }

    @Override
    public void applyPayloadOverrides(
            ObjectNode source,
            ObjectNode output,
            ObjectNode translation,
            ObjectNode diarization
    ) {
        // Default no-op.
    }
}
