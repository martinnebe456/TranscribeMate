package com.transcribemate.v2.fx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppRuntimePathsTest {
    @AfterEach
    void clearOverrides() {
        System.clearProperty("tm.appDataDir");
        System.clearProperty("tm.uiTestMode");
        System.clearProperty("os.name");
        System.clearProperty("user.home");
    }

    @Test
    void resolveAppDataDirHonorsSystemPropertyOverride() {
        Path override = Path.of("build", "tmp", "app-runtime-paths-test").toAbsolutePath().normalize();
        System.setProperty("tm.appDataDir", override.toString());

        assertEquals(override, AppRuntimePaths.resolveAppDataDir());
    }

    @Test
    void uiTestModeRecognizesSystemPropertyFlag() {
        System.setProperty("tm.uiTestMode", "true");

        assertTrue(AppRuntimePaths.isUiTestMode());
    }

    @Test
    void resolveDefaultWorkspaceRootUsesDocumentsOnMacWhenLegacyWorkspaceMissing() throws Exception {
        Path appData = Files.createTempDirectory("tm-mac-app-data");
        System.setProperty("os.name", "Mac OS X");
        System.setProperty("user.home", "/tmp/tm-mac-home");

        Path workspaceRoot = AppRuntimePaths.resolveDefaultWorkspaceRoot(appData);

        assertEquals(
                Path.of("/tmp/tm-mac-home", "Documents", "TranscribeMate", "workspace"),
                workspaceRoot
        );
    }

    @Test
    void resolveDefaultWorkspaceRootFallsBackToLegacyAppDataWorkspaceOnMac() throws Exception {
        Path appData = Files.createTempDirectory("tm-mac-app-data");
        Path legacyWorkspace = appData.resolve("workspace");
        Files.createDirectories(legacyWorkspace);
        System.setProperty("os.name", "Mac OS X");
        System.setProperty("user.home", "/tmp/tm-mac-home");

        Path workspaceRoot = AppRuntimePaths.resolveDefaultWorkspaceRoot(appData);

        assertEquals(legacyWorkspace, workspaceRoot);
    }

    @Test
    void resolveDefaultWorkspaceRootHonorsExplicitAppDataOverrideOnMac() {
        Path override = Path.of("/tmp/tm-mac-app-data-override");
        System.setProperty("os.name", "Mac OS X");
        System.setProperty("user.home", "/tmp/tm-mac-home");
        System.setProperty("tm.appDataDir", override.toString());

        Path workspaceRoot = AppRuntimePaths.resolveDefaultWorkspaceRoot(override);

        assertEquals(override.resolve("workspace"), workspaceRoot);
    }

    @Test
    void resolveDefaultWorkspaceRootUsesAppDataWorkspaceOnLinux() throws Exception {
        Path appData = Files.createTempDirectory("tm-linux-app-data");
        System.setProperty("os.name", "Linux");
        System.setProperty("user.home", "/tmp/tm-linux-home");

        Path workspaceRoot = AppRuntimePaths.resolveDefaultWorkspaceRoot(appData);

        assertEquals(appData.resolve("workspace"), workspaceRoot);
    }

    @Test
    void linuxUsesShellBootstrapAndCpuOnlyGpuPolicy() {
        System.setProperty("os.name", "Linux");

        assertEquals("bootstrap_runtime.sh", AppRuntimePaths.bootstrapScriptFileName());
        assertEquals("Prefer GPU (Linux build is CPU-only in this release)", AppRuntimePaths.gpuPreferenceLabelText());
        assertFalse(AppRuntimePaths.defaultGpuPreferenceSelected());
        assertFalse(AppRuntimePaths.isGpuPreferenceEditable());
        assertFalse(AppRuntimePaths.normalizeGpuPreference(true));
    }
}
