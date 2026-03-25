package com.github.copilot.tray;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Application entry point. Initializes JavaFX (headless — no primary stage shown)
 * and delegates to TrayApplication for the system tray lifecycle.
 */
public class Main extends Application {

    private TrayApplication trayApp;

    public static void main(String[] args) {
        // Set macOS dock name (must be set before AWT/JavaFX initializes)
        System.setProperty("apple.awt.application.name", "Copilot Agentic Tray");
        launch(args);
    }

    @Override
    public void init() {
        // Prevent JavaFX from exiting when all windows are closed
        // (we live in the system tray)
        Platform.setImplicitExit(false);
    }

    @Override
    public void start(Stage primaryStage) {
        // Set the dock/taskbar icon (shows in Cmd+Tab / Alt+Tab)
        if (java.awt.Taskbar.isTaskbarSupported()) {
            var taskbar = java.awt.Taskbar.getTaskbar();
            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                var iconUrl = getClass().getResource("/icons/tray-idle.png");
                if (iconUrl != null) {
                    taskbar.setIconImage(new javax.swing.ImageIcon(iconUrl).getImage());
                }
            }
        }

        // Don't show the primary stage — the app lives in the system tray
        trayApp = new TrayApplication();
        trayApp.start();
    }

    @Override
    public void stop() {
        if (trayApp != null) {
            trayApp.shutdown();
        }
    }
}
