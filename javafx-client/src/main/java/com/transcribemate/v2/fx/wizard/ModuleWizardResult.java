package com.transcribemate.v2.fx.wizard;

import java.util.Map;

public record ModuleWizardResult(
        boolean completed,
        Map<String, Object> values
) {
    public ModuleWizardResult {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}

