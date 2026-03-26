package com.github.copilot.tray.ui;

import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages theme switching across all active scenes.
 * Themes: "dark", "light". "system" currently defaults to dark.
 */
public class ThemeManager {

    private static final String DARK_CSS = "/css/dashboard-dark.css";
    private static final String LIGHT_CSS = "/css/dashboard-light.css";

    private final List<Scene> managedScenes = new ArrayList<>();
    private String currentTheme = "dark";

    public void setTheme(String theme) {
        this.currentTheme = resolveTheme(theme);
        for (var scene : managedScenes) {
            applyToScene(scene);
        }
    }

    public String getCurrentTheme() {
        return currentTheme;
    }

    /** Register a scene so it receives theme updates. Applies current theme immediately. */
    public void register(Scene scene) {
        if (!managedScenes.contains(scene)) {
            managedScenes.add(scene);
        }
        applyToScene(scene);
    }

    /** Unregister a scene (e.g. when its window closes). */
    public void unregister(Scene scene) {
        managedScenes.remove(scene);
    }

    /** Get the CSS resource URL for the current theme. */
    public String getStylesheetUrl() {
        String path = "dark".equals(currentTheme) ? DARK_CSS : LIGHT_CSS;
        return getClass().getResource(path).toExternalForm();
    }

    private void applyToScene(Scene scene) {
        scene.getStylesheets().clear();
        scene.getStylesheets().add(getStylesheetUrl());
    }

    private static String resolveTheme(String theme) {
        if ("light".equalsIgnoreCase(theme)) return "light";
        if ("dark".equalsIgnoreCase(theme)) return "dark";
        // "system" — detect OS preference (macOS/Windows dark mode)
        return detectSystemTheme();
    }

    private static String detectSystemTheme() {
        // macOS: defaults read -g AppleInterfaceStyle returns "Dark" in dark mode
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            try {
                var proc = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if ("Dark".equalsIgnoreCase(output)) return "dark";
                return "light";
            } catch (Exception e) {
                return "dark";
            }
        }
        // Windows: check registry for dark mode
        if (os.contains("win")) {
            try {
                var proc = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
                var output = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (output.contains("0x0")) return "dark";
                return "light";
            } catch (Exception e) {
                return "dark";
            }
        }
        // Linux/other: default to dark
        return "dark";
    }
}
