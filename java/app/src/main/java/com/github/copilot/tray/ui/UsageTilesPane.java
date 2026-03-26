package com.github.copilot.tray.ui;

import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import eu.hansolo.tilesfx.Tile;
import eu.hansolo.tilesfx.TileBuilder;
import eu.hansolo.tilesfx.chart.ChartData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Collection;
import java.util.List;

/**
 * Reusable pane showing usage tiles and donut chart for sessions.
 * Embeddable in any tab — call {@link #update} when selection changes.
 */
public class UsageTilesPane extends VBox {

    private static final double TILE_W = 150;
    private static final double TILE_H = 150;
    private static final double SMALL_H = 110;

    private static final Color COLOR_SYSTEM    = Color.web("#7b8cde");
    private static final Color COLOR_MSGS      = Color.web("#c0c0c0");
    private static final Color COLOR_AVAILABLE = Color.web("#4a4a6a");

    private static final Color COLOR_USER_MSGS = Color.web("#5da5da");
    private static final Color COLOR_ASST_MSGS = Color.web("#faa43a");

    // Donut chart data
    private final ChartData systemToolsData;
    private final ChartData messagesData;

    // Messages donut chart data
    private final ChartData userMsgsData;
    private final ChartData asstMsgsData;

    // Detail tiles
    private final Tile donutTile;
    private final Tile contextGauge;
    private final Tile messagesDonutTile;

    // Breakdown tiles
    private final Tile systemToolsTile;
    private final Tile messagesTokTile;
    private final Tile availableTile;

    // Aggregate labels
    private final Label totalSessionsValue;
    private final Label activeSessionsValue;
    private final Label totalTokensValue;

    // Aggregate row (exposed for embedding above the table)
    private final HBox aggregateRow;

    public UsageTilesPane() {
        systemToolsData = new ChartData("System/Tools", 0, COLOR_SYSTEM);
        messagesData    = new ChartData("Messages", 0, COLOR_MSGS);
        userMsgsData    = new ChartData("User", 0, COLOR_USER_MSGS);
        asstMsgsData    = new ChartData("Assistant", 0, COLOR_ASST_MSGS);

        donutTile = TileBuilder.create()
                .skinType(Tile.SkinType.DONUT_CHART)
                .prefSize(TILE_W, TILE_H)
                .chartData(systemToolsData, messagesData)
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();

        contextGauge = TileBuilder.create()
                .skinType(Tile.SkinType.GAUGE)
                .prefSize(TILE_W, TILE_H)
                .unit("%")
                .minValue(0).maxValue(100).value(0)
                .thresholdVisible(false).threshold(80)
                .barColor(COLOR_SYSTEM)
                .thresholdColor(Tile.TileColor.RED.color)
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();

        messagesDonutTile = TileBuilder.create()
                .skinType(Tile.SkinType.DONUT_CHART)
                .prefSize(TILE_W, TILE_H)
                .chartData(userMsgsData, asstMsgsData)
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();

        systemToolsTile = buildBreakdownTile(COLOR_SYSTEM);
        messagesTokTile = buildBreakdownTile(COLOR_MSGS);
        availableTile   = buildBreakdownTile(COLOR_AVAILABLE);

        totalSessionsValue = new Label("0");
        totalSessionsValue.getStyleClass().add("aggregate-value");
        activeSessionsValue = new Label("0");
        activeSessionsValue.getStyleClass().add("aggregate-value");
        totalTokensValue = new Label("0");
        totalTokensValue.getStyleClass().add("aggregate-value");

        // Layout — each tile gets a Label header + tile + legend in a VBox
        var tokensLegend = new HBox(12,
                legendItem("System/Tools", COLOR_SYSTEM),
                legendItem("Messages", COLOR_MSGS));
        tokensLegend.setAlignment(Pos.CENTER);
        var donutCol = new VBox(4, tileLabel("Tokens Used"), donutTile, tokensLegend);
        donutCol.setAlignment(Pos.CENTER_LEFT);

        // Add a spacer below the context gauge matching legend height so tiles align
        var contextSpacer = new Region();
        tokensLegend.heightProperty().addListener((obs, oldH, newH) ->
                contextSpacer.setPrefHeight(newH.doubleValue()));
        // initial measurement after layout
        contextSpacer.setPrefHeight(18); // reasonable default until first layout pass
        var contextCol = new VBox(4, tileLabel("Context Used"), contextGauge, contextSpacer);
        contextCol.setAlignment(Pos.CENTER_LEFT);

        var msgsLegend = new HBox(12,
                legendItem("User", COLOR_USER_MSGS),
                legendItem("Assistant", COLOR_ASST_MSGS));
        msgsLegend.setAlignment(Pos.CENTER);
        var tokensCol = new VBox(4, tileLabel("Messages"), messagesDonutTile, msgsLegend);
        tokensCol.setAlignment(Pos.CENTER_LEFT);

        var detailRow = new HBox(6, donutCol, contextCol, tokensCol);
        detailRow.setAlignment(Pos.CENTER_LEFT);

        var sysToolsCol = new VBox(4, tileLabel("System/Tools"), systemToolsTile);
        sysToolsCol.setAlignment(Pos.CENTER_LEFT);

        var messagesCol = new VBox(4, tileLabel("Messages"), messagesTokTile);
        messagesCol.setAlignment(Pos.CENTER_LEFT);

        var availableCol = new VBox(4, tileLabel("Available"), availableTile);
        availableCol.setAlignment(Pos.CENTER_LEFT);

        var breakdownRow = new HBox(6, sysToolsCol, messagesCol, availableCol);
        breakdownRow.setAlignment(Pos.CENTER_LEFT);

        aggregateRow = new HBox(20,
                aggregateItem("Total Sessions", totalSessionsValue),
                aggregateItem("Active", activeSessionsValue),
                aggregateItem("Total Tokens", totalTokensValue));
        aggregateRow.setAlignment(Pos.CENTER_LEFT);
        aggregateRow.setPadding(new Insets(6, 8, 6, 8));
        aggregateRow.getStyleClass().add("aggregate-row");

        var tilesPane = new VBox(6,
                sectionLabel("Selected Session(s)"), detailRow,
                sectionLabel("Context Breakdown"), breakdownRow);

        getChildren().add(tilesPane);
        getStyleClass().add("usage-tiles-pane");
        setPadding(new Insets(4));
    }

    /** Returns the aggregate row node for embedding outside this pane. */
    public HBox getAggregateRow() {
        return aggregateRow;
    }

    /**
     * Update tiles for the given session selection and directory sessions.
     *
     * @param selected      currently selected sessions (may be empty)
     * @param dirSessions   all sessions in the current directory (for aggregate tiles)
     */
    public void update(List<SessionSnapshot> selected, Collection<SessionSnapshot> dirSessions) {
        updateAggregateTiles(dirSessions);

        if (selected.size() == 1) {
            updateDetailTiles(selected.getFirst());
        } else if (selected.size() > 1) {
            updateAggregateDetailTiles(selected);
        } else {
            clearDetailTiles();
        }
    }

    // --- Detail tile updates ---

    private void updateDetailTiles(SessionSnapshot session) {
        var u = session.usage();

        systemToolsData.setValue(u.systemToolsTokens());
        messagesData.setValue(u.messagesTokens());

        contextGauge.setValue(u.tokenUsagePercent());

        userMsgsData.setValue(u.userMessagesCount());
        asstMsgsData.setValue(u.assistantMessagesCount());

        systemToolsTile.setValue(u.systemToolsTokens());
        systemToolsTile.setDescription(String.format("%.1f%%", u.systemToolsPercent()));
        messagesTokTile.setValue(u.messagesTokens());
        messagesTokTile.setDescription(String.format("%.1f%%", u.messagesPercent()));
        availableTile.setValue(u.availableTokens());
        availableTile.setDescription(String.format("%.1f%%", u.availablePercent()));
    }

    private void updateAggregateDetailTiles(List<SessionSnapshot> selected) {
        systemToolsData.setValue(selected.stream().mapToInt(s -> s.usage().systemToolsTokens()).sum());
        messagesData.setValue(selected.stream().mapToInt(s -> s.usage().messagesTokens()).sum());

        double avgPct = selected.stream().mapToDouble(s -> s.usage().tokenUsagePercent()).average().orElse(0);
        contextGauge.setValue(avgPct);

        userMsgsData.setValue(selected.stream().mapToInt(s -> s.usage().userMessagesCount()).sum());
        asstMsgsData.setValue(selected.stream().mapToInt(s -> s.usage().assistantMessagesCount()).sum());

        int sysTokSum = selected.stream().mapToInt(s -> s.usage().systemToolsTokens()).sum();
        int msgTokSum = selected.stream().mapToInt(s -> s.usage().messagesTokens()).sum();
        int availSum = selected.stream().mapToInt(s -> s.usage().availableTokens()).sum();
        systemToolsTile.setValue(sysTokSum);
        systemToolsTile.setDescription("");
        messagesTokTile.setValue(msgTokSum);
        messagesTokTile.setDescription("");
        availableTile.setValue(availSum);
        availableTile.setDescription("");
    }

    private void clearDetailTiles() {
        systemToolsData.setValue(0);
        messagesData.setValue(0);
        contextGauge.setValue(0);
        userMsgsData.setValue(0);
        asstMsgsData.setValue(0);
        for (var tile : List.of(systemToolsTile, messagesTokTile, availableTile)) {
            tile.setValue(0);
            tile.setDescription("");
        }
    }

    private void updateAggregateTiles(Collection<? extends SessionSnapshot> sessions) {
        totalSessionsValue.setText(String.valueOf(sessions.size()));
        long active = sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED).count();
        activeSessionsValue.setText(String.valueOf(active));
        int tokens = sessions.stream()
                .mapToInt(s -> s.usage().currentTokens()).sum();
        totalTokensValue.setText(formatTokens(tokens));
    }

    // --- Factory helpers ---

    private Tile buildBreakdownTile(Color color) {
        return TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .value(0).decimals(0)
                .valueColor(color)
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();
    }

    private static HBox aggregateItem(String title, Label valueLabel) {
        var titleLbl = new Label(title);
        titleLbl.getStyleClass().add("aggregate-title");
        var box = new HBox(6, titleLbl, valueLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static HBox legendItem(String name, Color color) {
        var swatch = new javafx.scene.shape.Circle(5, color);
        var label = new Label(name);
        label.getStyleClass().add("legend-label");
        var box = new HBox(4, swatch, label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static String formatTokens(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    private static Label sectionLabel(String text) {
        var label = new Label(text);
        label.getStyleClass().add("section-label");
        label.setPadding(new Insets(4, 0, 0, 4));
        return label;
    }

    private static Label tileLabel(String text) {
        var label = new Label(text);
        label.getStyleClass().add("tile-title-label");
        return label;
    }
}
