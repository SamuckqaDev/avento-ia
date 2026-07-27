package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemAutomationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsNonHttpUrls() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.openUrl("file:///etc/passwd"));
    }

    @Test
    void rejectsNonHttpUrlsForBrowserTabs() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(
                IllegalArgumentException.class, () -> service.openBrowserTab("Brave Browser", "file:///etc/passwd"));
    }

    @Test
    void rejectsControlCharactersInAppName() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.openApp("Finder\nTerminal"));
    }

    @Test
    void rejectsAppleScriptCharactersWhenClosingApp() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.closeApp("Finder\""));
    }

    @Test
    void rejectsAppleScriptCharactersWhenClosingBrowserTab() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.closeBrowserTab("Brave\""));
    }

    @Test
    void rejectsMissingPathBeforeOpening() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.openPath(tempDir.resolve("missing.txt")));
    }

    @Test
    void rejectsBlankNotificationTitle() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(IllegalArgumentException.class, () -> service.displayNotification("", "processo caiu"));
    }

    @Test
    void rejectsAppleScriptCharactersInNotificationMessage() {
        SystemAutomationService service = new SystemAutomationService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.displayNotification("Avento", "processo caiu \" com aspas"));
    }
}
