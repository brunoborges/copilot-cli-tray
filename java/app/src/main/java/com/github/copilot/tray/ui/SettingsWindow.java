package com.github.copilot.tray.ui;

import com.github.copilot.sdk.ConnectionState;
import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.remote.GhCliRunner;
import com.github.copilot.tray.remote.RemoteSessionPoller;
import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.session.GitInfo;
import com.github.copilot.tray.session.SessionDiskReader;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
    private final RemoteSessionPoller remotePoller;
    private final ThemeManager themeManager;
    private final Consumer<String> deleteHandler;
    private final Consumer<String> resumeHandler;
    private final Consumer<String> newSessionHandler;
    private Stage stage;
    private StackPane contentArea;
    private VBox sideBar;
    private ToggleGroup navGroup;

    // Sessions tab — directory-first master-detail
    private ToggleGroup locationToggle;
    private TreeView<String> directoryList;
    private TableView<SessionSnapshot> sessionTable;
    private VBox detailPane;
    private UsageTilesPane usageTilesPane;
    private HBox actionBar;
    private Button newSessionBtn, resumeBtn, attachBtn, renameBtn, deleteBtn, viewEventsBtn;
    // Remote-specific action buttons
    private Button viewLogsBtn, openBrowserBtn, openPrBtn, openRepoBtn;
    private SessionSnapshot selectedSession;
    private String selectedDirectory; // track across refreshes
    private boolean refreshing;
    private List<SessionSnapshot> allSessions = List.of();

    // Layout containers swapped between local/remote
    private VBox topPane;
    private SplitPane bottomPaneSplit;
    private ScrollPane detailScroll;
    private VBox rightBox;

    // Settings tab controls
    private TextField cliPathField;
    private TextField ghCliPathField;
    private Spinner<Integer> pollIntervalSpinner;
    private Spinner<Integer> warningThresholdSpinner;
    private CheckBox notificationsCheckBox;
    private CheckBox autoStartCheckBox;
    private CheckBox openDashboardOnStartupCheckBox;
    private ComboBox<String> themeCombo;

    public SettingsWindow(SessionManager sessionManager, ConfigStore configStore,
                          SdkBridge sdkBridge, GhCliRunner ghCliRunner,
                          RemoteSessionPoller remotePoller, ThemeManager themeManager,
                          Consumer<String> deleteHandler, Consumer<String> resumeHandler,
                          Consumer<String> newSessionHandler) {
        this.sessionManager = sessionManager;
        this.configStore = configStore;
        this.sdkBridge = sdkBridge;
        this.ghCliRunner = ghCliRunner;
        this.remotePoller = remotePoller;
        this.themeManager = themeManager;
        this.deleteHandler = deleteHandler;
        this.resumeHandler = resumeHandler;
        this.newSessionHandler = newSessionHandler;
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
            // Select sessions nav button
            if (navGroup != null && !navGroup.getToggles().isEmpty()) {
                navGroup.getToggles().getFirst().setSelected(true);
                // Show sessions page
                if (contentArea != null) {
                    var pages = contentArea.getChildren();
                    for (int i = 0; i < pages.size(); i++) pages.get(i).setVisible(i == 0);
                }
            }
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
        // Content pages
        var sessionsPage = createSessionsContent();
        var prunePage = createPruneContent();
        var prefsPage = createSettingsContent();
        var aboutPage = createAboutContent();

        contentArea = new StackPane(sessionsPage, prunePage, prefsPage, aboutPage);
        // Only show the selected page
        prunePage.setVisible(false);
        prefsPage.setVisible(false);
        aboutPage.setVisible(false);

        // Sidebar navigation
        navGroup = new ToggleGroup();
        // SVG path data for nav icons
        // Sessions: list/grid icon
        var sessionsIcon = "M3 3h8v8H3V3zm10 0h8v8h-8V3zM3 13h8v8H3v-8zm10 0h8v8h-8v-8z";
        // Prune: trash icon
        var pruneIcon = "M9 3v1H4v2h1v10a2 2 0 002 2h6a2 2 0 002-2V6h1V4h-5V3H9zM7 8v8h2V8H7zm4 0v8h2V8h-2z";
        // Settings: gear icon (MDI cog – explicit separators for JavaFX)
        var prefsIcon = "M12,15.5A3.5,3.5 0 0,1 8.5,12A3.5,3.5 0 0,1 12,8.5A3.5,3.5 0 0,1 15.5,12A3.5,3.5 0 0,1 12,15.5M19.43,12.97C19.47,12.65 19.5,12.33 19.5,12C19.5,11.67 19.47,11.34 19.43,11L21.54,9.37C21.73,9.22 21.78,8.95 21.66,8.73L19.66,5.27C19.54,5.05 19.27,4.96 19.05,5.05L16.56,6.05C16.04,5.66 15.48,5.32 14.87,5.07L14.5,2.42C14.46,2.18 14.25,2 14,2H10C9.75,2 9.54,2.18 9.5,2.42L9.13,5.07C8.5,5.32 7.96,5.66 7.44,6.05L4.95,5.05C4.73,4.96 4.46,5.05 4.34,5.27L2.34,8.73C2.21,8.95 2.27,9.22 2.46,9.37L4.57,11C4.53,11.34 4.5,11.67 4.5,12C4.5,12.33 4.53,12.65 4.57,12.97L2.46,14.63C2.27,14.78 2.21,15.05 2.34,15.27L4.34,18.73C4.46,18.95 4.73,19.03 4.95,18.95L7.44,17.94C7.96,18.34 8.5,18.68 9.13,18.93L9.5,21.58C9.54,21.82 9.75,22 10,22H14C14.25,22 14.46,21.82 14.5,21.58L14.87,18.93C15.5,18.67 16.04,18.34 16.56,17.94L19.05,18.95C19.27,19.03 19.54,18.95 19.66,18.73L21.66,15.27C21.78,15.05 21.73,14.78 21.54,14.63L19.43,12.97Z";
        // About: info circle icon
        var aboutIcon = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";

        var sessionsBtn = createNavButton("Sessions", sessionsIcon, sessionsPage);
        var pruneBtn = createNavButton("Prune", pruneIcon, prunePage);
        var prefsBtn = createNavButton("Settings", prefsIcon, prefsPage);
        var aboutBtn = createNavButton("About", aboutIcon, aboutPage);

        sessionsBtn.setSelected(true);

        sideBar = new VBox(0, sessionsBtn, pruneBtn);
        sideBar.getStyleClass().add("activity-bar");

        // Settings and About pinned to bottom
        var spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sideBar.getChildren().addAll(spacer, prefsBtn, aboutBtn);

        var root = new HBox(sideBar, contentArea);
        HBox.setHgrow(contentArea, Priority.ALWAYS);

        var scene = new Scene(root, 1100, 800);
        themeManager.register(scene);
        // Cmd+W / Ctrl+W to hide the window
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(
                        javafx.scene.input.KeyCode.W,
                        javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                () -> stage.hide());
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

    private ToggleButton createNavButton(String label, String svgPathData, Node page) {
        var svgIcon = new javafx.scene.shape.SVGPath();
        svgIcon.setContent(svgPathData);
        svgIcon.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        svgIcon.getStyleClass().add("nav-icon");

        var textLabel = new Label(label);
        textLabel.getStyleClass().add("nav-label");

        var content = new VBox(2, svgIcon, textLabel);
        content.setAlignment(Pos.CENTER);
        content.setMouseTransparent(true);

        var btn = new ToggleButton();
        btn.setGraphic(content);
        btn.setToggleGroup(navGroup);
        btn.getStyleClass().add("nav-button");
        btn.setTooltip(new Tooltip(label));
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> {
            // Prevent deselecting
            if (!btn.isSelected()) { btn.setSelected(true); return; }
            for (var child : contentArea.getChildren()) {
                child.setVisible(child == page);
            }
        });
        return btn;
    }

    // =====================================================================
    // Sessions Tab — Directory-first master-detail
    // =====================================================================

    private Node createSessionsContent() {
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
        var toggleBar = new HBox(6, localBtn, remoteBtn);
        toggleBar.setPadding(new Insets(8, 10, 8, 10));
        toggleBar.setAlignment(Pos.CENTER_LEFT);

        // --- Left: directory tree ---
        var root = new TreeItem<String>("root");
        root.setExpanded(true);
        directoryList = new TreeView<>(root);
        directoryList.setShowRoot(false);
        directoryList.setPrefWidth(80);
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
            // Force text color via inline style to bypass JavaFX CSS caching bugs
            tableRow.selectedProperty().addListener((obs, was, is) -> {
                if (is) {
                    tableRow.setStyle("-fx-text-background-color: #ffffff;");
                } else {
                    tableRow.setStyle("-fx-text-background-color: " + getTableTextColor() + ";");
                }
            });
            tableRow.setStyle("-fx-text-background-color: " + getTableTextColor() + ";");
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
        var placeholderLabel = new Label("Select a session to view details.");
        placeholderLabel.getStyleClass().add("placeholder-label");
        detailPane = new VBox(12, placeholderLabel);
        detailPane.setPadding(new Insets(16));

        // Usage tiles pane (local only)
        usageTilesPane = new UsageTilesPane();
        usageTilesPane.setMinWidth(480);
        usageTilesPane.setMaxWidth(480);

        detailScroll = new ScrollPane(detailPane);
        detailScroll.setFitToWidth(true);

        // Detail (left) + Usage (right, fixed width) side by side — local mode
        bottomPaneSplit = new SplitPane(detailScroll, usageTilesPane);
        bottomPaneSplit.setDividerPositions(0.30);
        SplitPane.setResizableWithParent(usageTilesPane, false);

        newSessionBtn = new Button("New Session");
        newSessionBtn.setOnAction(e -> {
            String dir = selectedDirectory != null ? stripBadge(selectedDirectory) : null;
            newSessionHandler.accept(dir);
        });
        resumeBtn = new Button("Resume");
        resumeBtn.setDisable(true);
        resumeBtn.setOnAction(e -> { if (selectedSession != null) resumeHandler.accept(selectedSession.id()); });
        attachBtn = new Button("Attach");
        attachBtn.setDisable(true);
        attachBtn.setOnAction(e -> {
            if (selectedSession == null) return;
            var session = selectedSession;
            var logWindow = new SessionEventLogWindow(session.id(), session.name(), () -> {
                sdkBridge.detachSession(session.id());
            }, themeManager, stage);
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
            dialog.initOwner(stage);
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
            if (!DeleteConfirmDialog.confirm(msg, stage, themeManager)) return;
            actionBar.setDisable(true);
            deleteProgress.setProgress(0);
            deleteProgress.setVisible(true);
            int total = selected.size();
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < total; i++) {
                    var s = selected.get(i);
                    try {
                        deleteHandler.accept(s.id());
                    } catch (Exception ignored) {
                        // already handled inside the handler
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
        });

        // Remote-specific action buttons
        openRepoBtn = new Button("Open Repository");
        openRepoBtn.setDisable(true);
        openRepoBtn.setOnAction(e -> {
            if (selectedSession != null && selectedSession.workingDirectory() != null) {
                try {
                    java.awt.Desktop.getDesktop().browse(
                            java.net.URI.create("https://github.com/" + selectedSession.workingDirectory()));
                } catch (Exception ex) {
                    LOG.warn("Failed to open repository URL: {}", ex.getMessage());
                }
            }
        });
        openPrBtn = new Button("Open Pull Request");
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
        openBrowserBtn = new Button("Open Agent Session");
        openBrowserBtn.setDisable(true);
        openBrowserBtn.setOnAction(e -> {
            if (selectedSession != null && selectedSession.remote())
                ghCliRunner.openInBrowser(selectedSession.id());
        });
        viewLogsBtn = new Button("View Logs");
        viewLogsBtn.setDisable(true);
        viewLogsBtn.setOnAction(e -> {
            if (selectedSession == null || !selectedSession.remote()) return;
            var session = selectedSession;

            var choices = List.of("Latest Log", "Follow in Real Time");
            var dialog = new ChoiceDialog<>(choices.getFirst(), choices);
            dialog.setTitle("View Logs");
            dialog.setHeaderText("How would you like to view logs?");
            dialog.setContentText("Mode:");
            dialog.initOwner(stage);
            themeManager.register(dialog.getDialogPane().getScene());

            dialog.showAndWait().ifPresent(choice -> {
                var logWindow = new SessionEventLogWindow(session.id(), session.name(), () -> {}, themeManager, stage);
                logWindow.show();
                if ("Follow in Real Time".equals(choice)) {
                    try {
                        var process = ghCliRunner.followTaskLogs(session.id(),
                                line -> Platform.runLater(() -> logWindow.appendLog(line)));
                        logWindow.setOnCloseAction(() -> process.destroyForcibly());
                    } catch (Exception ex) {
                        logWindow.appendLog("ERROR: " + ex.getMessage());
                    }
                } else {
                    ghCliRunner.getTaskLogs(session.id())
                            .thenAccept(logs -> Platform.runLater(() -> logWindow.appendLog(logs)));
                }
            });
        });

        viewEventsBtn = new Button("View Events");
        viewEventsBtn.setDisable(true);
        viewEventsBtn.setOnAction(e -> {
            if (selectedSession == null) return;
            var viewer = new SessionEventsViewer(selectedSession.id(), selectedSession.name(),
                    themeManager, stage);
            viewer.show();
        });

        actionBar = new HBox(8, newSessionBtn, resumeBtn, attachBtn, viewEventsBtn, renameBtn, deleteBtn,
                openRepoBtn, openPrBtn, openBrowserBtn, viewLogsBtn);
        actionBar.getStyleClass().add("action-bar");

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
        split.setDividerPositions(0.20);
        SplitPane.setResizableWithParent(leftBox, false);

        return split;
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

        // Update table placeholder based on mode and polling state
        if (remote && remotePoller != null
                && remotePoller.getPollingState() != RemoteSessionPoller.PollingState.READY) {
            var loading = new VBox(8);
            loading.setAlignment(Pos.CENTER);
            var spinner = new ProgressIndicator();
            spinner.setMaxSize(32, 32);
            loading.getChildren().addAll(spinner, new Label("Loading remote sessions…"));
            sessionTable.setPlaceholder(loading);
        } else {
            sessionTable.setPlaceholder(new Label(remote
                    ? "No remote sessions found." : "Select a directory."));
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

            // Update placeholder when remote polling completes
            if (isRemote && remotePoller != null
                    && remotePoller.getPollingState() == RemoteSessionPoller.PollingState.READY) {
                sessionTable.setPlaceholder(new Label("No remote sessions found."));
            }

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

            // Save sort order before replacing items
            var savedSortOrder = new java.util.ArrayList<>(sessionTable.getSortOrder());
            var savedSortTypes = savedSortOrder.stream()
                    .map(TableColumn::getSortType)
                    .toList();

            // Populate session table for selected directory
            var sel = directoryList.getSelectionModel().getSelectedItem();
            onDirectorySelected(sel == null ? null : sel.getValue());

            // Restore sort order
            if (!savedSortOrder.isEmpty()) {
                sessionTable.getSortOrder().setAll(savedSortOrder);
                for (int i = 0; i < savedSortOrder.size(); i++) {
                    savedSortOrder.get(i).setSortType(savedSortTypes.get(i));
                }
                sessionTable.sort();
            }

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
        newSessionBtn.setVisible(!remote);
        newSessionBtn.setManaged(!remote);
        resumeBtn.setVisible(!remote);
        resumeBtn.setManaged(!remote);
        attachBtn.setVisible(!remote);
        attachBtn.setManaged(!remote);
        viewEventsBtn.setVisible(!remote);
        viewEventsBtn.setManaged(!remote);
        renameBtn.setVisible(!remote);
        renameBtn.setManaged(!remote);
        deleteBtn.setVisible(!remote);
        deleteBtn.setManaged(!remote);
        resumeBtn.setDisable(none || multi);
        attachBtn.setDisable(none || multi);
        viewEventsBtn.setDisable(none || multi);
        renameBtn.setDisable(none || multi);
        deleteBtn.setDisable(none);
        // Remote actions
        openRepoBtn.setVisible(remote);
        openRepoBtn.setManaged(remote);
        openPrBtn.setVisible(remote);
        openPrBtn.setManaged(remote);
        openBrowserBtn.setVisible(remote);
        openBrowserBtn.setManaged(remote);
        viewLogsBtn.setVisible(remote);
        viewLogsBtn.setManaged(remote);
        openRepoBtn.setDisable(none || multi);
        openPrBtn.setDisable(none || multi || (selectedSession != null && selectedSession.pullRequestUrl() == null));
        openBrowserBtn.setDisable(none || multi);
        viewLogsBtn.setDisable(none || multi);
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
        var cards = new ArrayList<Node>();

        // --- Session Info card ---
        var infoGrid = aboutGrid();
        int row = 0;
        aboutRow(infoGrid, row++, "ID", detailValueField(session.id(), true));
        aboutRow(infoGrid, row++, "Name", detailValueField(session.name(), false));

        if (session.remote()) {
            addDetailHyperlinkToGrid(infoGrid, row++, "Repository", session.workingDirectory(),
                    "https://github.com/" + session.workingDirectory());
            aboutRow(infoGrid, row++, "State",
                    session.remoteState() != null ? session.remoteState() : session.status().name());
            if (session.user() != null)
                aboutRow(infoGrid, row++, "User", session.user());
        } else {
            aboutRow(infoGrid, row++, "Directory", detailValueField(session.workingDirectory(), true));
            aboutRow(infoGrid, row++, "Model", session.model());
            aboutRow(infoGrid, row++, "Status", session.status().name());
        }

        if (session.createdAt() != null)
            aboutRow(infoGrid, row++, "Created", DATE_FMT.format(session.createdAt()));
        if (session.lastActivityAt() != null)
            aboutRow(infoGrid, row, "Last Active", DATE_FMT.format(session.lastActivityAt()));

        cards.add(aboutCard("Session", infoGrid));

        // --- Repository card (local git repos) ---
        if (!session.remote()) {
            GitInfo.from(session.workingDirectory()).ifPresent(git -> {
                var repoGrid = aboutGrid();
                int r = 0;
                if (git.branch() != null)
                    aboutRow(repoGrid, r++, "Branch", git.branch());
                if (git.remoteUrl() != null)
                    aboutRow(repoGrid, r++, "Remote", detailValueField(git.remoteUrl(), true));
                if (git.githubUrl() != null)
                    addDetailHyperlinkToGrid(repoGrid, r++, "GitHub", git.githubUrl(), git.githubUrl());
                cards.add(aboutCard("Repository", repoGrid));
            });
        }

        // --- Pull Request card (remote only) ---
        if (session.remote() && session.pullRequestNumber() != null) {
            var prGrid = aboutGrid();
            int pr = 0;
            aboutRow(prGrid, pr++, "PR #", String.valueOf(session.pullRequestNumber()));
            if (session.pullRequestTitle() != null)
                aboutRow(prGrid, pr++, "Title", session.pullRequestTitle());
            if (session.pullRequestState() != null)
                aboutRow(prGrid, pr++, "State", session.pullRequestState());
            if (session.pullRequestUrl() != null)
                addDetailHyperlinkToGrid(prGrid, pr, "URL", session.pullRequestUrl(), session.pullRequestUrl());
            cards.add(aboutCard("Pull Request", prGrid));
        }

        // --- Subagents card (local only) ---
        if (!session.remote() && !session.subagents().isEmpty()) {
            var subGrid = aboutGrid();
            int s = 0;
            for (var sub : session.subagents()) {
                aboutRow(subGrid, s++, sub.id(), "[" + sub.status() + "] " + sub.description());
            }
            cards.add(aboutCard("Subagents", subGrid));
        }

        // --- Permission warning ---
        if (!session.remote() && session.pendingPermission()) {
            var warnLabel = new Label("⚠  Permission request pending");
            warnLabel.getStyleClass().add("about-value");
            warnLabel.setStyle("-fx-text-fill: #e8a838;");
            cards.add(warnLabel);
        }

        detailPane.getChildren().setAll(cards);
    }

    private Node detailValueField(String value, boolean showCopy) {
        var valueField = new TextField(value != null ? value : "");
        valueField.setEditable(false);
        valueField.setPrefColumnCount(value != null ? Math.max(value.length(), 1) : 1);
        valueField.getStyleClass().add("detail-value");

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
            var row = new HBox(2, valueField, copyBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }
        return valueField;
    }

    private void addDetailHyperlinkToGrid(GridPane grid, int row, String label, String text, String url) {
        var keyLabel = new Label(label);
        keyLabel.getStyleClass().add("about-key");
        keyLabel.setMinWidth(110);

        var link = new Hyperlink(text);
        link.getStyleClass().add("detail-value");
        link.setOnAction(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception ex) { LOG.warn("Failed to open URL: {}", ex.getMessage()); }
        });

        grid.add(keyLabel, 0, row);
        grid.add(link, 1, row);
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

    private static final String COPY_ICON_BACK = "M5.5 1H11a1.5 1.5 0 0 1 1.5 1.5V8A1.5 1.5 0 0 1 11 9.5H5.5A1.5 1.5 0 0 1 4 8V2.5A1.5 1.5 0 0 1 5.5 1z";
    private static final String COPY_ICON_FRONT = "M2.5 4H8A1.5 1.5 0 0 1 9.5 5.5V11A1.5 1.5 0 0 1 8 12.5H2.5A1.5 1.5 0 0 1 1 11V5.5A1.5 1.5 0 0 1 2.5 4z";

    /** Copy icon rendered from SVG path data (matches icons/copy.svg). */
    private static javafx.scene.Group createCopyIcon() {
        var back = new javafx.scene.shape.SVGPath();
        back.setContent(COPY_ICON_BACK);
        back.setFill(Color.TRANSPARENT);
        back.setStroke(Color.gray(0.65));        back.setStrokeWidth(1.2);

        var front = new javafx.scene.shape.SVGPath();
        front.setContent(COPY_ICON_FRONT);
        front.setFill(Color.TRANSPARENT);
        front.setStroke(Color.gray(0.65));
        front.setStrokeWidth(1.2);

        return new javafx.scene.Group(back, front);
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

    private String getTableTextColor() {
        return "dark".equals(themeManager.getCurrentTheme()) ? "#e0e0e0" : "#1e1e1e";
    }

    // =====================================================================
    // Prune Tab
    // =====================================================================

    private Node createPruneContent() {
        var prunePanel = new PrunePanel(new com.github.copilot.tray.session.SessionPruner(), resumeHandler, themeManager, sessionManager);
        return prunePanel;
    }

    // =====================================================================
    // Settings Tab
    // =====================================================================

    private Node createSettingsContent() {
        var config = configStore.getConfig();

        // --- Appearance card ---
        var appearanceGrid = aboutGrid();
        var themeKey = new Label("Theme");
        themeKey.getStyleClass().add("about-key");
        themeKey.setMinWidth(180);
        themeCombo = new ComboBox<>(FXCollections.observableArrayList("System", "Dark", "Light"));
        themeCombo.setValue(capitalize(config.getTheme()));
        appearanceGrid.add(themeKey, 0, 0);
        appearanceGrid.add(themeCombo, 1, 0);
        var appearanceCard = aboutCard("Appearance", appearanceGrid);

        // --- GitHub Tools card ---
        var cliGrid = aboutGrid();
        var cliPathKey = new Label("GitHub Copilot (path)");
        cliPathKey.getStyleClass().add("about-key");
        cliPathKey.setMinWidth(180);
        cliPathField = new TextField(config.getCliPath());
        cliPathField.setPromptText("Auto-detect (leave empty)");
        cliPathField.setPrefWidth(350);
        cliGrid.add(cliPathKey, 0, 0);
        cliGrid.add(cliPathField, 1, 0);

        var ghCliPathKey = new Label("GitHub CLI (path)");
        ghCliPathKey.getStyleClass().add("about-key");
        ghCliPathKey.setMinWidth(180);
        ghCliPathField = new TextField(config.getGhCliPath());
        ghCliPathField.setPromptText("Auto-detect (leave empty)");
        ghCliPathField.setPrefWidth(350);
        cliGrid.add(ghCliPathKey, 0, 1);
        cliGrid.add(ghCliPathField, 1, 1);

        var cliCard = aboutCard("GitHub Tools", cliGrid);

        // --- Polling card ---
        var pollingGrid = aboutGrid();
        var pollKey = new Label("Poll Interval (seconds)");
        pollKey.getStyleClass().add("about-key");
        pollKey.setMinWidth(180);
        pollIntervalSpinner = new Spinner<>(10, 60, Math.max(10, config.getPollIntervalSeconds()));
        pollingGrid.add(pollKey, 0, 0);
        pollingGrid.add(pollIntervalSpinner, 1, 0);

        var thresholdKey = new Label("Context Warning (%)");
        thresholdKey.getStyleClass().add("about-key");
        thresholdKey.setMinWidth(180);
        warningThresholdSpinner = new Spinner<>(50, 100, config.getContextWarningThreshold());
        pollingGrid.add(thresholdKey, 0, 1);
        pollingGrid.add(warningThresholdSpinner, 1, 1);
        var pollingCard = aboutCard("Monitoring", pollingGrid);

        // --- Behavior card ---
        var behaviorGrid = aboutGrid();
        var notifKey = new Label("Enable Notifications");
        notifKey.getStyleClass().add("about-key");
        notifKey.setMinWidth(180);
        notificationsCheckBox = new CheckBox();
        notificationsCheckBox.setSelected(config.isNotificationsEnabled());
        behaviorGrid.add(notifKey, 0, 0);
        behaviorGrid.add(notificationsCheckBox, 1, 0);

        var autoStartKey = new Label("Auto-Start on Login");
        autoStartKey.getStyleClass().add("about-key");
        autoStartKey.setMinWidth(180);
        autoStartCheckBox = new CheckBox();
        autoStartCheckBox.setSelected(config.isAutoStart());
        behaviorGrid.add(autoStartKey, 0, 1);
        behaviorGrid.add(autoStartCheckBox, 1, 1);

        var dashboardKey = new Label("Open Dashboard on Startup");
        dashboardKey.getStyleClass().add("about-key");
        dashboardKey.setMinWidth(180);
        openDashboardOnStartupCheckBox = new CheckBox();
        openDashboardOnStartupCheckBox.setSelected(config.isOpenDashboardOnStartup());
        behaviorGrid.add(dashboardKey, 0, 2);
        behaviorGrid.add(openDashboardOnStartupCheckBox, 1, 2);
        var behaviorCard = aboutCard("Behavior", behaviorGrid);

        // --- Save button ---
        var saveButton = new Button("Save");
        saveButton.getStyleClass().add("action-button");
        saveButton.setOnAction(e -> saveSettings());

        var content = new VBox(12, appearanceCard, cliCard, pollingCard, behaviorCard, saveButton);
        content.setPadding(new Insets(20));

        var scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private void saveSettings() {
        var config = configStore.getConfig();
        var selectedTheme = themeCombo.getValue().toLowerCase();
        config.setTheme(selectedTheme);
        config.setCliPath(cliPathField.getText().trim());
        config.setGhCliPath(ghCliPathField.getText().trim());
        config.setPollIntervalSeconds(pollIntervalSpinner.getValue());
        config.setContextWarningThreshold(warningThresholdSpinner.getValue());
        config.setNotificationsEnabled(notificationsCheckBox.isSelected());
        config.setAutoStart(autoStartCheckBox.isSelected());
        config.setOpenDashboardOnStartup(openDashboardOnStartupCheckBox.isSelected());
        configStore.save();
        themeManager.setTheme(selectedTheme);

        // Apply gh CLI path to runner
        var ghPath = config.getGhCliPath();
        ghCliRunner.setGhPath(ghPath.isBlank() ? "gh" : ghPath);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // =====================================================================
    // About Tab
    // =====================================================================

    private Node createAboutContent() {
        // Load build info
        var buildProps = new java.util.Properties();
        try (var is = getClass().getResourceAsStream("/build.properties")) {
            if (is != null) buildProps.load(is);
        } catch (Exception ignored) {}
        var appVersion = buildProps.getProperty("app.version", "unknown");
        var buildTime = buildProps.getProperty("build.timestamp", "unknown");
        var gitCommit = buildProps.getProperty("git.commit", "unknown");

        // --- Hero header ---
        var appName = new Label("GitHub Copilot Agentic Tray");
        appName.getStyleClass().add("about-app-name");

        var appDesc = new Label("A cross-platform system tray application to track\nand manage GitHub Copilot CLI sessions and remote coding agents.");
        appDesc.setWrapText(true);
        appDesc.getStyleClass().add("about-description");

        var versionBadge = new Label("v" + appVersion);
        versionBadge.getStyleClass().add("about-version-badge");

        var headerRow = new HBox(10, appName, versionBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        var heroBox = new VBox(6, headerRow, appDesc);
        heroBox.getStyleClass().add("about-card");
        heroBox.setPadding(new Insets(16));

        // --- Build card ---
        var buildGrid = aboutGrid();
        aboutRow(buildGrid, 0, "Version", appVersion);
        aboutRow(buildGrid, 1, "Build", buildTime);
        aboutRow(buildGrid, 2, "Commit", gitCommit);
        aboutRow(buildGrid, 3, "License", "MIT");
        var buildCard = aboutCard("Build Information", buildGrid);

        // --- Runtime card ---
        var runtimeGrid = aboutGrid();
        aboutRow(runtimeGrid, 0, "JDK", System.getProperty("java.version")
                + "  (" + System.getProperty("java.vendor", "") + ")");
        aboutRow(runtimeGrid, 1, "JavaFX", System.getProperty("javafx.version", "unknown"));
        aboutRow(runtimeGrid, 2, "OS", System.getProperty("os.name") + " "
                + System.getProperty("os.version"));
        aboutRow(runtimeGrid, 3, "Architecture", System.getProperty("os.arch"));
        var runtimeCard = aboutCard("Runtime Environment", runtimeGrid);

        // --- CLI status card ---
        var cliGrid = aboutGrid();
        var cliConnectionVal = aboutValueLabel("checking…");
        var cliVersionVal = aboutValueLabel("checking…");
        var cliProtocolVal = aboutValueLabel("checking…");
        var cliAuthVal = aboutValueLabel("checking…");
        var cliAuthTypeVal = aboutValueLabel("—");
        var cliLoginVal = aboutValueLabel("—");

        int r = 0;
        aboutRow(cliGrid, r++, "Connection", cliConnectionVal);
        aboutRow(cliGrid, r++, "Version", cliVersionVal);
        aboutRow(cliGrid, r++, "Protocol", cliProtocolVal);
        aboutRow(cliGrid, r++, "Authenticated", cliAuthVal);
        aboutRow(cliGrid, r++, "Auth Type", cliAuthTypeVal);
        aboutRow(cliGrid, r, "Login", cliLoginVal);
        var cliCard = aboutCard("Copilot CLI Status", cliGrid);

        Runnable fetchStatus = () -> sdkBridge.fetchCliStatus().thenAccept(status -> Platform.runLater(() -> {
            var stateStr = switch (status.connectionState()) {
                case CONNECTED -> "Connected ✅";
                case CONNECTING -> "Connecting… ⏳";
                case DISCONNECTED -> "Disconnected ❌";
                case ERROR -> "Error ⚠️";
            };
            cliConnectionVal.setText(stateStr);
            cliVersionVal.setText(status.version() != null ? status.version() : "—");
            cliProtocolVal.setText(status.protocolVersion() != null ? status.protocolVersion() : "—");
            cliAuthVal.setText(status.authenticated() != null
                    ? (status.authenticated() ? "Yes ✅" : "No ❌") : "—");
            cliAuthTypeVal.setText(status.authType() != null ? status.authType() : "—");
            cliLoginVal.setText(status.login() != null ? status.login() : "—");
        })).exceptionally(ex -> {
            Platform.runLater(() -> cliConnectionVal.setText("Unable to reach CLI ❌"));
            return null;
        });

        // --- Links card ---
        var linksBox = new VBox(6,
                createHyperlink("GitHub Repository", "https://github.com/brunoborges/copilot-agentic-tray"),
                createHyperlink("Copilot SDK for Java", "https://github.com/github/copilot-sdk-java"),
                createHyperlink("Copilot CLI Documentation", "https://docs.github.com/copilot/concepts/agents/about-copilot-cli"));
        var linksCard = aboutCard("Links", linksBox);

        // --- Layout ---
        var content = new VBox(12, heroBox, buildCard, runtimeCard, cliCard, linksCard);
        content.setPadding(new Insets(20));

        var scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);

        scrollPane.visibleProperty().addListener((obs, wasVisible, isNowVisible) -> {
            if (isNowVisible) fetchStatus.run();
        });

        return scrollPane;
    }

    private VBox aboutCard(String title, Node body) {
        var header = new Label(title);
        header.getStyleClass().add("about-card-title");
        var card = new VBox(8, header, body);
        card.getStyleClass().add("about-card");
        card.setPadding(new Insets(14));
        return card;
    }

    private GridPane aboutGrid() {
        var grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        return grid;
    }

    private void aboutRow(GridPane grid, int row, String key, String value) {
        aboutRow(grid, row, key, aboutValueLabel(value));
    }

    private void aboutRow(GridPane grid, int row, String key, Label valueLabel) {
        aboutRow(grid, row, key, (Node) valueLabel);
    }

    private void aboutRow(GridPane grid, int row, String key, Node valueNode) {
        var keyLabel = new Label(key);
        keyLabel.getStyleClass().add("about-key");
        keyLabel.setMinWidth(110);
        grid.add(keyLabel, 0, row);
        grid.add(valueNode, 1, row);
    }

    private Label aboutValueLabel(String text) {
        var label = new Label(text);
        label.getStyleClass().add("about-value");
        return label;
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
