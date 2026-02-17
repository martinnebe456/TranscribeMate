package com.transcribemate.v2.fx;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public final class ConferenceFileRow {
    private final StringProperty filePath = new SimpleStringProperty("");
    private final StringProperty speaker = new SimpleStringProperty("");
    private final StringProperty description = new SimpleStringProperty("");
    private final StringProperty lectureDate = new SimpleStringProperty("");

    public ConferenceFileRow(String filePath, String speaker, String description, String lectureDate) {
        this.filePath.set(filePath == null ? "" : filePath);
        this.speaker.set(speaker == null ? "" : speaker);
        this.description.set(description == null ? "" : description);
        this.lectureDate.set(lectureDate == null ? "" : lectureDate);
    }

    public String getFilePath() {
        return filePath.get();
    }

    public void setFilePath(String value) {
        filePath.set(value == null ? "" : value);
    }

    public StringProperty filePathProperty() {
        return filePath;
    }

    public String getSpeaker() {
        return speaker.get();
    }

    public void setSpeaker(String value) {
        speaker.set(value == null ? "" : value);
    }

    public StringProperty speakerProperty() {
        return speaker;
    }

    public String getDescription() {
        return description.get();
    }

    public void setDescription(String value) {
        description.set(value == null ? "" : value);
    }

    public StringProperty descriptionProperty() {
        return description;
    }

    public String getLectureDate() {
        return lectureDate.get();
    }

    public void setLectureDate(String value) {
        lectureDate.set(value == null ? "" : value);
    }

    public StringProperty lectureDateProperty() {
        return lectureDate;
    }
}
