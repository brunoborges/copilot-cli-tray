package com.github.copilot.tray.ui;

import com.github.copilot.sdk.ConnectionState;
import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.remote.GhCliRunner;
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
    private final GhCliRunner ghCliRunner;
    private final Consumer<String> deleteHandler;
    private final Consumer<String> resumeHandler;
    private Stage stage;
    private TabPane tabPane;

    // Sessions tab — directory-first master-detail
    private ToggleGroup locationToggle;
    private TreeView<String> directoryList;
    private TableView<SessionSnapshot> sessionTable;
    private VBox detailPane;
    private GridPane detailGrid;
    private UsageTilesPane usageTilesPane;
    private HBox actionBar;
    private Button resumeBtn, attachBtn, renameBtn, deleteBtn;
    // Remote-specific action buttons
    private Button viewLogsBtn, followLogsBtn, openBrowserBtn, openPrBtn;
    private SessionSnapshot selectedSession;
    private String selectedDirectory; // track across refreshes
    private boolean refreshing;
    private List<SessionSnapshot> allSessions = List.of();

    // Layout containers swapped between local/remote
    private VBox topPane;
    private SplitPane bottomPaneSplit;
    private ScrollPane detailScroll;
    private VBox rightBox;

    // Preferences tab controls
    private TextField cliPathField;
    private Spinner<Integer> pollIntervalSpinner;
    private Spinner<Integer> warningThresholdSpinner;
    private CheckBox notificationsCheckBox;
    private CheckBox autoStartCheckBox;
    private CheckBox openDashboardOnStartupCheckBox;

    public SettingsWindow(SessionManager sessionManager, ConfigStore configStore,
                          SdkBridge sdkBridge, GhCliRunner ghCliRunner,
                          Consumer<String> deleteHandler, Consumer<String> resumeHandler) {
        this.sessionManager = sessionManager;
        this.configStore = configStore;
        this.sdkBridge = sdkBridge;
        this.ghCliRunner = ghCliRunner;
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
        var scene = new Scene(tabPane, 1100, 800);
        scene.getStylesheets().add(getClass().getResource("/css/dashboard.css").toExternalForm());
        var s = new Stage();
        s.setTitle("GitHub Copilot Agentic Tray — Dashboard");
        s.setMinHeight(700);
        s.setMinWidth(900);
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
            if (!refreshing) {
                rebuildForMode();
                refreshSessions(sessionManager.getSessions());
            }
        });
        var toggleBar = new HBox(4, localBtn, remoteBtn);
        toggleBar.setPadding(new Insets(6));
        toggleBar.setAlignment(Pos.CENTER_LEFT);

        // --- Left: directory tree ---
        var root = new TreeItem<String>("root");
        root.setExpanded(true);
        directoryList = new TreeView<>(root);
        directoryList.setShowRoot(false);
        directoryList.setPrefWidth(280);
        directoryList.setCellFactory(tv -> new DirectoryTreeCell(directoryList, this::isRemoteSelected));
        directoryList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, nv) -> {
                    if (refreshing) return;
                    selectedDirectory = nv == null ? null : nv.getValue();
                    clearDetailPane();
                    onDirectorySelected(selectedDirectory);
                    syncUsageTiles(List.of());
                });
        // Style: white background, dark text even when selected
        directoryList.getStyleClass().add("directory-tree");

        var leftBox = new VBox(toggleBar, directoryList);
        leftBox.getStyleClass().add("left-panel");
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
        placeholderLabel.getStyleClass().add("placeholder-label");
        detailPane = new VBox(placeholderLabel);
        detailPane.setPadding(new Insets(10));

        // Usage tiles pane (local only)
        usageTilesPane = new UsageTilesPane();

        detailScroll = new ScrollPane(detailPane);
        detailScroll.setFitToWidth(true);

        // Detail (left 30%) + Usage (right 70%) side by side — local mode
        bottomPaneSplit = new SplitPane(detailScroll, usageTilesPane);
        bottomPaneSplit.setDividerPositions(0.30);

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
        deleteBtn.getStyleClass().add("delete-btn");
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

        // Remote-specific action buttons
        viewLogsBtn = new Button("View Logs");
        viewLogsBtn.setDisable(true);
        viewLogsBtn.setOnAction(e -> {
            if (selectedSession == null || !selectedSession.remote()) return;
            var session = selectedSession;
            var logWindow = new SessionEventLogWindow(session.id(), session.name(), () -> {});
            logWindow.show();
            ghCliRunner.getTaskLogs(session.id())
                    .thenAccept(logs -> Platform.runLater(() -> logWindow.appendLog(logs)));
        });
        followLogsBtn = new Button("Follow Logs");
        followLogsBtn.setDisable(true);
        followLogsBtn.setOnAction(e -> {
            if (selectedSession == null || !selectedSession.remote()) return;
            var session = selectedSession;
            var logWindow = new SessionEventLogWindow(session.id(), session.name(), () -> {});
            logWindow.show();
            try {
                var process = ghCliRunner.followTaskLogs(session.id(),
                        line -> Platform.runLater(() -> logWindow.appendLog(line)));
                logWindow.setOnCloseAction(() -> process.destroyForcibly());
            } catch (Exception ex) {
                logWindow.appendLog("ERROR: " + ex.getMessage());
            }
        });
        openBrowserBtn = new Button("Open Agent Session");
        openBrowserBtn.setDisable(true);
        openBrowserBtn.setOnAction(e -> {
            if (selectedSession != null && selectedSession.remote())
                ghCliRunner.openInBrowser(selectedSession.id());
        });
        openPrBtn = new Button("Open PR");
        openPrBtn.setDisable(true);
        openPrBtn.setOnAction(e -> {
            if (selectedSession != null && selectedSession.pullRequestUrl() != null) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(selectedSession.pullRequestUrl()));
                } catch (Exception ex) {
                    LOG.warn("Failed to open PR URL: {}", ex.getMessage());
                }
            }
        });

        actionBar = new HBox(8, resumeBtn, attachBtn, renameBtn, deleteBtn,
                viewLogsBtn, followLogsBtn, openBrowserBtn, openPrBtn);
        actionBar.setPadding(new Insets(6));

        var actionPane = new VBox(4, actionBar, deleteProgress);

        // Top: aggregate tiles + session table (grows to fill)
        topPane = new VBox(usageTilesPane.getAggregateRow(), sessionTable);
        VBox.setVgrow(sessionTable, Priority.ALWAYS);
        topPane.setMinHeight(200);
        VBox.setVgrow(topPane, Priority.ALWAYS);

        // Bottom: fixed-height detail + usage side by side
        bottomPaneSplit.setPrefHeight(375);
        bottomPaneSplit.setMinHeight(375);
        bottomPaneSplit.setMaxHeight(375);

        // Right side: table on top, detail below, action bar pinned at bottom
        rightBox = new VBox(topPane, bottomPaneSplit, actionPane);

        var split = new SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.28);

        return new Tab("Sessions", split);
    }

    @SuppressWarnings("unchecked")
    private void buildSessionTableColumns() {
        buildLocalTableColumns();
    }

    @SuppressWarnings("unchecked")
    private void buildLocalTableColumns() {
        sessionTable.getColumns().clear();

        var nameCol = new TableColumn<SessionSnapshot, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        nameCol.setPrefWidth(180);

        var modelCol = new TableColumn<SessionSnapshot, String>("Model");
        modelCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().model()));
        modelCol.setPrefWidth(120);

        var statusCol = createStatusColumn();

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

        var createdCol = createCreatedColumn();

        sessionTable.getColumns().addAll(nameCol, modelCol, statusCol, pctCol, userMsgsCol, asstMsgsCol, ctxCol, createdCol);
    }

    @SuppressWarnings("unchecked")
    private void buildRemoteTableColumns() {
        sessionTable.getColumns().clear();

        var nameCol = new TableColumn<SessionSnapshot, String>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        nameCol.setPrefWidth(200);

        var repoCol = new TableColumn<SessionSnapshot, String>("Repository");
        repoCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().workingDirectory()));
        repoCol.setPrefWidth(200);

        var statusCol = createStatusColumn();

        var userCol = new TableColumn<SessionSnapshot, String>("User");
        userCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().user() != null ? cd.getValue().user() : ""));
        userCol.setPrefWidth(100);

        var prCol = new TableColumn<SessionSnapshot, String>("PR");
        prCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().pullRequestNumber() != null
                        ? "#" + cd.getValue().pullRequestNumber() : "—"));
        prCol.setPrefWidth(60);

        var prStateCol = new TableColumn<SessionSnapshot, String>("PR State");
        prStateCol.setCellValueFactory(cd -> new SimpleStringProperty(
                cd.getValue().pullRequestState() != null ? cd.getValue().pullRequestState() : ""));
        prStateCol.setPrefWidth(80);

        var createdCol = createCreatedColumn();

        sessionTable.getColumns().addAll(nameCol, repoCol, statusCol, userCol, prCol, prStateCol, createdCol);
    }

    private TableColumn<SessionSnapshot, String> createStatusColumn() {
        var statusCol = new TableColumn<SessionSnapshot, String>("Status");
        statusCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().status().name()));
        statusCol.setPrefWidth(85);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(c -> c.startsWith("status-"));
                if (empty || item == null) { setText(null); return; }
                setText(item);
                getStyleClass().add("status-" + item.toLowerCase());
            }
        });
        return statusCol;
    }

    private TableColumn<SessionSnapshot, String> createCreatedColumn() {
        var createdCol = new TableColumn<SessionSnapshot, String>("Created");
        createdCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().createdAt() != null
                        ? DATE_FMT.format(cd.getValue().createdAt()) : ""));
        createdCol.setPrefWidth(130);
        return createdCol;
    }

    /** Rebuild the right pane layout when switching between Local and Remote modes. */
    private void rebuildForMode() {
        boolean remote = isRemoteSelected();

        // Swap table columns
        if (remote) {
            buildRemoteTableColumns();
        } else {
            buildLocalTableColumns();
        }

        // Swap the bottom section: local shows detail+tiles split, remote shows detail full-width
        var actionPane = rightBox.getChildren().getLast(); // actionPane is always last
        rightBox.getChildren().clear();

        if (remote) {
            // Remote: no aggregate row, detail pane full-width
            topPane.getChildren().setAll(sessionTable);

            detailScroll.setPrefHeight(375);
            detailScroll.setMinHeight(375);
            detailScroll.setMaxHeight(375);

            rightBox.getChildren().addAll(topPane, detailScroll, actionPane);
        } else {
            // Local: aggregate row + table on top, detail+tiles split on bottom
            topPane.getChildren().setAll(usageTilesPane.getAggregateRow(), sessionTable);

            detailScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
            detailScroll.setMinHeight(Region.USE_COMPUTED_SIZE);
            detailScroll.setMaxHeight(Double.MAX_VALUE);

            bottomPaneSplit.setPrefHeight(375);
            bottomPaneSplit.setMinHeight(375);
            bottomPaneSplit.setMaxHeight(375);

            rightBox.getChildren().addAll(topPane, bottomPaneSplit, actionPane);
        }

        VBox.setVgrow(topPane, Priority.ALWAYS);
        clearDetailPane();
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

            var root = directoryList.getRoot();
            root.getChildren().clear();
            for (var label : dirLabels) {
                root.getChildren().add(new TreeItem<>(label));
            }

            // Restore directory selection
            if (previousDir != null) {
                for (int i = 0; i < dirLabels.size(); i++) {
                    if (previousDir.equals(stripBadge(dirLabels.get(i)))) {
                        directoryList.getSelectionModel().select(root.getChildren().get(i));
                        selectedDirectory = dirLabels.get(i);
                        break;
                    }
                }
            }
            if (directoryList.getSelectionModel().getSelectedItem() == null && !dirLabels.isEmpty()) {
                directoryList.getSelectionModel().select(root.getChildren().getFirst());
                selectedDirectory = dirLabels.getFirst();
            }

            // Populate session table for selected directory
            var sel = directoryList.getSelectionModel().getSelectedItem();
            onDirectorySelected(sel == null ? null : sel.getValue());

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
        boolean remote = isRemoteSelected();
        // Local actions
        resumeBtn.setVisible(!remote);
        resumeBtn.setManaged(!remote);
        attachBtn.setVisible(!remote);
        attachBtn.setManaged(!remote);
        renameBtn.setVisible(!remote);
        renameBtn.setManaged(!remote);
        deleteBtn.setVisible(!remote);
        deleteBtn.setManaged(!remote);
        resumeBtn.setDisable(none || multi);
        attachBtn.setDisable(none || multi);
        renameBtn.setDisable(none || multi);
        deleteBtn.setDisable(none);
        // Remote actions
        viewLogsBtn.setVisible(remote);
        viewLogsBtn.setManaged(remote);
        followLogsBtn.setVisible(remote);
        followLogsBtn.setManaged(remote);
        openBrowserBtn.setVisible(remote);
        openBrowserBtn.setManaged(remote);
        openPrBtn.setVisible(remote);
        openPrBtn.setManaged(remote);
        viewLogsBtn.setDisable(none || multi);
        followLogsBtn.setDisable(none || multi);
        openBrowserBtn.setDisable(none || multi);
        openPrBtn.setDisable(none || multi || (selectedSession != null && selectedSession.pullRequestUrl() == null));
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

        if (session.remote()) {
            row = addDetailHyperlink(row, "Repository", session.workingDirectory(),
                    "https://github.com/" + session.workingDirectory());
            row = addDetailRow(row, "State",
                    session.remoteState() != null ? session.remoteState() : session.status().name());
            if (session.user() != null)
                row = addDetailRow(row, "User", session.user());
            if (session.pullRequestNumber() != null) {
                row = addSectionHeader(row, "Pull Request");
                row = addDetailRow(row, "PR #", String.valueOf(session.pullRequestNumber()));
                if (session.pullRequestTitle() != null)
                    row = addDetailRow(row, "Title", session.pullRequestTitle());
                if (session.pullRequestState() != null)
                    row = addDetailRow(row, "PR State", session.pullRequestState());
                if (session.pullRequestUrl() != null)
                    row = addDetailRow(row, "URL", session.pullRequestUrl(), true);
            }
        } else {
            row = addDetailRow(row, "Directory", session.workingDirectory(), true);
            row = addDetailRow(row, "Model", session.model());
            row = addDetailRow(row, "Status", session.status().name());
            row = addDetailRow(row, "Location", "Local");
        }

        if (session.createdAt() != null)
            row = addDetailRow(row, "Created", DATE_FMT.format(session.createdAt()));
        if (session.lastActivityAt() != null)
            row = addDetailRow(row, "Last Active", DATE_FMT.format(session.lastActivityAt()));

        if (!session.remote()) {
            // Usage section (local only — remote has no token data)
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
        }
        detailPane.getChildren().setAll(detailGrid);
    }

    private void showMultiDetail(java.util.List<SessionSnapshot> selected) {
        var label = new Label(selected.size() + " sessions selected");
        label.getStyleClass().add("placeholder-label");
        detailPane.getChildren().setAll(label);
    }

    private void clearDetailPane() {
        var placeholder = new Label("Select a session to view details.");
        placeholder.getStyleClass().add("placeholder-label");
        detailPane.getChildren().setAll(placeholder);
    }

    private int addDetailRow(int row, String label, String value) {
        return addDetailRow(row, label, value, false);
    }

    private int addDetailHyperlink(int row, String label, String text, String url) {
        var keyLabel = new Label(label);
        keyLabel.getStyleClass().add("detail-key");
        keyLabel.setMinWidth(Region.USE_PREF_SIZE);

        var link = new Hyperlink(text);
        link.getStyleClass().add("detail-value");
        link.setOnAction(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception ex) { LOG.warn("Failed to open URL: {}", ex.getMessage()); }
        });

        detailGrid.add(keyLabel, 0, row);
        detailGrid.add(link, 1, row);
        return row + 1;
    }

    private int addDetailRow(int row, String label, String value, boolean showCopy) {
        var keyLabel = new Label(label);
        keyLabel.getStyleClass().add("detail-key");
        keyLabel.setMinWidth(Region.USE_PREF_SIZE);

        var valueField = new TextField(value != null ? value : "");
        valueField.setEditable(false);
        valueField.setPrefColumnCount(value != null ? Math.max(value.length(), 1) : 1);
        valueField.getStyleClass().add("detail-value");

        javafx.scene.Node valueRow;
        if (showCopy) {
            var copyIcon = createCopyIcon();
            var copyBtn = new Button();
            copyBtn.setGraphic(copyIcon);
            copyBtn.getStyleClass().add("copy-btn");
            copyBtn.setTooltip(new Tooltip("Copy to clipboard"));
            copyBtn.setOnAction(e -> {
                var cb = javafx.scene.input.Clipboard.getSystemClipboard();
                var content = new javafx.scene.input.ClipboardContent();
                content.putString(value != null ? value : "");
                cb.setContent(content);
                copyBtn.setOpacity(0.5);
                javafx.animation.PauseTransition flash = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
                flash.setOnFinished(ev -> copyBtn.setOpacity(1.0));
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
        header.getStyleClass().add("detail-header");
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

    private static class DirectoryTreeCell extends TreeCell<String> {
        private static final String FOLDER_CLOSED = "📁";
        private static final String FOLDER_OPEN   = "📂";
        private static final String REPO_CLOSED   = "📦";
        private static final String REPO_OPEN     = "📂";
        private final TreeView<String> tree;
        private final java.util.function.BooleanSupplier remoteCheck;

        DirectoryTreeCell(TreeView<String> tree, java.util.function.BooleanSupplier remoteCheck) {
            this.tree = tree;
            this.remoteCheck = remoteCheck;
        }

        @Override protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null); setGraphic(null);
                return;
            }
            var dirPath = stripBadge(item);
            boolean isRemote = remoteCheck.getAsBoolean();
            var shortPath = isRemote ? dirPath : shortenPath(dirPath);
            var badge = item.substring(dirPath.length());
            boolean selected = getTreeItem() != null
                    && getTreeItem() == tree.getSelectionModel().getSelectedItem();
            String icon;
            if (isRemote) {
                icon = selected ? REPO_OPEN : REPO_CLOSED;
            } else {
                icon = selected ? FOLDER_OPEN : FOLDER_CLOSED;
            }
            setText(icon + " " + shortPath + badge);
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

        grid.add(new Label("Open Dashboard on Startup:"), 0, row);
        openDashboardOnStartupCheckBox = new CheckBox();
        openDashboardOnStartupCheckBox.setSelected(config.isOpenDashboardOnStartup());
        grid.add(openDashboardOnStartupCheckBox, 1, row++);

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
        config.setOpenDashboardOnStartup(openDashboardOnStartupCheckBox.isSelected());
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
        label.getStyleClass().add("about-heading");
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
