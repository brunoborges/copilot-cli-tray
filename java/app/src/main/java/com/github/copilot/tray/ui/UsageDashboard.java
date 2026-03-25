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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Real-time usage dashboard built with Hansolo TilesFX.
 * Uses the same directory-first layout as the Sessions tab.
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

    // Directory list (mirrors Sessions tab)
    private final ListView<String> directoryList = new ListView<>();
    private String selectedDirectoryPath; // stripped badge, null = no selection

    // Session table (multi-select like Sessions tab)
    private final TableView<SessionSnapshot> sessionTable = new TableView<>();
    // IDs selected before a refresh; used to restore selection afterwards
    private List<String> pendingRestoreIds = List.of();

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

    // Guard to suppress selection listener during programmatic table population
    private boolean populatingTable;
    private boolean refreshing;
    private List<SessionSnapshot> allSessions = List.of();

    public UsageDashboard(SessionManager sessionManager) {
        this.sessionManager = sessionManager;

        setSpacing(8);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #1a1a2e;");

        // --- Directory list (left pane) ---
        directoryList.setPrefWidth(250);
        directoryList.setPlaceholder(new Label("No directories found."));
        directoryList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, nv) -> {
                    if (refreshing) return;
                    selectedDirectoryPath = nv != null ? stripBadge(nv) : null;
                    populateSessionTable();
                });

        // --- Session table (right, top) ---
        buildSessionTable();

        // --- Tiles ---
        systemToolsData = new ChartData("System/Tools", 0, COLOR_SYSTEM);
        messagesData    = new ChartData("Messages", 0, COLOR_MSGS);
        freeSpaceData   = new ChartData("Free Space", 0, COLOR_FREE);
        bufferData      = new ChartData("Buffer", 0, COLOR_BUFFER);

        donutTile = TileBuilder.create()
                .skinType(Tile.SkinType.DONUT_CHART)
                .prefSize(TILE_W + 30, TILE_H + 30)
                .title("Context Window")
                .chartData(systemToolsData, messagesData, freeSpaceData, bufferData)
                .animated(false)
                .build();

        contextGauge = TileBuilder.create()
                .skinType(Tile.SkinType.GAUGE)
                .prefSize(TILE_W, TILE_H)
                .title("Context Used")
                .unit("%")
                .minValue(0).maxValue(100).value(0)
                .thresholdVisible(false).threshold(80)
                .barColor(COLOR_SYSTEM)
                .thresholdColor(Tile.TileColor.RED.color)
                .animated(false)
                .build();

        tokenCountTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, TILE_H)
                .title("Tokens Used")
                .description("of 0")
                .value(0).decimals(0).animated(false)
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
                sectionLabel("Aggregate (Directory)"), aggregateRow);

        var tilesScroll = new ScrollPane(tilesPane);
        tilesScroll.setFitToWidth(true);
        tilesScroll.setStyle("-fx-background: #1a1a2e; -fx-background-color: #1a1a2e;");

        // Right pane: session table + tiles
        var rightPane = new VBox(6, sectionLabel("Sessions"), sessionTable, tilesScroll);
        VBox.setVgrow(tilesScroll, Priority.ALWAYS);

        // Split: directory list (left) | session table + tiles (right)
        var split = new SplitPane(directoryList, rightPane);
        split.setDividerPositions(0.22);
        split.setStyle("-fx-background-color: #1a1a2e;");

        getChildren().add(split);
        VBox.setVgrow(split, Priority.ALWAYS);
    }

    @SuppressWarnings("unchecked")
    private void buildSessionTable() {
        sessionTable.setPrefHeight(180);
        sessionTable.setMaxHeight(220);
        sessionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

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
                        case CORRUPTED -> "#cc44cc";
                    };
                    setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
                }
            }
        });

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

        sessionTable.getColumns().addAll(nameCol, modelCol, statusCol, tokensCol, pctCol, msgsCol);
        sessionTable.setPlaceholder(new Label("Select a directory."));

        sessionTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<SessionSnapshot>) change -> {
                    if (refreshing || populatingTable) return;
                    syncTilesToCurrentState();
                });
    }

    /** Refresh from session data. Safe to call from any thread. */
    public void refresh(Collection<SessionSnapshot> sessions) {
        Platform.runLater(() -> {
            allSessions = List.copyOf(sessions);
            refreshDirectoryList();
        });
    }

    private void refreshDirectoryList() {
        refreshing = true;
        try {
            String previousDir = selectedDirectoryPath;
            // Save ALL selected session IDs for multi-select restore
            pendingRestoreIds = sessionTable.getSelectionModel().getSelectedItems().stream()
                    .map(SessionSnapshot::id)
                    .toList();

            // Group by directory
            var byDir = allSessions.stream()
                    .collect(Collectors.groupingBy(SessionSnapshot::workingDirectory,
                            TreeMap::new, Collectors.toList()));

            // Build labels with badges (same format as Sessions tab)
            var dirLabels = new ArrayList<String>();
            for (var entry : byDir.entrySet()) {
                var dir = entry.getKey();
                var list = entry.getValue();
                long activeCount = list.stream()
                        .filter(s -> s.status() != SessionStatus.ARCHIVED && s.status() != SessionStatus.CORRUPTED)
                        .count();
                var badge = new StringBuilder();
                badge.append(dir).append("  [").append(list.size()).append("]");
                if (activeCount > 0) badge.append(" ●");
                dirLabels.add(badge.toString());
            }

            directoryList.setItems(FXCollections.observableArrayList(dirLabels));

            // Restore directory selection
            boolean dirRestored = false;
            if (previousDir != null) {
                for (int i = 0; i < dirLabels.size(); i++) {
                    if (previousDir.equals(stripBadge(dirLabels.get(i)))) {
                        directoryList.getSelectionModel().select(i);
                        selectedDirectoryPath = previousDir;
                        dirRestored = true;
                        break;
                    }
                }
            }
            if (!dirRestored) {
                selectedDirectoryPath = null;
            }

            // Populate session table for the current directory
            populateSessionTable();

            // Restore session selection (all previously selected IDs)
            if (!pendingRestoreIds.isEmpty()) {
                var idSet = new HashSet<>(pendingRestoreIds);
                sessionTable.getSelectionModel().clearSelection();
                for (int i = 0; i < sessionTable.getItems().size(); i++) {
                    if (idSet.contains(sessionTable.getItems().get(i).id())) {
                        sessionTable.getSelectionModel().select(i);
                    }
                }
            }
            pendingRestoreIds = List.of();
        } finally {
            refreshing = false;
        }

        // Now that refreshing is off, sync tiles to final state
        syncTilesToCurrentState();
    }

    private void populateSessionTable() {
        populatingTable = true;
        try {
            if (selectedDirectoryPath == null) {
                sessionTable.setItems(FXCollections.emptyObservableList());
            } else {
                var sessions = allSessions.stream()
                        .filter(s -> selectedDirectoryPath.equals(s.workingDirectory()))
                        .sorted(Comparator.comparing(SessionSnapshot::lastActivityAt,
                                Comparator.nullsFirst(Comparator.reverseOrder())))
                        .toList();
                sessionTable.setItems(FXCollections.observableArrayList(sessions));
            }
        } finally {
            populatingTable = false;
        }
        syncTilesToCurrentState();
    }

    /**
     * Single source of truth for updating all tiles based on current state.
     * Computes directory sessions from allSessions (authoritative source),
     * not from sessionTable.getItems() which may be in a transient state.
     */
    private void syncTilesToCurrentState() {
        // Aggregate tiles: all sessions for the selected directory
        List<SessionSnapshot> dirSessions;
        if (selectedDirectoryPath == null) {
            dirSessions = List.of();
        } else {
            dirSessions = allSessions.stream()
                    .filter(s -> selectedDirectoryPath.equals(s.workingDirectory()))
                    .toList();
        }
        updateAggregateTiles(dirSessions);

        // Detail tiles: based on table selection (filter nulls — JavaFX quirk)
        var selected = sessionTable.getSelectionModel().getSelectedItems().stream()
                .filter(Objects::nonNull)
                .toList();
        if (selected.size() == 1) {
            updateDetailTiles(selected.getFirst());
        } else if (selected.size() > 1) {
            updateAggregateDetailTiles(selected);
        } else {
            clearDetailTiles();
        }
    }

    private void updateDetailTiles(SessionSnapshot session) {
        var u = session.usage();

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

    private void updateAggregateDetailTiles(List<SessionSnapshot> selected) {
        int totalTokens = selected.stream().mapToInt(s -> s.usage().currentTokens()).sum();
        int maxLimit = selected.stream().mapToInt(s -> s.usage().tokenLimit()).max().orElse(0);
        double avgPct = selected.stream().mapToDouble(s -> s.usage().tokenUsagePercent()).average().orElse(0);

        systemToolsData.setValue(selected.stream().mapToInt(s -> s.usage().systemToolsTokens()).sum());
        messagesData.setValue(selected.stream().mapToInt(s -> s.usage().messagesTokens()).sum());
        freeSpaceData.setValue(selected.stream().mapToInt(s -> s.usage().freeSpaceTokens()).sum());
        bufferData.setValue(selected.stream().mapToInt(s -> s.usage().bufferTokens()).sum());

        contextGauge.setValue(avgPct);

        tokenCountTile.setValue(totalTokens);
        tokenCountTile.setDescription(selected.size() + " sessions selected");

        modelTile.setDescription(selected.stream().map(SessionSnapshot::model).distinct().count() == 1
                ? selected.getFirst().model() : "multiple");

        statusTile.setDescription(selected.size() + " sessions");
        statusTile.setText("");

        systemToolsTile.setValue(0);
        systemToolsTile.setDescription(formatTokens((int) systemToolsData.getValue()));
        messagesTokTile.setValue(0);
        messagesTokTile.setDescription(formatTokens((int) messagesData.getValue()));
        freeSpaceTile.setValue(0);
        freeSpaceTile.setDescription(formatTokens((int) freeSpaceData.getValue()));
        bufferTile.setValue(0);
        bufferTile.setDescription(formatTokens((int) bufferData.getValue()));
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

    private void updateAggregateTiles(Collection<? extends SessionSnapshot> sessions) {
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
                .animated(false).build();
    }

    private Tile buildAggregateTile(String title) {
        return TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title(title).value(0).decimals(0)
                .animated(false).build();
    }

    private static Color statusColor(SessionStatus status) {
        return switch (status) {
            case IDLE -> Tile.TileColor.GREEN.color;
            case BUSY -> Tile.TileColor.ORANGE.color;
            case ACTIVE -> Tile.TileColor.BLUE.color;
            case ERROR -> Tile.TileColor.RED.color;
            case ARCHIVED -> Color.GRAY;
            case CORRUPTED -> Color.web("#cc44cc");
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

    /** Strip badge suffix from directory label to get the raw path. */
    private static String stripBadge(String label) {
        if (label == null) return null;
        int idx = label.indexOf("  [");
        return idx >= 0 ? label.substring(0, idx) : label;
    }
}
