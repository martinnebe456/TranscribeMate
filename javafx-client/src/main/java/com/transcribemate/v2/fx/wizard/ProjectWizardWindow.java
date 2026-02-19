package com.transcribemate.v2.fx.wizard;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ProjectWizardWindow {
    @FunctionalInterface
    public interface StepRunner {
        boolean runStep(ModuleWizardStepSpec step, int index, int total, WizardValueStore store);
    }

    private final Stage stage;
    private final ModuleWizardSpec spec;
    private final StepRunner stepRunner;
    private final WizardValueStore store = new WizardValueStore();
    private final Label progressLabel = new Label();
    private final Label stepTitleLabel = new Label();
    private final Label stepDescriptionLabel = new Label();
    private final ListView<String> stepsListView = new ListView<>();
    private final Button backButton = new Button("Back");
    private final Button nextButton = new Button("Run step");
    private final Button cancelButton = new Button("Cancel");
    private final int[] currentIndex = {0};
    private boolean completed;

    public ProjectWizardWindow(
            Stage owner,
            ModuleWizardSpec spec,
            StepRunner stepRunner,
            Consumer<Stage> stageThemer
    ) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.stepRunner = Objects.requireNonNull(stepRunner, "stepRunner");
        this.stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Project Wizard");

        Label headingLabel = new Label(spec.title());
        headingLabel.getStyleClass().add("section-title");

        stepDescriptionLabel.setWrapText(true);
        stepDescriptionLabel.getStyleClass().add("small-label");
        progressLabel.getStyleClass().add("small-label");
        stepsListView.setFocusTraversable(false);
        stepsListView.setPrefHeight(180.0);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8.0, backButton, spacer, cancelButton, nextButton);

        VBox root = new VBox(10.0, headingLabel, progressLabel, stepTitleLabel, stepDescriptionLabel, stepsListView, actions);
        root.setPadding(new Insets(12.0));
        root.getStyleClass().addAll("app-shell", "wizard-window", "card-pane");

        Scene scene = new Scene(root, 760.0, 520.0);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
        if (stageThemer != null) {
            stageThemer.accept(stage);
        }

        setupHandlers();
        refreshUi();
    }

    public ModuleWizardResult showAndWait() {
        stage.showAndWait();
        return new ModuleWizardResult(completed, store.asMap());
    }

    private void setupHandlers() {
        cancelButton.setOnAction(event -> {
            completed = false;
            stage.close();
        });
        stage.setOnCloseRequest(event -> completed = false);

        backButton.setOnAction(event -> {
            if (currentIndex[0] > 0) {
                currentIndex[0] -= 1;
                refreshUi();
            }
        });

        nextButton.setOnAction(event -> runCurrentStep());
    }

    private void runCurrentStep() {
        List<ModuleWizardStepSpec> steps = spec.steps();
        if (steps.isEmpty()) {
            completed = true;
            stage.close();
            return;
        }

        ModuleWizardStepSpec step = steps.get(currentIndex[0]);
        boolean ok = stepRunner.runStep(step, currentIndex[0], steps.size(), store);
        if (!ok) {
            return;
        }
        if (currentIndex[0] >= steps.size() - 1) {
            completed = true;
            stage.close();
            return;
        }
        currentIndex[0] += 1;
        refreshUi();
    }

    private void refreshUi() {
        List<ModuleWizardStepSpec> steps = spec.steps();
        int total = Math.max(steps.size(), 1);
        int index = Math.max(0, Math.min(currentIndex[0], total - 1));

        if (steps.isEmpty()) {
            progressLabel.setText("No steps defined.");
            stepTitleLabel.setText("-");
            stepDescriptionLabel.setText("This module does not define wizard steps.");
            backButton.setDisable(true);
            nextButton.setText("Finish");
            return;
        }

        ModuleWizardStepSpec step = steps.get(index);
        progressLabel.setText("Step " + (index + 1) + " / " + total);
        stepTitleLabel.setText(step.title());
        stepDescriptionLabel.setText(step.description());

        List<String> rows = new ArrayList<>();
        for (int i = 0; i < steps.size(); i += 1) {
            ModuleWizardStepSpec item = steps.get(i);
            String prefix = i < index ? "[done] " : (i == index ? "[current] " : "[next] ");
            rows.add(prefix + item.title());
        }
        stepsListView.getItems().setAll(rows);
        stepsListView.getSelectionModel().select(index);

        backButton.setDisable(index <= 0);
        nextButton.setText(index >= total - 1 ? "Finish wizard" : "Run step");
    }
}

