package com.transcribemate.v2.fx.wizard;

import java.util.List;

public record ModuleWizardSpec(
        String moduleId,
        String title,
        List<ModuleWizardStepSpec> steps
) {
    public ModuleWizardSpec {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}

