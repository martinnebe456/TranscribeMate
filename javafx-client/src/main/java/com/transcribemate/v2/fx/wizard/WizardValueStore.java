package com.transcribemate.v2.fx.wizard;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WizardValueStore {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        values.put(key, value);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }
}

