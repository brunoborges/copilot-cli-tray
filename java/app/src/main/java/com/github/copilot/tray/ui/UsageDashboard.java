package com.github.copilot.tray.ui;

import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import eu.hansolo.tilesfx.Tile;
import eu.hansolo.tilesfx.TileBuilder;
import eu.hansolo.tilesfx.chart.ChartData;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.Collection;
import java.util.List;

/**
 * Real-time usage dashboard built with Hansolo TilesFX.
 * Top: session table. Bottom: TilesFX detail for selected session.
 */
public class UsageDashboard extends VBox {

    private static final double TILE_W = 180;
    private static final double TILE_H = 180;
    private static final double SMALL_H = 130;

    private static final Color COLOR_SYSTEM = Color.web("#7b8cde");
    private static final Color COLOR_MSGS   = Color.web("#c0c0c0");
    private static final Color COLOR_FREE   = Color.web("#4a4a6a");
    private static final Color COLOR_BUFFER = Color.web("#8a8aaa");

    private final SessionManager sessionManager;

    // Session table
    private final TableView<SessionSnapshot> sessionTable = new TableView<>();

    // Donut chart data
    private final ChartData systemToolsData;
    private final ChartData messagesData;
    private final ChartData freeSpaceData;
    private final ChartData bufferData;

    // Detail tiles
    private final Tile donutTile;
    private final Tile contextGauge;
    private final Tile tokenCountTile;
    private final Tile modelTile;
    private final Tile statusTile;

    // Breakdown tiles
    private final Tile systemToolsTile;
    private final Tile messagesTokTile;
    private final Tile freeSpaceTile;
    private final Tile bufferTile;

    // Aggregate tiles
    private final Tile totalSessionsTile;
    private final Tile activeSessionsTile;
    private final Tile totalTokensTile;

    // Currently selected
    private SessionSnapshot selectedSession;

    public UsageDashboard(SessionManager sessionManager) {
        this.sessionManager = sessionManager;

        setSpacing(10);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #1a1a2e;");

        // --- Session table ---
        buildSessionTable();

        // --- Donut chart ---
        systemToolsData = new ChartData("System/Tools", 0, COLOR_SYSTEM);
        messagesData    = new ChartData("Messages", 0, COLOR_MSGS);
        freeSpaceData   = new ChartData("Free Space", 0, COLOR_FREE);
        bufferData      = new ChartData("Buffer", 0, COLOR_BUFFER);

        donutTile = TileBuilder.create()
                .skinType(Tile.SkinType.DONUT_CHART)
                .prefSize(TILE_W + 30, TILE_H + 30)
                .title("Context Window")
                .chartData(systemToolsData, messagesData, freeSpaceData, bufferData)
                .animated(true)
                .build();

        contextGauge = TileBuilder.create()
                .skinType(Tile.SkinType.GAUGE)
                .prefSize(TILE_W, TILE_H)
                .title("Context Used")
                .unit("%")
                .minValue(0).maxValue(100).value(0)
                .thresholdVisible(true).threshold(80)
                .barColor(COLOR_SYSTEM)
                .thresholdColor(Tile.TileColor.RED.color)
                .animated(true)
                .build();

        tokenCountTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, TILE_H)
                .title("Tokens Used")
                .description("of 0")
                .value(0).decimals(0).animated(true)
                .build();

        modelTile = TileBuilder.create()
                .skinType(Tile.SkinType.TEXT)
                .prefSize(TILE_W, TILE_H)
                .title("Model")
                .description("—")
                .textVisible(true)
                .build();

        statusTile = TileBuilder.create()
                .skinType(Tile.SkinType.STATUS)
                .prefSize(TILE_W, TILE_H)
                .title("Status")
                .description("Select a session")
                .build();

        systemToolsTile = buildBreakdownTile("System/Tools", COLOR_SYSTEM);
        messagesTokTile = buildBreakdownTile("Messages", COLOR_MSGS);
        freeSpaceTile   = buildBreakdownTile("Free Space", COLOR_FREE);
        bufferTile      = buildBreakdownTile("Buffer", COLOR_BUFFER);

        totalSessionsTile = buildAggregateTile("Total Sessions");
        activeSessionsTile = buildAggregateTile("Active Sessions");
        totalTokensTile = buildAggregateTile("Total Tokens");

        // --- Layout ---
        var detailRow = new HBox(6, donutTile, contextGauge, tokenCountTile, modelTile, statusTile);
        detailRow.setAlignment(Pos.CENTER);

        var breakdownRow = new HBox(6, systemToolsTile, messagesTokTile, freeSpaceTile, bufferTile);
        breakdownRow.setAlignment(Pos.CENTER);

        var aggregateRow = new HBox(6, totalSessionsTile, activeSessionsTile, totalTokensTile);
        aggregateRow.setAlignment(Pos.CENTER);

        var tilesPane = new VBox(6,
                sectionLabel("Selected Session"), detailRow,
                sectionLabel("Context Breakdown"), breakdownRow,
                new Separator(),
                sectionLabel("Aggregate (All Sessions)"), aggregateRow);

        var tilesScroll = new ScrollPane(tilesPane);
        tilesScroll.setFitToWidth(true);
        tilesScroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");

        getChildren().addAll(sectionLabel("Sessions"), sessionTable, tilesScroll);
        VBox.setVgrow(tilesScroll, Priority.ALWAYS);
    }

    @SuppressWarnings("unchecked")
    private void buildSessionTable() {
        sessionTable.setPrefHeight(160);
        sessionTable.setMaxHeight(200);

        var nameCol = new TableColumn<SessionSnapshot, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        nameCol.setPrefWidth(180);

        var modelCol = new TableColumn<SessionSnapshot, String>("Model");
        modelCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().model()));
        modelCol.setPrefWidth(130);

        var statusCol = new TableColumn<SessionSnapshot, String>("Status");
        statusCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status().name()));
        statusCol.setPrefWidth(80);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setStyle("");
                } else {
                    setText(item);
                    var color = switch (SessionStatus.valueOf(item)) {
                        case ACTIVE -> "#4488ff";
                        case BUSY -> "#ff8844";
                        case IDLE -> "#44cc44";
                        case ERROR -> "#ff4444";
                        case ARCHIVED -> "#888888";
                    };
                    setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
                }
            }
        });

        var locCol = new TableColumn<SessionSnapshot, String>("Location");
        locCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().remote() ? "Remote" : "Local"));
        locCol.setPrefWidth(65);

        var tokensCol = new TableColumn<SessionSnapshot, String>("Tokens");
        tokensCol.setCellValueFactory(cd -> {
            var u = cd.getValue().usage();
            return new SimpleStringProperty(formatTokens(u.currentTokens()) + " / " + formatTokens(u.tokenLimit()));
        });
        tokensCol.setPrefWidth(100);

        var pctCol = new TableColumn<SessionSnapshot, String>("Usage %");
        pctCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.0f%%", cd.getValue().usage().tokenUsagePercent())));
        pctCol.setPrefWidth(65);

        var msgsCol = new TableColumn<SessionSnapshot, String>("Msgs");
        msgsCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().usage().messagesCount())));
        msgsCol.setPrefWidth(50);

        var dirCol = new TableColumn<SessionSnapshot, String>("Directory");
        dirCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().workingDirectory()));
        dirCol.setPrefWidth(180);

        sessionTable.getColumns().addAll(nameCol, modelCol, statusCol, locCol,
                tokensCol, pctCol, msgsCol, dirCol);
        sessionTable.setPlaceholder(new Label("No sessions available."));

        // Selection drives the detail tiles
        sessionTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> {
                    selectedSession = selected;
                    if (selected != null) {
                        updateDetailTiles(selected);
                    } else {
                        clearDetailTiles();
                    }
                });
    }

    /** Refresh from session data. Safe to call from any thread. */
    public void refresh(Collection<SessionSnapshot> sessions) {
        Platform.runLater(() -> {
            var list = List.copyOf(sessions);
            var previousId = selectedSession != null ? selectedSession.id() : null;

            sessionTable.setItems(FXCollections.observableArrayList(list));
            updateAggregateTiles(list);

            // Restore selection
            if (previousId != null) {
                list.stream()
                        .filter(s -> s.id().equals(previousId))
                        .findFirst()
                        .ifPresent(s -> sessionTable.getSelectionModel().select(s));
            }
            // Auto-select first if nothing selected
            if (sessionTable.getSelectionModel().getSelectedItem() == null && !list.isEmpty()) {
                sessionTable.getSelectionModel().selectFirst();
            }
        });
    }

    private void updateDetailTiles(SessionSnapshot session) {
        var u = session.usage();

        // Donut
        systemToolsData.setValue(u.systemToolsTokens());
        messagesData.setValue(u.messagesTokens());
        freeSpaceData.setValue(u.freeSpaceTokens());
        bufferData.setValue(u.bufferTokens());

        contextGauge.setValue(u.tokenUsagePercent());

        tokenCountTile.setValue(u.currentTokens());
        tokenCountTile.setDescription("of " + formatTokens(u.tokenLimit()));

        modelTile.setDescription(session.model());

        statusTile.setDescription(session.status().name());
        statusTile.setActiveColor(statusColor(session.status()));
        statusTile.setText(session.status().name()
                + (session.remote() ? " (Remote)" : " (Local)"));

        systemToolsTile.setValue(u.systemToolsPercent());
        systemToolsTile.setDescription(formatTokens(u.systemToolsTokens()));
        messagesTokTile.setValue(u.messagesPercent());
        messagesTokTile.setDescription(formatTokens(u.messagesTokens()));
        freeSpaceTile.setValue(u.freeSpacePercent());
        freeSpaceTile.setDescription(formatTokens(u.freeSpaceTokens()));
        bufferTile.setValue(u.bufferPercent());
        bufferTile.setDescription(formatTokens(u.bufferTokens()));
    }

    private void clearDetailTiles() {
        systemToolsData.setValue(0);
        messagesData.setValue(0);
        freeSpaceData.setValue(0);
        bufferData.setValue(0);
        contextGauge.setValue(0);
        tokenCountTile.setValue(0);
        tokenCountTile.setDescription("of 0");
        modelTile.setDescription("—");
        statusTile.setDescription("Select a session");
        statusTile.setText("");
        for (var tile : List.of(systemToolsTile, messagesTokTile, freeSpaceTile, bufferTile)) {
            tile.setValue(0);
            tile.setDescription("");
        }
    }

    private void updateAggregateTiles(List<SessionSnapshot> sessions) {
        totalSessionsTile.setValue(sessions.size());
        activeSessionsTile.setValue(sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED).count());
        totalTokensTile.setValue(sessions.stream()
                .mapToInt(s -> s.usage().currentTokens()).sum());
    }

    // --- Factory helpers ---

    private Tile buildBreakdownTile(String title, Color color) {
        return TileBuilder.create()
                .skinType(Tile.SkinType.PERCENTAGE)
                .prefSize(TILE_W, SMALL_H)
                .title(title).unit("%")
                .maxValue(100).value(0)
                .barColor(color)
                .animated(true).build();
    }

    private Tile buildAggregateTile(String title) {
        return TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title(title).value(0).decimals(0)
                .animated(true).build();
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
