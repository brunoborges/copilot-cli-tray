package com.github.copilot.tray.ui;

import com.github.copilot.sdk.events.AbstractSessionEvent;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Window that displays a live stream of SDK events for an attached session.
 * When the window is closed, the provided detach callback is invoked.
 */
public class SessionEventLogWindow {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Stage stage;
    private final TextArea logArea;
    private Runnable extraCloseAction;

    /**
     * @param sessionId    the session being attached to
     * @param sessionName  display name of the session
     * @param onClose      called when the window is closed (should detach the session)
     * @param themeManager optional theme manager for stylesheet (may be null)
     * @param owner        optional owner stage for multi-monitor positioning (may be null)
     */
    public SessionEventLogWindow(String sessionId, String sessionName, Runnable onClose,
                                  ThemeManager themeManager, Stage owner) {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("event-log-area");

        var header = new Label("Session: " + sessionName + "  (" + sessionId + ")");
        header.getStyleClass().add("event-log-header");
        header.setPadding(new Insets(4));

        var root = new VBox(4, header, logArea);
        root.setPadding(new Insets(8));
        VBox.setVgrow(logArea, Priority.ALWAYS);

        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Event Log — " + sessionName);
        stage.getIcons().add(new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/tray-idle.png")));
        var scene = new Scene(root, 700, 500);
        if (themeManager != null) {
            themeManager.register(scene);
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            appendLog("— Detaching from session —");
            onClose.run();
            if (extraCloseAction != null) extraCloseAction.run();
        });

        appendLog("Attaching to session " + sessionId + "...");
    }

    /** Set an additional action to run when the window is closed (e.g., kill a process). */
    public void setOnCloseAction(Runnable action) {
        this.extraCloseAction = action;
    }

    /** Called from any thread when an SDK event arrives. */
    public void onEvent(String sessionId, AbstractSessionEvent event) {
        String eventName = event.getClass().getSimpleName();
        String detail = extractDetail(event);
        String line = eventName + (detail.isEmpty() ? "" : "  " + detail);
        Platform.runLater(() -> appendLog(line));
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    public void close() {
        stage.close();
    }

    void appendLog(String message) {
        String timestamp = LocalTime.now().format(TIME_FMT);
        logArea.appendText("[" + timestamp + "]  " + message + "\n");
    }

    /** Best-effort extraction of event payload for display. */
    private static String extractDetail(AbstractSessionEvent event) {
        try {
            var getDataMethod = event.getClass().getMethod("getData");
            var data = getDataMethod.invoke(event);
            if (data == null) return "";
            return data.toString();
        } catch (NoSuchMethodException e) {
            return "";
        } catch (Exception e) {
            return "(error reading data)";
        }
    }
}
