package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * JavaFX table row model for backend job history entries.
 *
 * The row keeps job metadata in StringProperty form so job tables can bind and
 * refresh incrementally without extra conversion layers.
 */
public final class JobRow {
    private final StringProperty jobId = new SimpleStringProperty("");
    private final StringProperty status = new SimpleStringProperty("");
    private final StringProperty mode = new SimpleStringProperty("");
    private final StringProperty source = new SimpleStringProperty("");
    private final StringProperty created = new SimpleStringProperty("");

    /**
     * Creates one job row and normalizes null values to empty strings.
     */
    public JobRow(String jobId, String status, String mode, String source, String created) {
        this.jobId.set(jobId == null ? "" : jobId);
        this.status.set(status == null ? "" : status);
        this.mode.set(mode == null ? "" : mode);
        this.source.set(source == null ? "" : source);
        this.created.set(created == null ? "" : created);
    }

    public String getJobId() {
        return jobId.get();
    }

    public void setJobId(String value) {
        jobId.set(value == null ? "" : value);
    }

    public StringProperty jobIdProperty() {
        return jobId;
    }

    public String getStatus() {
        return status.get();
    }

    public void setStatus(String value) {
        status.set(value == null ? "" : value);
    }

    public StringProperty statusProperty() {
        return status;
    }

    public String getMode() {
        return mode.get();
    }

    public void setMode(String value) {
        mode.set(value == null ? "" : value);
    }

    public StringProperty modeProperty() {
        return mode;
    }

    public String getSource() {
        return source.get();
    }

    public void setSource(String value) {
        source.set(value == null ? "" : value);
    }

    public StringProperty sourceProperty() {
        return source;
    }

    public String getCreated() {
        return created.get();
    }

    public void setCreated(String value) {
        created.set(value == null ? "" : value);
    }

    public StringProperty createdProperty() {
        return created;
    }
}
