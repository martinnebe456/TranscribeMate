package com.transcribemate.v2.fx;

import javafx.application.Application;

/*
Non-JavaFX main entrypoint used for packaged launchers.
This avoids launcher-side JavaFX module detection issues on Windows app-image builds.
*/

public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(TranscribeMateApp.class, args);
    }
}
