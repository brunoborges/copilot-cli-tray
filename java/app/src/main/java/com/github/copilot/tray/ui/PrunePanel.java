package com.github.copilot.tray.ui;

import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionPruner;
import com.github.copilot.tray.session.SessionPruner.PruneCandidate;
import com.github.copilot.tray.session.SessionPruner.PruneCategory;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * UI panel for scanning and pruning low-value sessions.
 * Uses a card-based layout grouped by working directory.
 */
public class PrunePanel extends VBox {

    private final SessionPruner pruner;
    private final Consumer<String> resumeHandler;
    private final ThemeManager themeManager;
    private final SessionManager sessionManager;

    private final CheckBox includeTrivialCb = new CheckBox("Include trivial sessions (≤5 messages)");
    private final Label statusLabel = new Label("Scanning will start automatically…");
    private final Label summaryLabel = new Label("");
    private final Button refreshBtn = new Button("Refresh");
    private final Button pruneBtn = new Button("Delete Selected");
    private final ProgressIndicator spinner = new ProgressIndicator();

    // Card container inside a ScrollPane
    private final VBox cardsContainer = new VBox(12);
    private final ScrollPane scrollPane = new ScrollPane(cardsContainer);

    // Shift-click range selection: track last toggled row index per card table
    private final Map<TableView<PruneCandidate>, Integer> lastClickedIndexMap = new HashMap<>();

    // Selection buttons
    private final Button deselectAllBtn = new Button("Deselect All");
    private final Button selectEmptyBtn = new Button("Select Empty");
    private final Button selectAbandonedBtn = new Button("Select Abandoned");
    private final Button selectTrivialBtn = new Button("Select Trivial");
    private final Button selectCorruptedBtn = new Button("Select Corrupted");
    private final Button selectAllBtn = new Button("Select All");
    private final Button infoBtn = new Button("ℹ Categories");

    // Per-row selection state keyed by sessionId
    private final Map<String, SimpleBooleanProperty> selectionMap = new LinkedHashMap<>();
    private List<PruneCandidate> candidates = List.of();

    // Auto-scan guard
    private boolean hasScanned = false;

    private static final int ROW_HEIGHT = 32;
    private static final int HEADER_HEIGHT = 32;

    public PrunePanel() {
        this(new SessionPruner(), id -> {}, null, null);
    }

    public PrunePanel(SessionPruner pruner, Consumer<String> resumeHandler,
                      ThemeManager themeManager, SessionManager sessionManager) {
        this.pruner = pruner;
        this.resumeHandler = resumeHandler;
        this.themeManager = themeManager;
        this.sessionManager = sessionManager;

        setSpacing(10);
        setPadding(new Insets(12));

        // Scan controls
        includeTrivialCb.setSelected(true);
        refreshBtn.setOnAction(e -> runScan());
        refreshBtn.getStyleClass().add("prune-scan-btn");

        spinner.setVisible(false);
        spinner.setPrefSize(20, 20);

        infoBtn.setOnAction(e -> showCategoryInfo());

        var topRow = new HBox(10, refreshBtn, includeTrivialCb, infoBtn, spinner);
        topRow.setAlignment(Pos.CENTER_LEFT);

        summaryLabel.getStyleClass().add("prune-summary");
        summaryLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));

        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("prune-status");

        // Selection controls
        deselectAllBtn.setOnAction(e -> setSelectionByCategory(null, false));
        selectAllBtn.setOnAction(e -> setSelectionByCategory(null, true));
        selectEmptyBtn.setOnAction(e -> { clearAllSelections(); setSelectionByCategory(PruneCategory.EMPTY, true); });
        selectAbandonedBtn.setOnAction(e -> { clearAllSelections(); setSelectionByCategory(PruneCategory.ABANDONED, true); });
        selectTrivialBtn.setOnAction(e -> { clearAllSelections(); setSelectionByCategory(PruneCategory.TRIVIAL, true); });
        selectCorruptedBtn.setOnAction(e -> { clearAllSelections(); setSelectionByCategory(PruneCategory.CORRUPTED, true); });

        var selectionRow = new HBox(8, deselectAllBtn, selectAllBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                selectEmptyBtn, selectAbandonedBtn, selectTrivialBtn, selectCorruptedBtn);
        selectionRow.setAlignment(Pos.CENTER_LEFT);
        setSelectionControlsDisabled(true);

        // Delete button
        pruneBtn.setOnAction(e -> confirmAndPrune());
        pruneBtn.getStyleClass().add("prune-delete-btn");
        pruneBtn.setDisable(true);

        var bottomRow = new HBox(10, pruneBtn, statusLabel);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        // ScrollPane setup
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        cardsContainer.setPadding(new Insets(4));

        getChildren().addAll(topRow, summaryLabel, selectionRow, scrollPane, bottomRow);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Auto-scan when the panel becomes visible
        visibleProperty().addListener((obs, wasVisible, isNowVisible) -> {
            if (isNowVisible && !hasScanned) {
                hasScanned = true;
                Platform.runLater(this::runScan);
            }
        });
    }

    // --- Card layout ---

    @SuppressWarnings("unchecked")
    private void rebuildCards() {
        cardsContainer.getChildren().clear();
        lastClickedIndexMap.clear();

        if (candidates.isEmpty()) {
            var placeholder = new Label("No pruneable sessions found.");
            placeholder.getStyleClass().add("prune-status");
            placeholder.setPadding(new Insets(20));
            cardsContainer.getChildren().add(placeholder);
            return;
        }

        // Group by directory
        var byDir = new LinkedHashMap<String, List<PruneCandidate>>();
        for (var c : candidates) {
            String dir = (c.workingDirectory() == null || c.workingDirectory().isEmpty())
                    ? "(Other)" : c.workingDirectory();
            byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(c);
        }

        var sortedDirs = new ArrayList<>(byDir.keySet());
        Collections.sort(sortedDirs);

        for (var dir : sortedDirs) {
            var sessions = byDir.get(dir);
            cardsContainer.getChildren().add(buildCard(dir, sessions));
        }
    }

    @SuppressWarnings("unchecked")
    private VBox buildCard(String directory, List<PruneCandidate> sessions) {
        var card = new VBox();
        card.getStyleClass().add("about-card");
        card.setPadding(new Insets(12, 6, 6, 12));
        card.setSpacing(8);

        // Card header: title with count/size on left, Select all/none on right
        long totalSize = sessions.stream().mapToLong(PruneCandidate::diskSizeBytes).sum();
        int count = sessions.size();

        var titleLabel = new Label(directory + "  (" + count + " session" + (count != 1 ? "s" : "")
                + ", " + formatSize(totalSize) + ")");
        titleLabel.getStyleClass().add("about-card-title");

        var selectAllLink = new Hyperlink("Select all");
        selectAllLink.getStyleClass().add("prune-card-link");
        var selectNoneLink = new Hyperlink("Select none");
        selectNoneLink.getStyleClass().add("prune-card-link");

        selectAllLink.setOnAction(e -> sessions.forEach(s -> getSelectionProperty(s.sessionId()).set(true)));
        selectNoneLink.setOnAction(e -> sessions.forEach(s -> getSelectionProperty(s.sessionId()).set(false)));

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(6, titleLabel, spacer, selectAllLink, selectNoneLink);
        header.setAlignment(Pos.CENTER_LEFT);

        // Build the embedded table
        var table = buildCardTable(sessions);

        card.getChildren().addAll(header, table);
        return card;
    }

    @SuppressWarnings("unchecked")
    private TableView<PruneCandidate> buildCardTable(List<PruneCandidate> sessions) {
        var table = new TableView<PruneCandidate>();
        table.getStyleClass().addAll("prune-card-table", "no-header");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        // Checkbox column
        var selectCol = new TableColumn<PruneCandidate, Boolean>("✓");
        selectCol.setCellValueFactory(cd -> getSelectionProperty(cd.getValue().sessionId()));
        selectCol.setCellFactory(col -> new CardCheckBoxCell(table));
        selectCol.setPrefWidth(40);
        selectCol.setMinWidth(40);
        selectCol.setMaxWidth(40);
        selectCol.setSortable(false);
        selectCol.setResizable(false);

        // Name — use SessionManager's resolved name if available, else firstUserMessage
        var nameCol = new TableColumn<PruneCandidate, String>("Name");
        nameCol.setCellValueFactory(cd -> {
            var pc = cd.getValue();
            var name = resolveSessionName(pc);
            return new SimpleStringProperty(name);
        });
        nameCol.setPrefWidth(200);
        nameCol.setMinWidth(100);
        nameCol.setMaxWidth(500);
        nameCol.setSortable(false);
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    setTextOverrun(OverrunStyle.ELLIPSIS);
                    setWrapText(false);
                    var pc = getTableRow().getItem();
                    if (pc != null) {
                        setTooltip(new Tooltip(resolveSessionName(pc)));
                    }
                }
            }
        });

        // Category
        var categoryCol = new TableColumn<PruneCandidate, String>("Category");
        categoryCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().category().name()));
        categoryCol.setPrefWidth(90);
        categoryCol.setMinWidth(80);
        categoryCol.setMaxWidth(100);
        categoryCol.setSortable(false);
        categoryCol.setResizable(false);
        categoryCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(c -> c.startsWith("prune-cat-"));
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    getStyleClass().add(switch (PruneCategory.valueOf(item)) {
                        case EMPTY -> "prune-cat-empty";
                        case ABANDONED -> "prune-cat-abandoned";
                        case TRIVIAL -> "prune-cat-trivial";
                        case CORRUPTED -> "prune-cat-corrupted";
                    });
                }
            }
        });

        // Age
        var ageCol = new TableColumn<PruneCandidate, String>("Age");
        ageCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().age()));
        ageCol.setPrefWidth(65);
        ageCol.setMinWidth(60);
        ageCol.setMaxWidth(80);
        ageCol.setSortable(false);
        ageCol.setResizable(false);

        // Size
        var sizeCol = new TableColumn<PruneCandidate, String>("Size");
        sizeCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().diskSizeFormatted()));
        sizeCol.setPrefWidth(65);
        sizeCol.setMinWidth(60);
        sizeCol.setMaxWidth(80);
        sizeCol.setSortable(false);
        sizeCol.setResizable(false);

        // Combined user/assistant messages
        var msgsCol = new TableColumn<PruneCandidate, String>("Msgs");
        msgsCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().userMessageCount() + "/" + cd.getValue().assistantMessageCount()));
        msgsCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    var pc = getTableRow().getItem();
                    if (pc != null) {
                        setTooltip(new Tooltip("User messages: " + pc.userMessageCount()
                                + "\nAssistant messages: " + pc.assistantMessageCount()));
                    }
                }
            }
        });
        msgsCol.setPrefWidth(50);
        msgsCol.setMinWidth(45);
        msgsCol.setMaxWidth(60);
        msgsCol.setSortable(false);
        msgsCol.setResizable(false);

        // Actions column
        var actionsCol = new TableColumn<PruneCandidate, Void>("⋮");
        actionsCol.setPrefWidth(40);
        actionsCol.setMinWidth(40);
        actionsCol.setMaxWidth(40);
        actionsCol.setSortable(false);
        actionsCol.setResizable(false);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final MenuButton menuBtn = new MenuButton("\u22EE");
            private final MenuItem resumeItem = new MenuItem("Resume");
            private final MenuItem viewEventsItem = new MenuItem("View Events");
            private final MenuItem copyIdItem = new MenuItem("Copy ID");
            private final MenuItem deleteItem = new MenuItem("Delete");
            {
                menuBtn.getStyleClass().add("prune-small-btn");
                menuBtn.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 0 2 0 2;");
                menuBtn.setMaxWidth(34);
                menuBtn.setPrefWidth(34);
                menuBtn.getItems().addAll(resumeItem, viewEventsItem, copyIdItem,
                        new SeparatorMenuItem(), deleteItem);
                resumeItem.setOnAction(e -> {
                    var item = getTableRow().getItem();
                    if (item != null) resumeHandler.accept(item.sessionId());
                });
                viewEventsItem.setOnAction(e -> {
                    var item = getTableRow().getItem();
                    if (item != null) {
                        new SessionEventsViewer(item.sessionId(), item.firstUserMessage(),
                                themeManager, null).show();
                    }
                });
                copyIdItem.setOnAction(e -> {
                    var item = getTableRow().getItem();
                    if (item != null) {
                        var cb = javafx.scene.input.Clipboard.getSystemClipboard();
                        var content = new javafx.scene.input.ClipboardContent();
                        content.putString(item.sessionId());
                        cb.setContent(content);
                    }
                });
                deleteItem.setOnAction(e -> {
                    var item = getTableRow().getItem();
                    if (item != null) deleteSingleSession(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : menuBtn);
            }
        });

        table.getColumns().addAll(selectCol, nameCol, categoryCol, ageCol, sizeCol, msgsCol, actionsCol);
        table.setItems(FXCollections.observableArrayList(sessions));

        // Preferred height fits actual rows (min 1), max height caps at 4 rows
        int rowCount = sessions.size();
        double prefHeight = (Math.max(1, Math.min(rowCount, 4)) * ROW_HEIGHT) + 4;
        double maxHeight = (4 * ROW_HEIGHT) + 4;
        table.setPrefHeight(prefHeight);
        table.setMaxHeight(maxHeight);
        table.setMinHeight(ROW_HEIGHT + 9);

        return table;
    }

    // --- Selection management ---

    private SimpleBooleanProperty getSelectionProperty(String sessionId) {
        return selectionMap.computeIfAbsent(sessionId, id -> {
            var prop = new SimpleBooleanProperty(false);
            prop.addListener((obs, old, val) -> updatePruneButton());
            return prop;
        });
    }

    private void clearAllSelections() {
        selectionMap.values().forEach(p -> p.set(false));
    }

    private void setSelectionByCategory(PruneCategory category, boolean selected) {
        for (var candidate : candidates) {
            if (category == null || candidate.category() == category) {
                getSelectionProperty(candidate.sessionId()).set(selected);
            }
        }
    }

    private List<PruneCandidate> getSelectedCandidates() {
        return candidates.stream()
                .filter(c -> getSelectionProperty(c.sessionId()).get())
                .toList();
    }

    private void setSelectionControlsDisabled(boolean disabled) {
        deselectAllBtn.setDisable(disabled);
        selectAllBtn.setDisable(disabled);
        selectEmptyBtn.setDisable(disabled);
        selectAbandonedBtn.setDisable(disabled);
        selectTrivialBtn.setDisable(disabled);
        selectCorruptedBtn.setDisable(disabled);
    }

    private void updatePruneButton() {
        long selected = getSelectedCandidates().size();
        pruneBtn.setDisable(selected == 0);
        if (selected > 0) {
            pruneBtn.setText("Delete " + selected + " Selected");
        } else {
            pruneBtn.setText("Delete Selected");
        }
    }

    // --- Scan ---

    private void runScan() {
        refreshBtn.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("Scanning sessions…");
        summaryLabel.setText("");
        pruneBtn.setDisable(true);
        setSelectionControlsDisabled(true);
        selectionMap.clear();

        boolean includeTrivial = includeTrivialCb.isSelected();

        CompletableFuture.supplyAsync(() -> pruner.scan(includeTrivial))
                .thenAccept(results -> Platform.runLater(() -> {
                    candidates = results;
                    rebuildCards();
                    refreshBtn.setDisable(false);
                    spinner.setVisible(false);

                    if (candidates.isEmpty()) {
                        statusLabel.setText("No pruneable sessions found.");
                    } else {
                        long totalSize = candidates.stream()
                                .mapToLong(PruneCandidate::diskSizeBytes).sum();
                        long emptyCount = candidates.stream()
                                .filter(c -> c.category() == PruneCategory.EMPTY).count();
                        long abandonedCount = candidates.stream()
                                .filter(c -> c.category() == PruneCategory.ABANDONED).count();
                        long trivialCount = candidates.stream()
                                .filter(c -> c.category() == PruneCategory.TRIVIAL).count();
                        long corruptedCount = candidates.stream()
                                .filter(c -> c.category() == PruneCategory.CORRUPTED).count();

                        var summary = "Found %d sessions — %s total  |  %d empty, %d abandoned, %d trivial"
                                .formatted(candidates.size(), formatSize(totalSize),
                                        emptyCount, abandonedCount, trivialCount);
                        if (corruptedCount > 0) {
                            summary += ", %d corrupted".formatted(corruptedCount);
                        }
                        summaryLabel.setText(summary);
                        statusLabel.setText("Use the selection buttons to pick sessions, then delete.");
                        setSelectionControlsDisabled(false);
                    }
                }));
    }

    // --- Prune ---

    private void deleteSingleSession(PruneCandidate candidate) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete session '" + candidate.firstUserMessage() + "'?\n("
                        + candidate.diskSizeFormatted() + ")",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText(null);
        alert.setTitle("Delete Session");
        alert.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                executePrune(List.of(candidate));
            }
        });
    }

    private void confirmAndPrune() {
        var selected = getSelectedCandidates();
        if (selected.isEmpty()) {
            statusLabel.setText("No sessions selected.");
            return;
        }

        long totalSize = selected.stream().mapToLong(PruneCandidate::diskSizeBytes).sum();

        var alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Prune");
        alert.setHeaderText("Delete " + selected.size() + " sessions?");
        alert.setContentText("This will permanently delete " + selected.size()
                + " session(s) and free approximately " + formatSize(totalSize)
                + " of disk space.\n\nThis action cannot be undone.");
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);

        alert.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                executePrune(selected);
            }
        });
    }

    private void executePrune(List<PruneCandidate> toDelete) {
        pruneBtn.setDisable(true);
        spinner.setVisible(true);
        statusLabel.setText("Deleting sessions…");

        CompletableFuture.supplyAsync(() -> pruner.delete(toDelete))
                .thenAccept(result -> Platform.runLater(() -> {
                    spinner.setVisible(false);

                    // Remove deleted from selection map
                    result.deletedSessionIds().forEach(selectionMap::remove);
                    var remaining = candidates.stream()
                            .filter(c -> !result.deletedSessionIds().contains(c.sessionId()))
                            .toList();
                    candidates = remaining;
                    rebuildCards();

                    var sb = new StringBuilder();
                    sb.append("Deleted ").append(result.deletedCount()).append(" sessions, freed ")
                            .append(result.totalBytesFreedFormatted()).append(".");
                    if (!result.failedSessionIds().isEmpty()) {
                        sb.append("  ⚠ ").append(result.failedSessionIds().size()).append(" failed.");
                    }
                    statusLabel.setText(sb.toString());
                    summaryLabel.setText("");
                    updatePruneButton();

                    if (!remaining.isEmpty()) {
                        setSelectionControlsDisabled(false);
                    }
                }));
    }

    // --- Info dialog ---

    private void showCategoryInfo() {
        var stage = new javafx.stage.Stage();
        stage.setTitle("Prune Categories");

        var cards = new VBox(12);
        cards.setPadding(new Insets(20));

        cards.getChildren().addAll(
                categoryCard("EMPTY", "#f14c4c",
                        "Sessions with no events.jsonl file, or no user messages at all. "
                                + "These were likely created by accident or immediately abandoned."),
                categoryCard("ABANDONED", "#e8a855",
                        "Sessions where the user sent a message but no assistant response "
                                + "was ever generated. The session was started but never completed "
                                + "its first exchange."),
                categoryCard("TRIVIAL", "#7a7a94",
                        "Sessions with ≤5 user messages and some assistant responses. "
                                + "These are very short exchanges that typically hold little value "
                                + "for future reference. This category is optional."),
                categoryCard("CORRUPTED", "#c77dba",
                        "Sessions with unreadable, malformed, or incompatible data. "
                                + "These cannot be resumed and are safe to delete.")
        );

        var note = new Label("Sessions with more than 5 user messages and at least one "
                + "assistant response are considered valuable and never flagged.");
        note.setWrapText(true);
        note.getStyleClass().add("about-value");
        note.setStyle("-fx-font-style: italic; -fx-padding: 4 0 0 0;");
        cards.getChildren().add(note);

        var scene = new javafx.scene.Scene(new ScrollPane(cards) {{
            setFitToWidth(true);
        }}, 480, 420);
        if (themeManager != null) themeManager.register(scene);
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(
                        javafx.scene.input.KeyCode.W,
                        javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                stage::close);
        stage.setScene(scene);
        stage.show();
        stage.toFront();
    }

    private VBox categoryCard(String name, String color, String description) {
        var title = new Label(name);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: " + color + ";");

        var desc = new Label(description);
        desc.setWrapText(true);
        desc.getStyleClass().add("about-value");

        var card = new VBox(4, title, desc);
        card.getStyleClass().add("about-card");
        card.setPadding(new Insets(12));
        card.setStyle("-fx-border-color: " + color + " transparent transparent transparent; "
                + "-fx-border-width: 3 0 0 0;");
        return card;
    }

    // --- Utilities ---

    /** Resolve a display name: prefer SessionManager's name, fall back to firstUserMessage. */
    private String resolveSessionName(PruneCandidate pc) {
        if (sessionManager != null) {
            var snapshot = sessionManager.getSession(pc.sessionId());
            if (snapshot != null) {
                var name = snapshot.name();
                if (name != null && !name.equals(pc.sessionId())) {
                    return name;
                }
            }
        }
        var msg = pc.firstUserMessage();
        return (msg == null || msg.isEmpty()) ? pc.sessionId() : msg;
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_048_576) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024) return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    /** CheckBox cell for card tables with shift-click range support. */
    private class CardCheckBoxCell extends TableCell<PruneCandidate, Boolean> {
        private final CheckBox checkBox = new CheckBox();
        private final TableView<PruneCandidate> ownerTable;
        private String boundSessionId;
        private javafx.beans.value.ChangeListener<Boolean> externalListener;
        private boolean updating;

        CardCheckBoxCell(TableView<PruneCandidate> ownerTable) {
            this.ownerTable = ownerTable;
            setAlignment(Pos.CENTER);

            checkBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                if (boundSessionId == null) return;
                int myIndex = getIndex();
                boolean newValue = !checkBox.isSelected();
                int lastClicked = lastClickedIndexMap.getOrDefault(ownerTable, -1);

                if (event.isShiftDown() && lastClicked >= 0 && lastClicked != myIndex) {
                    event.consume();
                    int lo = Math.min(lastClicked, myIndex);
                    int hi = Math.max(lastClicked, myIndex);
                    var items = ownerTable.getItems();
                    for (int i = lo; i <= hi && i < items.size(); i++) {
                        getSelectionProperty(items.get(i).sessionId()).set(newValue);
                    }
                    lastClickedIndexMap.put(ownerTable, myIndex);
                } else {
                    lastClickedIndexMap.put(ownerTable, myIndex);
                }
            });

            checkBox.selectedProperty().addListener((obs, old, val) -> {
                if (!updating && boundSessionId != null) {
                    getSelectionProperty(boundSessionId).set(val);
                }
            });
        }

        @Override
        protected void updateItem(Boolean item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                unbind();
                return;
            }

            var candidate = getTableRow().getItem();
            var sessionId = candidate.sessionId();

            if (!sessionId.equals(boundSessionId)) {
                unbind();
                boundSessionId = sessionId;
                var prop = getSelectionProperty(sessionId);

                updating = true;
                checkBox.setSelected(prop.get());
                updating = false;

                externalListener = (obs, old, val) -> {
                    updating = true;
                    checkBox.setSelected(val);
                    updating = false;
                };
                prop.addListener(externalListener);
            }
            setGraphic(checkBox);
        }

        private void unbind() {
            if (boundSessionId != null && externalListener != null) {
                var prop = selectionMap.get(boundSessionId);
                if (prop != null) {
                    prop.removeListener(externalListener);
                }
            }
            boundSessionId = null;
            externalListener = null;
        }
    }
}