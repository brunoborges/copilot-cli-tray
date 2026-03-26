package com.github.copilot.tray.ui;

import com.github.copilot.sdk.ConnectionState;
import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.session.SessionDiskReader;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX settings window launched from the system tray.
 * Directory-first layout: sessions are organized by working directory.
 */
public class SettingsWindow {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsWindow.class);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SessionManager sessionManager;
    private final ConfigStore configStore;
    private final SdkBridge sdkBridge;
    private final Consumer<String> deleteHandler;
    private final Consumer<String> resumeHandler;
    private Stage stage;
    private TabPane tabPane;

    // Sessions tab — directory-first master-detail
    private ToggleGroup locationToggle;
    private ListView<String> directoryList;
    private TableView<SessionSnapshot> sessionTable;
    private VBox detailPane;
    private GridPane detailGrid;
    private UsageTilesPane usageTilesPane;
    private HBox actionBar;
    private Button resumeBtn, attachBtn, renameBtn, deleteBtn;
    private SessionSnapshot selectedSession;
    private String selectedDirectory; // track across refreshes
    private boolean refreshing;
    private List<SessionSnapshot> allSessions = List.of();

    // Preferences tab controls
    private TextField cliPathField;
    private Spinner<Integer> pollIntervalSpinner;
    private Spinner<Integer> warningThresholdSpinner;
    private CheckBox notificationsCheckBox;
    private CheckBox autoStartCheckBox;

    public SettingsWindow(SessionManager sessionManager, ConfigStore configStore,
                          SdkBridge sdkBridge,
                          Consumer<String> deleteHandler, Consumer<String> resumeHandler) {
        this.sessionManager = sessionManager;
        this.configStore = configStore;
        this.sdkBridge = sdkBridge;
        this.deleteHandler = deleteHandler;
        this.resumeHandler = resumeHandler;
    }

    public void show() {
        Platform.runLater(() -> {
            if (stage == null) stage = createStage();
            refreshSessions(sessionManager.getSessions());
            stage.show();
            stage.toFront();
        });
    }

    public void showSessionsTab() {
        Platform.runLater(() -> {
            if (stage == null) stage = createStage();
            refreshSessions(sessionManager.getSessions());
            if (tabPane != null) tabPane.getSelectionModel().select(0);
            stage.show();
            stage.toFront();
        });
    }

    public void onSessionChange(Collection<SessionSnapshot> sessions) {
        if (stage != null && stage.isShowing()) {
            Platform.runLater(() -> refreshSessions(sessions));
        }
    }

    private Stage createStage() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(
                createSessionsTab(),
                createPruneTab(),
                createPreferencesTab(),
                createAboutTab()
        );
        var scene = new Scene(tabPane, 1100, 700);
        var s = new Stage();
        s.setTitle("GitHub Copilot Agentic Tray — Dashboard");
        s.getIcons().add(new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/tray-idle.png")));
        s.setScene(scene);
        s.setOnCloseRequest(e -> { e.consume(); s.hide(); });
        return s;
    }

    // =====================================================================
    // Sessions Tab — Directory-first master-detail
    // =====================================================================

    private Tab createSessionsTab() {
        // --- Top: Local / Remote toggle ---
        var localBtn = new ToggleButton("Local");
        var remoteBtn = new ToggleButton("Remote");
        locationToggle = new ToggleGroup();
        localBtn.setToggleGroup(locationToggle);
        remoteBtn.setToggleGroup(locationToggle);
        localBtn.setSelected(true);
        localBtn.setUserData("local");
        remoteBtn.setUserData("remote");
        // Prevent deselecting both
        locationToggle.selectedToggleProperty().addListener((obs, old, nv) -> {
            if (nv == null) old.setSelected(true);
        });
        locationToggle.selectedToggleProperty().addListener((obs, old, nv) -> {
            if (!refreshing) refreshSessions(sessionManager.getSessions());
        });
        var toggleBar = new HBox(4, localBtn, remoteBtn);
        toggleBar.setPadding(new Insets(6));
        toggleBar.setAlignment(Pos.CENTER_LEFT);

        // --- Left: directory list ---
        directoryList = new ListView<>();
        directoryList.setPrefWidth(280);
        directoryList.setPlaceholder(new Label("No directories found."));
        directoryList.setCellFactory(lv -> new DirectoryCell());
        directoryList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, nv) -> {
                    if (refreshing) return;
                    selectedDirectory = nv;
                    clearDetailPane();
                    onDirectorySelected(nv);
                    syncUsageTiles(List.of());
                });

        var leftBox = new VBox(toggleBar, directoryList);
        VBox.setVgrow(directoryList, Priority.ALWAYS);

        // --- Right top: session table ---
        sessionTable = new TableView<>();
        sessionTable.setPlaceholder(new Label("Select a directory."));
        sessionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        buildSessionTableColumns();
        sessionTable.setRowFactory(tv -> {
            var tableRow = new TableRow<SessionSnapshot>();
            tableRow.setOnMouseClicked(e -> {
                if (tableRow.isEmpty()) {
                    sessionTable.getSelectionModel().clearSelection();
                }
            });
            return tableRow;
        });
        sessionTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<SessionSnapshot>) change -> {
                    if (refreshing) return;
                    var selected = sessionTable.getSelectionModel().getSelectedItems()
                            .stream().filter(java.util.Objects::nonNull).toList();
                    if (selected.size() == 1) {
                        selectedSession = selected.getFirst();
                        showSessionDetail(selectedSession);
                    } else if (selected.size() > 1) {
                        selectedSession = selected.getFirst();
                        showMultiDetail(selected);
                    } else {
                        selectedSession = null;
                        clearDetailPane();
                    }
                    updateActionButtons(selected.size());
                    syncUsageTiles(selected);
                });

        // --- Right bottom: detail + actions ---
        detailGrid = new GridPane();
        detailGrid.setHgap(8);
        detailGrid.setVgap(4);
        detailGrid.setPadding(new Insets(10));
        detailGrid.getColumnConstraints().addAll(
                new ColumnConstraints(90),
                new ColumnConstraints());
        var placeholderLabel = new Label("Select a session to view details.");
        placeholderLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        detailPane = new VBox(placeholderLabel);
        detailPane.setPadding(new Insets(10));

        // Usage tiles pane
        usageTilesPane = new UsageTilesPane();

        var detailScroll = new ScrollPane(detailPane);
        detailScroll.setFitToWidth(true);

        // Detail (left 30%) + Usage (right 70%) side by side
        var detailUsageSplit = new SplitPane(detailScroll, usageTilesPane);
        detailUsageSplit.setDividerPositions(0.30);
        detailUsageSplit.setPrefHeight(250);
        detailUsageSplit.setMinHeight(200);
        detailUsageSplit.setMaxHeight(300);

        resumeBtn = new Button("Resume in Terminal");
        resumeBtn.setDisable(true);
        resumeBtn.setOnAction(e -> { if (selectedSession != null) resumeHandler.accept(selectedSession.id()); });
        attachBtn = new Button("Attach");
        attachBtn.setDisable(true);
        attachBtn.setOnAction(e -> {
            if (selectedSession == null) return;
            var session = selectedSession;
            var logWindow = new SessionEventLogWindow(session.id(), session.name(), () -> {
                sdkBridge.detachSession(session.id());
            });
            logWindow.show();
            sdkBridge.attachSession(session.id(), (sid, event) -> logWindow.onEvent(sid, event))
                    .thenRun(() -> Platform.runLater(() -> logWindow.appendLog("Attached — listening for events")))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> logWindow.appendLog("ERROR: " + ex.getMessage()));
                        return null;
                    });
        });
        renameBtn = new Button("Rename");
        renameBtn.setDisable(true);
        renameBtn.setOnAction(e -> {
            if (selectedSession == null) return;
            var dialog = new TextInputDialog(selectedSession.name());
            dialog.setTitle("Rename Session");
            dialog.setHeaderText(null);
            dialog.setContentText("New name:");
            dialog.showAndWait().ifPresent(newName -> {
                if (!newName.isBlank()) {
                    sessionManager.updateName(selectedSession.id(), newName.trim());
                    SessionDiskReader.updateSummary(selectedSession.id(), newName.trim());
                    sessionManager.fireChange();
                }
            });
        });
        deleteBtn = new Button("Delete");
        deleteBtn.setDisable(true);
        deleteBtn.setStyle("-fx-text-fill: red;");
        var deleteProgress = new ProgressBar(0);
        deleteProgress.setMaxWidth(Double.MAX_VALUE);
        deleteProgress.setVisible(false);
        deleteProgress.managedProperty().bind(deleteProgress.visibleProperty());
        deleteBtn.setOnAction(e -> {
            var selected = List.copyOf(sessionTable.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) return;
            var msg = selected.size() == 1
                    ? "Delete session '" + selected.getFirst().name() + "'?"
                    : "Delete " + selected.size() + " selected sessions?";
            new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO)
                    .showAndWait().ifPresent(bt -> {
                        if (bt == ButtonType.YES) {
                            actionBar.setDisable(true);
                            deleteProgress.setProgress(0);
                            deleteProgress.setVisible(true);
                            int total = selected.size();
                            Thread.ofVirtual().start(() -> {
                                for (int i = 0; i < total; i++) {
                                    var s = selected.get(i);
                                    try {
                                        deleteHandler.accept(s.id());
                                    } catch (Exception ex) {
                                        LOG.warn("Failed to delete session {}: {}", s.id(), ex.getMessage());
                                    }
                                    final double progress = (i + 1.0) / total;
                                    Platform.runLater(() -> deleteProgress.setProgress(progress));
                                }
                                Platform.runLater(() -> {
                                    deleteProgress.setVisible(false);
                                    actionBar.setDisable(false);
                                    sessionTable.getSelectionModel().clearSelection();
                                    updateActionButtons(0);
                                });
                            });
                        }
                    });
        });
        actionBar = new HBox(8, resumeBtn, attachBtn, renameBtn, deleteBtn);
        actionBar.setPadding(new Insets(6));

        var actionPane = new VBox(4, actionBar, deleteProgress);

        // Top: aggregate tiles + session table
        var topPane = new VBox(usageTilesPane.getAggregateRow(), sessionTable);
        VBox.setVgrow(sessionTable, Priority.ALWAYS);

        // Bottom: details + usage side by side, plus actions
        var bottomPane = new VBox(detailUsageSplit, actionPane);

        // Vertical split so user can resize table vs detail area
        var rightSplit = new SplitPane(topPane, bottomPane);
        rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        rightSplit.setDividerPositions(0.5);

        var split = new SplitPane(leftBox, rightSplit);
        split.setDividerPositions(0.28);

        return new Tab("Sessions", split);
    }

    @SuppressWarnings("unchecked")
    private void buildSessionTableColumns() {
        var nameCol = new TableColumn<SessionSnapshot, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        nameCol.setPrefWidth(180);

        var modelCol = new TableColumn<SessionSnapshot, String>("Model");
        modelCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().model()));
        modelCol.setPrefWidth(120);

        var statusCol = new TableColumn<SessionSnapshot, String>("Status");
        statusCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status().name()));
        statusCol.setPrefWidth(85);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("-fx-text-fill: " + statusColor(item) + "; -fx-font-weight: bold;");
            }
        });

        var pctCol = new TableColumn<SessionSnapshot, String>("Usage");
        pctCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.format("%.0f%%", cd.getValue().usage().tokenUsagePercent())));
        pctCol.setPrefWidth(60);

        var userMsgsCol = new TableColumn<SessionSnapshot, String>("User");
        userMsgsCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().usage().userMessagesCount())));
        userMsgsCol.setPrefWidth(45);

        var asstMsgsCol = new TableColumn<SessionSnapshot, String>("Asst");
        asstMsgsCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().usage().assistantMessagesCount())));
        asstMsgsCol.setPrefWidth(45);

        var ctxCol = new TableColumn<SessionSnapshot, String>("Context");
        ctxCol.setCellValueFactory(cd -> {
            var u = cd.getValue().usage();
            return new SimpleStringProperty(formatTokens(u.currentTokens()) + " / " + formatTokens(u.tokenLimit()));
        });
        ctxCol.setPrefWidth(100);

        var createdCol = new TableColumn<SessionSnapshot, String>("Created");
        createdCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().createdAt() != null
                        ? DATE_FMT.format(cd.getValue().createdAt()) : ""));
        createdCol.setPrefWidth(130);

        sessionTable.getColumns().addAll(nameCol, modelCol, statusCol, pctCol, userMsgsCol, asstMsgsCol, ctxCol, createdCol);
    }

    private void onDirectorySelected(String directory) {
        if (directory == null) {
            sessionTable.setItems(FXCollections.emptyObservableList());
            return;
        }
        // Strip the badge suffix to get the real directory path
        var dirPath = stripBadge(directory);
        boolean isRemote = isRemoteSelected();
        var sessions = sessionManager.getSessions().stream()
                .filter(s -> s.remote() == isRemote)
                .filter(s -> dirPath.equals(s.workingDirectory()))
                .sorted(Comparator.comparing(SessionSnapshot::lastActivityAt).reversed())
                .toList();
        sessionTable.setItems(FXCollections.observableArrayList(sessions));
    }

    // =====================================================================
    // Refresh — rebuild directory list, restore selection
    // =====================================================================

    private void refreshSessions(Collection<SessionSnapshot> sessions) {
        if (directoryList == null) return;

        allSessions = List.copyOf(sessions);
        refreshing = true;
        try {
            boolean isRemote = isRemoteSelected();
            String previousDir = selectedDirectory != null ? stripBadge(selectedDirectory) : null;
            // Save ALL selected session IDs for multi-select restore
            var previousSelectedIds = sessionTable.getSelectionModel().getSelectedItems().stream()
                    .map(SessionSnapshot::id)
                    .toList();

            // Group by directory, filtered by local/remote
            var filtered = sessions.stream()
                    .filter(s -> s.remote() == isRemote)
                    .collect(Collectors.groupingBy(SessionSnapshot::workingDirectory,
                            TreeMap::new, Collectors.toList()));

            // Build directory labels with badges
            var dirLabels = new ArrayList<String>();
            for (var entry : filtered.entrySet()) {
                var dir = entry.getKey();
                var list = entry.getValue();
                long activeCount = list.stream()
                        .filter(s -> s.status() != SessionStatus.ARCHIVED && s.status() != SessionStatus.CORRUPTED)
                        .count();
                long corruptedCount = list.stream()
                        .filter(s -> s.status() == SessionStatus.CORRUPTED).count();
                var badge = new StringBuilder();
                badge.append(dir).append("  [").append(list.size()).append("]");
                if (activeCount > 0) badge.append(" ●");
                if (corruptedCount > 0) badge.append(" ⚠");
                dirLabels.add(badge.toString());
            }

            directoryList.setItems(FXCollections.observableArrayList(dirLabels));

            // Restore directory selection
            if (previousDir != null) {
                for (int i = 0; i < dirLabels.size(); i++) {
                    if (previousDir.equals(stripBadge(dirLabels.get(i)))) {
                        directoryList.getSelectionModel().select(i);
                        selectedDirectory = dirLabels.get(i);
                        break;
                    }
                }
            }
            if (directoryList.getSelectionModel().getSelectedItem() == null && !dirLabels.isEmpty()) {
                directoryList.getSelectionModel().selectFirst();
                selectedDirectory = dirLabels.getFirst();
            }

            // Populate session table for selected directory
            onDirectorySelected(directoryList.getSelectionModel().getSelectedItem());

            // Restore session selection (all previously selected IDs)
            if (!previousSelectedIds.isEmpty()) {
                var idSet = new HashSet<>(previousSelectedIds);
                sessionTable.getSelectionModel().clearSelection();
                for (int i = 0; i < sessionTable.getItems().size(); i++) {
                    if (idSet.contains(sessionTable.getItems().get(i).id())) {
                        sessionTable.getSelectionModel().select(i);
                    }
                }
            }

            // Update the selectedSession reference to the new snapshot (same ID, possibly updated data)
            var restoredSelection = sessionTable.getSelectionModel().getSelectedItems();
            if (restoredSelection.size() == 1) {
                selectedSession = restoredSelection.getFirst();
            } else if (restoredSelection.size() > 1) {
                selectedSession = restoredSelection.getFirst();
            } else {
                selectedSession = null;
            }
            updateActionButtons(restoredSelection.size());
        } finally {
            refreshing = false;
        }

        // Detail pane is NOT rebuilt here — it keeps whatever the user is looking at.
        // It only rebuilds on explicit user click (selection change with refreshing=false).

        // Sync usage tiles to current state
        var currentSelected = sessionTable.getSelectionModel().getSelectedItems().stream()
                .filter(java.util.Objects::nonNull).toList();
        syncUsageTiles(currentSelected);
    }

    private boolean isRemoteSelected() {
        if (locationToggle == null || locationToggle.getSelectedToggle() == null) return false;
        return "remote".equals(locationToggle.getSelectedToggle().getUserData());
    }

    private void updateActionButtons(int selectionCount) {
        boolean none = selectionCount == 0;
        boolean multi = selectionCount > 1;
        resumeBtn.setDisable(none || multi);
        attachBtn.setDisable(none || multi);
        renameBtn.setDisable(none || multi);
        deleteBtn.setDisable(none);
    }

    private void syncUsageTiles(List<SessionSnapshot> selected) {
        if (usageTilesPane == null) return;
        String dirPath = selectedDirectory != null ? stripBadge(selectedDirectory) : null;
        boolean isRemote = isRemoteSelected();
        var dirSessions = allSessions.stream()
                .filter(s -> s.remote() == isRemote)
                .filter(s -> dirPath != null && dirPath.equals(s.workingDirectory()))
                .toList();
        usageTilesPane.update(selected, dirSessions);
    }

    private void showSessionDetail(SessionSnapshot session) {
        detailGrid.getChildren().clear();
        int row = 0;
        row = addDetailRow(row, "ID", session.id(), true);
        row = addDetailRow(row, "Name", session.name());
        row = addDetailRow(row, "Directory", session.workingDirectory(), true);
        row = addDetailRow(row, "Model", session.model());
        row = addDetailRow(row, "Status", session.status().name());
        row = addDetailRow(row, "Location", session.remote() ? "Remote" : "Local");
        if (session.createdAt() != null)
            row = addDetailRow(row, "Created", DATE_FMT.format(session.createdAt()));
        if (session.lastActivityAt() != null)
            row = addDetailRow(row, "Last Active", DATE_FMT.format(session.lastActivityAt()));

        // Usage section
        row = addSectionHeader(row, "Usage");
        row = addDetailRow(row, "Tokens", session.usage().currentTokens()
                + " / " + session.usage().tokenLimit()
                + "  (" + (int) session.usage().tokenUsagePercent() + "%)");
        row = addDetailRow(row, "Messages", String.valueOf(session.usage().messagesCount()));

        if (!session.subagents().isEmpty()) {
            row = addSectionHeader(row, "Subagents");
            for (var sub : session.subagents()) {
                row = addDetailRow(row, sub.id(), "[" + sub.status() + "] " + sub.description());
            }
        }
        if (session.pendingPermission()) {
            row = addSectionHeader(row, "");
            addDetailRow(row, "⚠", "Permission request pending");
        }
        detailPane.getChildren().setAll(detailGrid);
    }

    private void showMultiDetail(java.util.List<SessionSnapshot> selected) {
        var label = new Label(selected.size() + " sessions selected");
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        detailPane.getChildren().setAll(label);
    }

    private void clearDetailPane() {
        var placeholder = new Label("Select a session to view details.");
        placeholder.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        detailPane.getChildren().setAll(placeholder);
    }

    private int addDetailRow(int row, String label, String value) {
        return addDetailRow(row, label, value, false);
    }

    private int addDetailRow(int row, String label, String value, boolean showCopy) {
        var keyLabel = new Label(label);
        keyLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #aaa;");
        keyLabel.setMinWidth(Region.USE_PREF_SIZE);

        var valueField = new TextField(value != null ? value : "");
        valueField.setEditable(false);
        valueField.setPrefColumnCount(value != null ? Math.max(value.length(), 1) : 1);
        valueField.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; "
                + "-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0;");

        javafx.scene.Node valueRow;
        if (showCopy) {
            var copyIcon = createCopyIcon();
            var copyBtn = new Button();
            copyBtn.setGraphic(copyIcon);
            copyBtn.setStyle("-fx-padding: 2; -fx-background-color: transparent; -fx-cursor: hand;");
            copyBtn.setTooltip(new Tooltip("Copy to clipboard"));
            copyBtn.setOnAction(e -> {
                var cb = javafx.scene.input.Clipboard.getSystemClipboard();
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(value != null ? value : "");
                cb.setContent(content);
                copyBtn.setStyle("-fx-padding: 2; -fx-background-color: transparent; -fx-cursor: hand; -fx-opacity: 0.5;");
                javafx.animation.PauseTransition flash = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
                flash.setOnFinished(ev -> copyBtn.setStyle("-fx-padding: 2; -fx-background-color: transparent; -fx-cursor: hand;"));
                flash.play();
            });
            valueRow = new HBox(2, valueField, copyBtn);
            ((HBox) valueRow).setAlignment(Pos.CENTER_LEFT);
        } else {
            valueRow = valueField;
        }

        detailGrid.add(keyLabel, 0, row);
        detailGrid.add(valueRow, 1, row);
        return row + 1;
    }

    private static final String COPY_ICON_BACK = "M5.5 1H11a1.5 1.5 0 0 1 1.5 1.5V8A1.5 1.5 0 0 1 11 9.5H5.5A1.5 1.5 0 0 1 4 8V2.5A1.5 1.5 0 0 1 5.5 1z";
    private static final String COPY_ICON_FRONT = "M2.5 4H8A1.5 1.5 0 0 1 9.5 5.5V11A1.5 1.5 0 0 1 8 12.5H2.5A1.5 1.5 0 0 1 1 11V5.5A1.5 1.5 0 0 1 2.5 4z";

    /** Copy icon rendered from SVG path data (matches icons/copy.svg). */
    private static javafx.scene.Group createCopyIcon() {
        var back = new javafx.scene.shape.SVGPath();
        back.setContent(COPY_ICON_BACK);
        back.setFill(Color.TRANSPARENT);
        back.setStroke(Color.gray(0.6));
        back.setStrokeWidth(1.2);

        var front = new javafx.scene.shape.SVGPath();
        front.setContent(COPY_ICON_FRONT);
        front.setFill(Color.TRANSPARENT);
        front.setStroke(Color.gray(0.6));
        front.setStrokeWidth(1.2);

        return new javafx.scene.Group(back, front);
    }

    private int addSectionHeader(int row, String title) {
        var header = new Label(title);
        header.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #ccc; -fx-padding: 6 0 2 0;");
        detailGrid.add(header, 0, row, 2, 1);
        return row + 1;
    }

    /** Strip badge suffix like "  [3] ●" from directory label to get the path. */
    static String stripBadge(String label) {
        if (label == null) return null;
        int idx = label.indexOf("  [");
        return idx >= 0 ? label.substring(0, idx) : label;
    }

    // =====================================================================
    // Directory list cell — styled with icons
    // =====================================================================

    private static class DirectoryCell extends ListCell<String> {
        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null); setGraphic(null); setStyle("");
                return;
            }
            var dirPath = stripBadge(item);
            // Show short path: last 2 components
            var shortPath = shortenPath(dirPath);
            var badge = item.substring(dirPath.length());
            setText("📁 " + shortPath + badge);
            setStyle("-fx-font-size: 12px;");
            setTooltip(new Tooltip(dirPath));
        }
    }

    /** Shorten a path to show ~/relative or last 2 components. */
    static String shortenPath(String path) {
        if (path == null || path.isEmpty()) return "(unknown)";
        var home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        // Show last 2 components
        var parts = path.replace('\\', '/').split("/");
        if (parts.length <= 2) return path;
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private static String statusColor(String statusName) {
        return switch (SessionStatus.valueOf(statusName)) {
            case ACTIVE -> "#4488ff";
            case BUSY -> "#ff8844";
            case IDLE -> "#44cc44";
            case ERROR -> "#ff4444";
            case ARCHIVED -> "#888888";
            case CORRUPTED -> "#cc44cc";
        };
    }

    private static String formatTokens(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    // =====================================================================
    // Prune Tab
    // =====================================================================

    private Tab createPruneTab() {
        var prunePanel = new PrunePanel(new com.github.copilot.tray.session.SessionPruner(), resumeHandler);
        return new Tab("Prune", prunePanel);
    }

    // =====================================================================
    // Preferences Tab
    // =====================================================================

    private Tab createPreferencesTab() {
        var config = configStore.getConfig();
        var grid = new GridPane();
        grid.setPadding(new Insets(15));
        grid.setHgap(10);
        grid.setVgap(10);
        int row = 0;

        grid.add(new Label("Copilot CLI Path:"), 0, row);
        cliPathField = new TextField(config.getCliPath());
        cliPathField.setPromptText("Auto-detect (leave empty)");
        cliPathField.setPrefWidth(350);
        grid.add(cliPathField, 1, row++);

        grid.add(new Label("Poll Interval (seconds):"), 0, row);
        pollIntervalSpinner = new Spinner<>(1, 60, config.getPollIntervalSeconds());
        grid.add(pollIntervalSpinner, 1, row++);

        grid.add(new Label("Context Warning Threshold (%):"), 0, row);
        warningThresholdSpinner = new Spinner<>(50, 100, config.getContextWarningThreshold());
        grid.add(warningThresholdSpinner, 1, row++);

        grid.add(new Label("Enable Notifications:"), 0, row);
        notificationsCheckBox = new CheckBox();
        notificationsCheckBox.setSelected(config.isNotificationsEnabled());
        grid.add(notificationsCheckBox, 1, row++);

        grid.add(new Label("Auto-Start on Login:"), 0, row);
        autoStartCheckBox = new CheckBox();
        autoStartCheckBox.setSelected(config.isAutoStart());
        grid.add(autoStartCheckBox, 1, row++);

        var saveButton = new Button("Save");
        saveButton.setOnAction(e -> savePreferences());
        grid.add(saveButton, 1, row);

        return new Tab("Preferences", grid);
    }

    private void savePreferences() {
        var config = configStore.getConfig();
        config.setCliPath(cliPathField.getText().trim());
        config.setPollIntervalSeconds(pollIntervalSpinner.getValue());
        config.setContextWarningThreshold(warningThresholdSpinner.getValue());
        config.setNotificationsEnabled(notificationsCheckBox.isSelected());
        config.setAutoStart(autoStartCheckBox.isSelected());
        configStore.save();
    }

    // =====================================================================
    // About Tab
    // =====================================================================

    private Tab createAboutTab() {
        var content = new VBox(10);
        content.setPadding(new Insets(15));

        // App info
        content.getChildren().addAll(
                createSectionHeader("Application"),
                new Label("GitHub Copilot Agentic Tray"),
                new Label("Version: 1.0.0-SNAPSHOT"),
                new Label("License: MIT"),
                new Label("A cross-platform system tray application to track and manage"),
                new Label("GitHub Copilot CLI sessions and remote coding agents."),
                new Separator()
        );

        // Runtime info
        content.getChildren().addAll(
                createSectionHeader("Runtime"),
                new Label("JDK: " + System.getProperty("java.version")
                        + " (" + System.getProperty("java.vendor", "") + ")"),
                new Label("JavaFX: " + System.getProperty("javafx.version", "unknown")),
                new Label("OS: " + System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")"),
                new Separator()
        );

        // CLI status section — populated asynchronously
        var cliStatusHeader = createSectionHeader("Copilot CLI");
        var cliConnectionLabel = new Label("Connection: checking…");
        var cliVersionLabel = new Label("Version: checking…");
        var cliProtocolLabel = new Label("Protocol: checking…");
        var cliAuthLabel = new Label("Authenticated: checking…");
        var cliAuthTypeLabel = new Label("Auth Type: —");
        var cliLoginLabel = new Label("Login: —");
        var refreshBtn = new Button("Refresh Status");

        content.getChildren().addAll(
                cliStatusHeader,
                cliConnectionLabel,
                cliVersionLabel,
                cliProtocolLabel,
                cliAuthLabel,
                cliAuthTypeLabel,
                cliLoginLabel,
                refreshBtn,
                new Separator()
        );

        // Fetch CLI status
        Runnable fetchStatus = () -> sdkBridge.fetchCliStatus().thenAccept(status -> Platform.runLater(() -> {
            var stateStr = switch (status.connectionState()) {
                case CONNECTED -> "Connected ✅";
                case CONNECTING -> "Connecting… ⏳";
                case DISCONNECTED -> "Disconnected ❌";
                case ERROR -> "Error ⚠️";
            };
            cliConnectionLabel.setText("Connection: " + stateStr);
            cliVersionLabel.setText("Version: " + (status.version() != null ? status.version() : "—"));
            cliProtocolLabel.setText("Protocol: " + (status.protocolVersion() != null ? status.protocolVersion() : "—"));
            cliAuthLabel.setText("Authenticated: " + (status.authenticated() != null
                    ? (status.authenticated() ? "Yes ✅" : "No ❌") : "—"));
            cliAuthTypeLabel.setText("Auth Type: " + (status.authType() != null ? status.authType() : "—"));
            cliLoginLabel.setText("Login: " + (status.login() != null ? status.login() : "—"));
        })).exceptionally(ex -> {
            Platform.runLater(() -> cliConnectionLabel.setText("Connection: Unable to reach CLI ❌"));
            return null;
        });

        fetchStatus.run();
        refreshBtn.setOnAction(e -> fetchStatus.run());

        // Links
        content.getChildren().addAll(
                createSectionHeader("Links"),
                createHyperlink("GitHub", "https://github.com/brunoborges/copilot-agentic-tray"),
                createHyperlink("Copilot SDK for Java", "https://github.com/github/copilot-sdk-java"),
                createHyperlink("Copilot CLI Docs", "https://docs.github.com/copilot/concepts/agents/about-copilot-cli")
        );

        var scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        return new Tab("About", scrollPane);
    }

    private Label createSectionHeader(String text) {
        var label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        return label;
    }

    private Hyperlink createHyperlink(String text, String url) {
        var link = new Hyperlink(text + ": " + url);
        link.setOnAction(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception ex) { /* ignore */ }
        });
        return link;
    }
}
