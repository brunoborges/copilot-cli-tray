package com.github.copilot.tray.ui;

import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import com.github.copilot.tray.session.UsageSnapshot;
import eu.hansolo.tilesfx.Tile;
import eu.hansolo.tilesfx.TileBuilder;
import eu.hansolo.tilesfx.chart.ChartData;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.Collection;

/**
 * Real-time usage dashboard built with Hansolo TilesFX.
 * Mirrors the Copilot CLI {@code /context} output:
 * System/Tools, Messages, Free Space, Buffer breakdown
 * with a donut chart, gauge, and summary tiles.
 */
public class UsageDashboard extends VBox {

    private static final double TILE_W = 200;
    private static final double TILE_H = 200;
    private static final double SMALL_H = 140;

    // Context breakdown colors (matching CLI visual style)
    private static final Color COLOR_SYSTEM  = Color.web("#7b8cde"); // light blue/purple
    private static final Color COLOR_MSGS    = Color.web("#c0c0c0"); // silver
    private static final Color COLOR_FREE    = Color.web("#4a4a6a"); // dark muted
    private static final Color COLOR_BUFFER  = Color.web("#8a8aaa"); // medium gray-blue

    private final SessionManager sessionManager;

    // Session selector
    private final ComboBox<String> sessionPicker = new ComboBox<>();

    // Donut chart data
    private final ChartData systemToolsData;
    private final ChartData messagesData;
    private final ChartData freeSpaceData;
    private final ChartData bufferData;

    // Per-session tiles
    private final Tile donutTile;
    private final Tile contextGauge;
    private final Tile tokenCountTile;
    private final Tile modelTile;
    private final Tile statusTile;

    // Breakdown detail tiles
    private final Tile systemToolsTile;
    private final Tile messagesTokTile;
    private final Tile freeSpaceTile;
    private final Tile bufferTile;

    // Aggregate summary tiles
    private final Tile totalSessionsTile;
    private final Tile activeSessionsTile;
    private final Tile totalTokensTile;

    public UsageDashboard(SessionManager sessionManager) {
        this.sessionManager = sessionManager;

        setSpacing(14);
        setPadding(new Insets(14));
        setStyle("-fx-background-color: #1a1a2e;");

        // --- Session picker ---
        var pickerRow = new HBox(10);
        pickerRow.setAlignment(Pos.CENTER_LEFT);
        var pickerLabel = new Label("Session:");
        pickerLabel.setTextFill(Color.WHITE);
        pickerLabel.setStyle("-fx-font-size: 14px;");
        sessionPicker.setPrefWidth(450);
        sessionPicker.setOnAction(e -> onSessionSelected());
        pickerRow.getChildren().addAll(pickerLabel, sessionPicker);

        // --- Donut chart for context breakdown ---
        systemToolsData = new ChartData("System/Tools", 0, COLOR_SYSTEM);
        messagesData    = new ChartData("Messages", 0, COLOR_MSGS);
        freeSpaceData   = new ChartData("Free Space", 0, COLOR_FREE);
        bufferData      = new ChartData("Buffer", 0, COLOR_BUFFER);

        donutTile = TileBuilder.create()
                .skinType(Tile.SkinType.DONUT_CHART)
                .prefSize(TILE_W + 40, TILE_H + 40)
                .title("Context Usage")
                .chartData(systemToolsData, messagesData, freeSpaceData, bufferData)
                .animated(true)
                .build();

        // --- Overall gauge ---
        contextGauge = TileBuilder.create()
                .skinType(Tile.SkinType.GAUGE)
                .prefSize(TILE_W, TILE_H)
                .title("Context Used")
                .unit("%")
                .minValue(0)
                .maxValue(100)
                .value(0)
                .thresholdVisible(true)
                .threshold(80)
                .barColor(COLOR_SYSTEM)
                .thresholdColor(Tile.TileColor.RED.color)
                .animated(true)
                .build();

        // --- Token count ---
        tokenCountTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, TILE_H)
                .title("Tokens Used")
                .description("of 0")
                .value(0)
                .decimals(0)
                .animated(true)
                .build();

        // --- Model ---
        modelTile = TileBuilder.create()
                .skinType(Tile.SkinType.TEXT)
                .prefSize(TILE_W, TILE_H)
                .title("Model")
                .description("—")
                .textVisible(true)
                .build();

        // --- Status ---
        statusTile = TileBuilder.create()
                .skinType(Tile.SkinType.STATUS)
                .prefSize(TILE_W, TILE_H)
                .title("Status")
                .description("No session")
                .build();

        // --- Breakdown detail tiles ---
        systemToolsTile = TileBuilder.create()
                .skinType(Tile.SkinType.PERCENTAGE)
                .prefSize(TILE_W, SMALL_H)
                .title("System/Tools")
                .unit("%")
                .maxValue(100)
                .value(0)
                .barColor(COLOR_SYSTEM)
                .animated(true)
                .build();

        messagesTokTile = TileBuilder.create()
                .skinType(Tile.SkinType.PERCENTAGE)
                .prefSize(TILE_W, SMALL_H)
                .title("Messages")
                .unit("%")
                .maxValue(100)
                .value(0)
                .barColor(COLOR_MSGS)
                .animated(true)
                .build();

        freeSpaceTile = TileBuilder.create()
                .skinType(Tile.SkinType.PERCENTAGE)
                .prefSize(TILE_W, SMALL_H)
                .title("Free Space")
                .unit("%")
                .maxValue(100)
                .value(0)
                .barColor(COLOR_FREE)
                .animated(true)
                .build();

        bufferTile = TileBuilder.create()
                .skinType(Tile.SkinType.PERCENTAGE)
                .prefSize(TILE_W, SMALL_H)
                .title("Buffer")
                .unit("%")
                .maxValue(100)
                .value(0)
                .barColor(COLOR_BUFFER)
                .animated(true)
                .build();

        // --- Aggregate tiles ---
        totalSessionsTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title("Total Sessions")
                .value(0)
                .decimals(0)
                .animated(true)
                .build();

        activeSessionsTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title("Active Sessions")
                .value(0)
                .decimals(0)
                .animated(true)
                .build();

        totalTokensTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title("Total Tokens (all)")
                .value(0)
                .decimals(0)
                .animated(true)
                .build();

        // --- Layout ---
        var topRow = new HBox(8, donutTile, contextGauge, tokenCountTile, modelTile, statusTile);
        topRow.setAlignment(Pos.CENTER);

        var breakdownLabel = sectionLabel("Context Breakdown");

        var breakdownRow = new HBox(8, systemToolsTile, messagesTokTile, freeSpaceTile, bufferTile);
        breakdownRow.setAlignment(Pos.CENTER);

        var sep = new Separator();
        sep.setStyle("-fx-background-color: #333355;");

        var aggregateLabel = sectionLabel("Aggregate (All Sessions)");

        var aggregateRow = new HBox(8, totalSessionsTile, activeSessionsTile, totalTokensTile);
        aggregateRow.setAlignment(Pos.CENTER);

        getChildren().addAll(pickerRow, topRow, breakdownLabel, breakdownRow,
                sep, aggregateLabel, aggregateRow);
    }

    /** Refresh all dashboard data. Call from the FX thread. */
    public void refresh(Collection<SessionSnapshot> sessions) {
        Platform.runLater(() -> {
            updatePicker(sessions);
            updateAggregate(sessions);
            onSessionSelected();
        });
    }

    private void updatePicker(Collection<SessionSnapshot> sessions) {
        var selected = sessionPicker.getValue();
        var items = sessions.stream()
                .map(this::sessionLabel)
                .sorted()
                .toList();
        sessionPicker.setItems(FXCollections.observableArrayList(items));

        if (selected != null && items.contains(selected)) {
            sessionPicker.setValue(selected);
        } else if (!items.isEmpty()) {
            sessionPicker.setValue(items.getFirst());
        }
    }

    private void onSessionSelected() {
        var label = sessionPicker.getValue();
        if (label == null) {
            clearTiles();
            return;
        }
        var session = sessionManager.getSessions().stream()
                .filter(s -> sessionLabel(s).equals(label))
                .findFirst()
                .orElse(null);
        if (session == null) {
            clearTiles();
            return;
        }
        updateSessionTiles(session);
    }

    private void updateSessionTiles(SessionSnapshot session) {
        var u = session.usage();

        // Donut chart
        systemToolsData.setValue(u.systemToolsTokens());
        messagesData.setValue(u.messagesTokens());
        freeSpaceData.setValue(u.freeSpaceTokens());
        bufferData.setValue(u.bufferTokens());

        // Gauge
        contextGauge.setValue(u.tokenUsagePercent());

        // Token count
        tokenCountTile.setValue(u.currentTokens());
        tokenCountTile.setDescription("of " + formatTokens(u.tokenLimit()));

        // Model
        modelTile.setDescription(session.model());

        // Status
        statusTile.setDescription(session.status().name());
        statusTile.setActiveColor(statusColor(session.status()));
        var location = session.remote() ? " (Remote)" : " (Local)";
        statusTile.setText(session.status().name() + location);

        // Breakdown percentages
        systemToolsTile.setValue(u.systemToolsPercent());
        systemToolsTile.setDescription(formatTokens(u.systemToolsTokens()));

        messagesTokTile.setValue(u.messagesPercent());
        messagesTokTile.setDescription(formatTokens(u.messagesTokens()));

        freeSpaceTile.setValue(u.freeSpacePercent());
        freeSpaceTile.setDescription(formatTokens(u.freeSpaceTokens()));

        bufferTile.setValue(u.bufferPercent());
        bufferTile.setDescription(formatTokens(u.bufferTokens()));
    }

    private void clearTiles() {
        systemToolsData.setValue(0);
        messagesData.setValue(0);
        freeSpaceData.setValue(0);
        bufferData.setValue(0);

        contextGauge.setValue(0);
        tokenCountTile.setValue(0);
        tokenCountTile.setDescription("of 0");
        modelTile.setDescription("—");
        statusTile.setDescription("No session");
        statusTile.setText("");

        systemToolsTile.setValue(0);
        systemToolsTile.setDescription("");
        messagesTokTile.setValue(0);
        messagesTokTile.setDescription("");
        freeSpaceTile.setValue(0);
        freeSpaceTile.setDescription("");
        bufferTile.setValue(0);
        bufferTile.setDescription("");
    }

    private void updateAggregate(Collection<SessionSnapshot> sessions) {
        totalSessionsTile.setValue(sessions.size());

        long active = sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED)
                .count();
        activeSessionsTile.setValue(active);

        int totalTokens = sessions.stream()
                .mapToInt(s -> s.usage().currentTokens())
                .sum();
        totalTokensTile.setValue(totalTokens);
    }

    private String sessionLabel(SessionSnapshot s) {
        var sb = new StringBuilder(s.name());
        if (!"unknown".equals(s.model())) {
            sb.append(" [").append(s.model()).append("]");
        }
        if (s.usage().tokenLimit() > 0) {
            sb.append(" · ").append(formatTokens(s.usage().currentTokens()))
              .append("/").append(formatTokens(s.usage().tokenLimit()))
              .append(" (").append((int) s.usage().tokenUsagePercent()).append("%)");
        }
        return sb.toString();
    }

    private static Color statusColor(SessionStatus status) {
        return switch (status) {
            case IDLE -> Tile.TileColor.GREEN.color;
            case BUSY -> Tile.TileColor.ORANGE.color;
            case ACTIVE -> Tile.TileColor.BLUE.color;
            case ERROR -> Tile.TileColor.RED.color;
            case ARCHIVED -> Color.GRAY;
        };
    }

    private static String formatTokens(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static Label sectionLabel(String text) {
        var label = new Label(text);
        label.setTextFill(Color.web("#aaaacc"));
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        label.setPadding(new Insets(4, 0, 0, 4));
        return label;
    }
}
