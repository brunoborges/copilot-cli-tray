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

    /**
     * @param sessionId   the session being attached to
     * @param sessionName display name of the session
     * @param onClose     called when the window is closed (should detach the session)
     */
    public SessionEventLogWindow(String sessionId, String sessionName, Runnable onClose) {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; "
                + "-fx-control-inner-background: #1a1a2e; -fx-text-fill: #cccccc;");

        var header = new Label("Session: " + sessionName + "  (" + sessionId + ")");
        header.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #aaa;");
        header.setPadding(new Insets(4));

        var root = new VBox(4, header, logArea);
        root.setPadding(new Insets(8));
        VBox.setVgrow(logArea, Priority.ALWAYS);

        stage = new Stage();
        stage.setTitle("Event Log — " + sessionName);
        stage.getIcons().add(new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/tray-idle.png")));
        stage.setScene(new Scene(root, 700, 500));
        stage.setOnCloseRequest(e -> {
            appendLog("— Detaching from session —");
            onClose.run();
        });

        appendLog("Attaching to session " + sessionId + "...");
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

    private void appendLog(String message) {
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
