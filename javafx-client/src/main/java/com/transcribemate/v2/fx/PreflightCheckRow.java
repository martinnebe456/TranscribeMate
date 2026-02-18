package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class PreflightCheckRow {
    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty status = new SimpleStringProperty("");
    private final StringProperty message = new SimpleStringProperty("");
    private final StringProperty suggestedFix = new SimpleStringProperty("");

    public PreflightCheckRow(String name, String status, String message, String suggestedFix) {
        this.name.set(name == null ? "" : name);
        this.status.set(status == null ? "" : status);
        this.message.set(message == null ? "" : message);
        this.suggestedFix.set(suggestedFix == null ? "" : suggestedFix);
    }

    public String getName() {
        return name.get();
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getStatus() {
        return status.get();
    }

    public StringProperty statusProperty() {
        return status;
    }

    public String getMessage() {
        return message.get();
    }

    public StringProperty messageProperty() {
        return message;
    }

    public String getSuggestedFix() {
        return suggestedFix.get();
    }

    public StringProperty suggestedFixProperty() {
        return suggestedFix;
    }
}
