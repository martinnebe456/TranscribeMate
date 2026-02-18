package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class WorkspaceFileRow {
    private final StringProperty relativePath = new SimpleStringProperty("");
    private final StringProperty type = new SimpleStringProperty("");
    private final StringProperty size = new SimpleStringProperty("");
    private final StringProperty modified = new SimpleStringProperty("");
    private final StringProperty absolutePath = new SimpleStringProperty("");

    public WorkspaceFileRow(String relativePath, String type, String size, String modified, String absolutePath) {
        this.relativePath.set(relativePath == null ? "" : relativePath);
        this.type.set(type == null ? "" : type);
        this.size.set(size == null ? "" : size);
        this.modified.set(modified == null ? "" : modified);
        this.absolutePath.set(absolutePath == null ? "" : absolutePath);
    }

    public String getRelativePath() {
        return relativePath.get();
    }

    public void setRelativePath(String value) {
        relativePath.set(value == null ? "" : value);
    }

    public StringProperty relativePathProperty() {
        return relativePath;
    }

    public String getType() {
        return type.get();
    }

    public void setType(String value) {
        type.set(value == null ? "" : value);
    }

    public StringProperty typeProperty() {
        return type;
    }

    public String getSize() {
        return size.get();
    }

    public void setSize(String value) {
        size.set(value == null ? "" : value);
    }

    public StringProperty sizeProperty() {
        return size;
    }

    public String getModified() {
        return modified.get();
    }

    public void setModified(String value) {
        modified.set(value == null ? "" : value);
    }

    public StringProperty modifiedProperty() {
        return modified;
    }

    public String getAbsolutePath() {
        return absolutePath.get();
    }

    public void setAbsolutePath(String value) {
        absolutePath.set(value == null ? "" : value);
    }

    public StringProperty absolutePathProperty() {
        return absolutePath;
    }
}
