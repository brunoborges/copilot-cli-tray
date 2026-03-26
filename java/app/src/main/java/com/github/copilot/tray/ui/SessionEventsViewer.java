package com.github.copilot.tray.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only viewer for a session's events.jsonl file.
 * Renders user messages, assistant messages, system events, and tool calls
 * in a chat-like layout with distinct visual styling for each role.
 */
public class SessionEventsViewer {

    private static final Logger LOG = LoggerFactory.getLogger(SessionEventsViewer.class);

    private static final Path SESSION_STORE = Path.of(
            System.getProperty("user.home"), ".copilot", "session-state");

    private final Stage stage;

    public SessionEventsViewer(String sessionId, String sessionName,
                               ThemeManager themeManager, Stage owner) {
        var events = loadEvents(sessionId);

        var messagesBox = new VBox(8);
        messagesBox.setPadding(new Insets(12));
        messagesBox.getStyleClass().add("events-viewer-messages");

        for (var event : events) {
            messagesBox.getChildren().add(buildEventNode(event));
        }

        if (events.isEmpty()) {
            var emptyLabel = new Label("No events found for this session.");
            emptyLabel.setStyle("-fx-text-fill: #888; -fx-font-style: italic;");
            messagesBox.getChildren().add(emptyLabel);
        }

        var scroll = new ScrollPane(messagesBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("events-viewer-scroll");

        var header = new Label("Session: " + sessionName + "  (" + sessionId + ")");
        header.getStyleClass().add("events-viewer-header");
        header.setPadding(new Insets(8));

        var statsLabel = new Label(buildStatsText(events));
        statsLabel.getStyleClass().add("events-viewer-stats");
        statsLabel.setPadding(new Insets(0, 8, 4, 8));

        var root = new VBox(header, statsLabel, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Events — " + sessionName);
        stage.getIcons().add(new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/tray-idle.png")));
        var scene = new Scene(root, 800, 600);
        if (themeManager != null) {
            themeManager.register(scene);
        }
        // Cmd+W / Ctrl+W to close
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(
                        javafx.scene.input.KeyCode.W,
                        javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                () -> stage.close());
        stage.setScene(scene);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    // =====================================================================
    // Event parsing
    // =====================================================================

    private record ParsedEvent(String type, String timestamp, String content,
                                List<ToolCall> toolCalls) {
        enum Role { USER, ASSISTANT, SYSTEM, TOOL }

        Role role() {
            return switch (type) {
                case "user.message" -> Role.USER;
                case "assistant.message" -> Role.ASSISTANT;
                case String s when s.startsWith("tool.") -> Role.TOOL;
                default -> Role.SYSTEM;
            };
        }
    }

    private record ToolCall(String name, String toolCallId) {}

    private List<ParsedEvent> loadEvents(String sessionId) {
        var eventsFile = SESSION_STORE.resolve(sessionId).resolve("events.jsonl");
        if (!Files.exists(eventsFile)) {
            // Try legacy single-file format
            eventsFile = SESSION_STORE.resolve(sessionId + ".jsonl");
        }
        if (!Files.exists(eventsFile)) {
            return List.of();
        }

        var events = new ArrayList<ParsedEvent>();
        try (var lines = Files.lines(eventsFile)) {
            for (var line : (Iterable<String>) lines::iterator) {
                var parsed = parseLine(line.trim());
                if (parsed != null) events.add(parsed);
            }
        } catch (IOException e) {
            LOG.error("Failed to read events.jsonl for session {}", sessionId, e);
        }
        return events;
    }

    private ParsedEvent parseLine(String line) {
        if (line.isEmpty()) return null;

        var type = extractField(line, "type");
        var timestamp = extractField(line, "timestamp");
        var content = extractNestedField(line, "data", "content");

        // Parse tool requests from assistant messages
        var toolCalls = new ArrayList<ToolCall>();
        if ("assistant.message".equals(type) && line.contains("\"toolRequests\"")) {
            parseToolRequests(line, toolCalls);
        }

        // For tool execution events, extract tool name
        if (type.equals("tool.execution_start") || type.equals("tool.execution_complete")) {
            var toolName = extractField(line, "name");
            if (toolName.isEmpty()) toolName = extractNestedField(line, "data", "name");
            if (!toolName.isEmpty()) {
                content = toolName;
            }
        }

        return new ParsedEvent(type, timestamp, content, toolCalls);
    }

    private void parseToolRequests(String line, List<ToolCall> toolCalls) {
        // Simple extraction of tool names from toolRequests array
        int idx = 0;
        while (true) {
            idx = line.indexOf("\"name\"", idx);
            if (idx < 0) break;
            // Check we're inside toolRequests (after the first occurrence of "name" which is the field)
            int nameStart = line.indexOf("\"name\"", idx);
            if (nameStart < 0) break;
            var name = extractValueAfterKey(line, nameStart);
            if (!name.isEmpty() && !name.equals("name")) {
                toolCalls.add(new ToolCall(name, ""));
            }
            idx = nameStart + 6;
        }
    }

    // =====================================================================
    // UI rendering
    // =====================================================================

    private Node buildEventNode(ParsedEvent event) {
        return switch (event.role()) {
            case USER -> buildUserMessage(event);
            case ASSISTANT -> buildAssistantMessage(event);
            case TOOL -> buildToolEvent(event);
            case SYSTEM -> buildSystemEvent(event);
        };
    }

    private Node buildUserMessage(ParsedEvent event) {
        var roleLabel = new Label("👤 User");
        roleLabel.getStyleClass().add("events-role-user");

        var contentLabel = new Label(truncate(event.content(), 2000));
        contentLabel.setWrapText(true);
        contentLabel.getStyleClass().add("events-content-user");

        var timeLabel = buildTimeLabel(event.timestamp());

        var header = new HBox(8, roleLabel, new Region(), timeLabel);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        var box = new VBox(4, header, contentLabel);
        box.getStyleClass().add("events-bubble-user");
        box.setPadding(new Insets(8));
        return box;
    }

    private Node buildAssistantMessage(ParsedEvent event) {
        var roleLabel = new Label("🤖 Assistant");
        roleLabel.getStyleClass().add("events-role-assistant");

        var timeLabel = buildTimeLabel(event.timestamp());

        var header = new HBox(8, roleLabel, new Region(), timeLabel);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        var box = new VBox(4, header);
        box.getStyleClass().add("events-bubble-assistant");
        box.setPadding(new Insets(8));

        if (!event.content().isEmpty()) {
            var contentLabel = new Label(truncate(event.content(), 2000));
            contentLabel.setWrapText(true);
            contentLabel.getStyleClass().add("events-content-assistant");
            box.getChildren().add(contentLabel);
        }

        if (!event.toolCalls().isEmpty()) {
            var toolsLabel = new Label("Tools: " + String.join(", ",
                    event.toolCalls().stream().map(ToolCall::name).distinct().toList()));
            toolsLabel.setWrapText(true);
            toolsLabel.getStyleClass().add("events-tool-calls");
            box.getChildren().add(toolsLabel);
        }

        return box;
    }

    private Node buildToolEvent(ParsedEvent event) {
        var icon = event.type().contains("start") ? "⚙" : "✓";
        var label = new Label(icon + " " + event.type()
                + (event.content().isEmpty() ? "" : ": " + event.content()));
        label.getStyleClass().add("events-tool-line");
        label.setWrapText(true);
        return label;
    }

    private Node buildSystemEvent(ParsedEvent event) {
        var text = event.type();
        if (!event.content().isEmpty()) {
            text += ": " + truncate(event.content(), 200);
        }
        var label = new Label("⚡ " + text);
        label.getStyleClass().add("events-system-line");
        label.setWrapText(true);

        var timeLabel = buildTimeLabel(event.timestamp());

        var row = new HBox(8, label, new Region(), timeLabel);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(2, 0, 2, 0));
        return row;
    }

    private Label buildTimeLabel(String timestamp) {
        var timeStr = "";
        if (timestamp != null && timestamp.length() >= 19) {
            // Extract HH:mm:ss from ISO timestamp
            timeStr = timestamp.substring(11, 19);
        }
        var label = new Label(timeStr);
        label.getStyleClass().add("events-timestamp");
        return label;
    }

    private String buildStatsText(List<ParsedEvent> events) {
        long userMsgs = events.stream().filter(e -> e.role() == ParsedEvent.Role.USER).count();
        long asstMsgs = events.stream().filter(e -> e.role() == ParsedEvent.Role.ASSISTANT).count();
        long toolExec = events.stream().filter(e -> e.type().equals("tool.execution_complete")).count();
        long sysEvents = events.stream().filter(e -> e.role() == ParsedEvent.Role.SYSTEM).count();
        return String.format("User: %d  ·  Assistant: %d  ·  Tool executions: %d  ·  System: %d  ·  Total: %d",
                userMsgs, asstMsgs, toolExec, sysEvents, events.size());
    }

    // =====================================================================
    // JSON field extraction (simple string-based, no JSON library needed)
    // =====================================================================

    private static String extractField(String json, String field) {
        var key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        return extractValueAfterKey(json, idx);
    }

    private static String extractNestedField(String json, String parent, String field) {
        var parentKey = "\"" + parent + "\"";
        int pIdx = json.indexOf(parentKey);
        if (pIdx < 0) return "";
        var fieldKey = "\"" + field + "\"";
        int fIdx = json.indexOf(fieldKey, pIdx);
        if (fIdx < 0) return "";
        return extractValueAfterKey(json, fIdx);
    }

    private static String extractValueAfterKey(String json, int keyIdx) {
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return "";

        if (json.charAt(i) == '"') {
            // String value — handle escaped quotes
            var sb = new StringBuilder();
            i++;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == '"') { sb.append('"'); i += 2; continue; }
                    if (next == 'n') { sb.append('\n'); i += 2; continue; }
                    if (next == '\\') { sb.append('\\'); i += 2; continue; }
                    if (next == 't') { sb.append('\t'); i += 2; continue; }
                    sb.append(c);
                    i++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        // Non-string value (number, boolean, null)
        int end = i;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != ']') end++;
        return json.substring(i, end).trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
