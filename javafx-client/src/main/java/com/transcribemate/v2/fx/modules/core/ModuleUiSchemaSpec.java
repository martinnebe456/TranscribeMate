package com.transcribemate.v2.fx.modules.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record ModuleUiSchemaSpec(
        Set<String> showTabs,
        Set<String> showSections,
        Set<String> showFields,
        boolean jobsFilterModule
) {
    public ModuleUiSchemaSpec {
        showTabs = immutableCopy(showTabs);
        showSections = immutableCopy(showSections);
        showFields = immutableCopy(showFields);
    }

    public static ModuleUiSchemaSpec all() {
        return new ModuleUiSchemaSpec(Set.of(), Set.of(), Set.of(), true);
    }

    private static Set<String> immutableCopy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
