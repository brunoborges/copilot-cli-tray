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

    private static final Color COLOR_SYSTEM = Color.web("#7b8cde");
    private static final Color COLOR_MSGS   = Color.web("#c0c0c0");
    private static final Color COLOR_FREE   = Color.web("#4a4a6a");
    private static final Color COLOR_BUFFER = Color.web("#8a8aaa");

    // Donut chart data
    private final ChartData systemToolsData;
    private final ChartData messagesData;

    // Detail tiles
    private final Tile donutTile;
    private final Tile contextGauge;
    private final Tile tokenCountTile;

    // Breakdown tiles
    private final Tile systemToolsTile;
    private final Tile messagesTokTile;
    private final Tile freeSpaceTile;
    private final Tile bufferTile;

    // Aggregate tiles
    private final Tile totalSessionsTile;
    private final Tile activeSessionsTile;
    private final Tile totalTokensTile;

    // Aggregate row (exposed for embedding above the table)
    private final HBox aggregateRow;

    public UsageTilesPane() {
        systemToolsData = new ChartData("System/Tools", 0, COLOR_SYSTEM);
        messagesData    = new ChartData("Messages", 0, COLOR_MSGS);

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

        tokenCountTile = TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, TILE_H)
                .description("of 0")
                .value(0).decimals(0).animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();

        systemToolsTile = buildBreakdownTile("System/Tools", COLOR_SYSTEM);
        messagesTokTile = buildBreakdownTile("Messages", COLOR_MSGS);
        freeSpaceTile   = buildBreakdownTile("Free Space", COLOR_FREE);
        bufferTile      = buildBreakdownTile("Buffer", COLOR_BUFFER);

        totalSessionsTile = buildAggregateTile("Total Sessions");
        activeSessionsTile = buildAggregateTile("Active Sessions");
        totalTokensTile = buildAggregateTile("Total Tokens");

        // Layout — each tile gets a Label header + tile in a VBox
        var donutCol = new VBox(4, tileLabel("Tokens Used"), donutTile);
        donutCol.setAlignment(Pos.CENTER_LEFT);

        var contextCol = new VBox(4, tileLabel("Context Used"), contextGauge);
        contextCol.setAlignment(Pos.CENTER_LEFT);

        var tokensCol = new VBox(4, tileLabel("Tokens Used"), tokenCountTile);
        tokensCol.setAlignment(Pos.CENTER_LEFT);

        var detailRow = new HBox(6, donutCol, contextCol, tokensCol);
        detailRow.setAlignment(Pos.CENTER_LEFT);

        var breakdownRow = new HBox(6, systemToolsTile, messagesTokTile, freeSpaceTile, bufferTile);
        breakdownRow.setAlignment(Pos.CENTER_LEFT);

        aggregateRow = new HBox(6, totalSessionsTile, activeSessionsTile, totalTokensTile);
        aggregateRow.setAlignment(Pos.CENTER_LEFT);
        aggregateRow.setPadding(new Insets(4, 0, 4, 0));

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

        tokenCountTile.setValue(u.currentTokens());
        tokenCountTile.setDescription("of " + formatTokens(u.tokenLimit()));

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

        systemToolsData.setValue(selected.stream().mapToInt(s -> s.usage().systemToolsTokens()).sum());
        messagesData.setValue(selected.stream().mapToInt(s -> s.usage().messagesTokens()).sum());

        double avgPct = selected.stream().mapToDouble(s -> s.usage().tokenUsagePercent()).average().orElse(0);
        contextGauge.setValue(avgPct);

        tokenCountTile.setValue(totalTokens);
        tokenCountTile.setDescription(selected.size() + " sessions selected");

        int sysTokSum = selected.stream().mapToInt(s -> s.usage().systemToolsTokens()).sum();
        int msgTokSum = selected.stream().mapToInt(s -> s.usage().messagesTokens()).sum();
        int freeTokSum = selected.stream().mapToInt(s -> s.usage().freeSpaceTokens()).sum();
        int bufTokSum = selected.stream().mapToInt(s -> s.usage().bufferTokens()).sum();
        systemToolsTile.setValue(0);
        systemToolsTile.setDescription(formatTokens(sysTokSum));
        messagesTokTile.setValue(0);
        messagesTokTile.setDescription(formatTokens(msgTokSum));
        freeSpaceTile.setValue(0);
        freeSpaceTile.setDescription(formatTokens(freeTokSum));
        bufferTile.setValue(0);
        bufferTile.setDescription(formatTokens(bufTokSum));
    }

    private void clearDetailTiles() {
        systemToolsData.setValue(0);
        messagesData.setValue(0);
        contextGauge.setValue(0);
        tokenCountTile.setValue(0);
        tokenCountTile.setDescription("of 0");
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
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();
    }

    private Tile buildAggregateTile(String title) {
        return TileBuilder.create()
                .skinType(Tile.SkinType.NUMBER)
                .prefSize(TILE_W, SMALL_H)
                .title(title).value(0).decimals(0)
                .animated(false)
                .textSize(Tile.TextSize.SMALLER)
                .build();
    }

    private HBox buildDonutLegend() {
        var items = new HBox(12,
                legendItem("System/Tools", COLOR_SYSTEM),
                legendItem("Messages", COLOR_MSGS));
        items.setAlignment(Pos.CENTER);
        return items;
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
