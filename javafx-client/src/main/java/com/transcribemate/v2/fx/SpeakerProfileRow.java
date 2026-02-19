package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * JavaFX table row model for speaker label -> speaker name mapping.
 */
public final class SpeakerProfileRow {
    private final StringProperty label = new SimpleStringProperty("");
    private final StringProperty name = new SimpleStringProperty("");

    /**
     * Creates one speaker profile row with null-safe field normalization.
     */
    public SpeakerProfileRow(String label, String name) {
        this.label.set(label == null ? "" : label);
        this.name.set(name == null ? "" : name);
    }

    public String getLabel() {
        return label.get();
    }

    public void setLabel(String value) {
        label.set(value == null ? "" : value);
    }

    public StringProperty labelProperty() {
        return label;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String value) {
        name.set(value == null ? "" : value);
    }

    public StringProperty nameProperty() {
        return name;
    }
}
