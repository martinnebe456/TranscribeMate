package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class ProjectRow {
    private final StringProperty projectId = new SimpleStringProperty("");
    private final StringProperty projectName = new SimpleStringProperty("");
    private final StringProperty status = new SimpleStringProperty("");
    private final StringProperty updated = new SimpleStringProperty("");
    private final StringProperty path = new SimpleStringProperty("");

    public ProjectRow(String projectId, String projectName, String status, String updated, String path) {
        this.projectId.set(projectId == null ? "" : projectId);
        this.projectName.set(projectName == null ? "" : projectName);
        this.status.set(status == null ? "" : status);
        this.updated.set(updated == null ? "" : updated);
        this.path.set(path == null ? "" : path);
    }

    public String getProjectId() {
        return projectId.get();
    }

    public void setProjectId(String value) {
        projectId.set(value == null ? "" : value);
    }

    public StringProperty projectIdProperty() {
        return projectId;
    }

    public String getProjectName() {
        return projectName.get();
    }

    public void setProjectName(String value) {
        projectName.set(value == null ? "" : value);
    }

    public StringProperty projectNameProperty() {
        return projectName;
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

    public String getUpdated() {
        return updated.get();
    }

    public void setUpdated(String value) {
        updated.set(value == null ? "" : value);
    }

    public StringProperty updatedProperty() {
        return updated;
    }

    public String getPath() {
        return path.get();
    }

    public void setPath(String value) {
        path.set(value == null ? "" : value);
    }

    public StringProperty pathProperty() {
        return path;
    }
}
