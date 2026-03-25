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
    private TextArea detailLabel;
    private HBox actionBar;
    private Button resumeBtn, renameBtn, deleteBtn;
    private SessionSnapshot selectedSession;
    private String selectedDirectory; // track across refreshes
    private boolean refreshing;

    // Usage dashboard
    private UsageDashboard usageDashboard;

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
            Platform.runLater(() -> {
                refreshSessions(sessions);
                if (usageDashboard != null) usageDashboard.refresh(sessions);
            });
        }
    }

    private Stage createStage() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getTabs().addAll(
                createSessionsTab(),
                createUsageTab(),
                createPruneTab(),
                createPreferencesTab(),
                createAboutTab()
        );
        var scene = new Scene(tabPane, 1100, 700);
        var s = new Stage();
        s.setTitle("GitHub Copilot Agentic Tray — Dashboard");
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
                    onDirectorySelected(nv);
                });

        var leftBox = new VBox(toggleBar, directoryList);
        VBox.setVgrow(directoryList, Priority.ALWAYS);

        // --- Right top: session table ---
        sessionTable = new TableView<>();
        sessionTable.setPlaceholder(new Label("Select a directory."));
        sessionTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        buildSessionTableColumns();
        sessionTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, nv) -> {
                    if (refreshing) return;
                    var selected = sessionTable.getSelectionModel().getSelectedItems();
                    if (selected.size() == 1) {
                        selectedSession = selected.getFirst();
                        showSessionDetail(selectedSession);
                    } else if (selected.size() > 1) {
                        selectedSession = selected.getFirst();
                        showMultiDetail(selected);
                    } else {
                        selectedSession = null;
                        detailLabel.setText("Select a session to view details.");
                    }
                    updateActionButtons(selected.size());
                });

        // --- Right bottom: detail + actions ---
        detailLabel = new TextArea("Select a session to view details.");
        detailLabel.setEditable(false);
        detailLabel.setWrapText(true);
        detailLabel.setPadding(new Insets(10));
        detailLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-control-inner-background: transparent;");

        resumeBtn = new Button("Resume in Terminal");
        resumeBtn.setDisable(true);
        resumeBtn.setOnAction(e -> { if (selectedSession != null) resumeHandler.accept(selectedSession.id()); });
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
        actionBar = new HBox(8, resumeBtn, renameBtn, deleteBtn);
        actionBar.setPadding(new Insets(6));

        var actionPane = new VBox(4, actionBar, deleteProgress);

        detailLabel.setPrefHeight(200);

        var rightPane = new VBox(sessionTable, new Separator(), detailLabel, actionPane);
        VBox.setVgrow(sessionTable, Priority.ALWAYS);

        var split = new SplitPane(leftBox, rightPane);
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

        var msgsCol = new TableColumn<SessionSnapshot, String>("Msgs");
        msgsCol.setCellValueFactory(cd ->
                new SimpleStringProperty(String.valueOf(cd.getValue().usage().messagesCount())));
        msgsCol.setPrefWidth(50);

        var createdCol = new TableColumn<SessionSnapshot, String>("Created");
        createdCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().createdAt() != null
                        ? DATE_FMT.format(cd.getValue().createdAt()) : ""));
        createdCol.setPrefWidth(130);

        sessionTable.getColumns().addAll(nameCol, modelCol, statusCol, pctCol, msgsCol, createdCol);
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

        refreshing = true;
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
        refreshing = false;

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
    }

    private boolean isRemoteSelected() {
        if (locationToggle == null || locationToggle.getSelectedToggle() == null) return false;
        return "remote".equals(locationToggle.getSelectedToggle().getUserData());
    }

    private void updateActionButtons(int selectionCount) {
        boolean none = selectionCount == 0;
        boolean multi = selectionCount > 1;
        resumeBtn.setDisable(none || multi);
        renameBtn.setDisable(none || multi);
        deleteBtn.setDisable(none);
    }

    private void showSessionDetail(SessionSnapshot session) {
        var sb = new StringBuilder();
        sb.append("ID:          ").append(session.id()).append("\n");
        sb.append("Name:        ").append(session.name()).append("\n");
        sb.append("Model:       ").append(session.model()).append("\n");
        sb.append("Status:      ").append(session.status()).append("\n");
        sb.append("Location:    ").append(session.remote() ? "Remote" : "Local").append("\n");
        sb.append("Directory:   ").append(session.workingDirectory()).append("\n");
        if (session.createdAt() != null)
            sb.append("Created:     ").append(DATE_FMT.format(session.createdAt())).append("\n");
        if (session.lastActivityAt() != null)
            sb.append("Last Active: ").append(DATE_FMT.format(session.lastActivityAt())).append("\n");
        sb.append("\n");

        sb.append("— Usage —\n");
        sb.append("Tokens:   ").append(session.usage().currentTokens())
                .append(" / ").append(session.usage().tokenLimit())
                .append("  (").append((int) session.usage().tokenUsagePercent()).append("%)\n");
        sb.append("Messages: ").append(session.usage().messagesCount()).append("\n");

        if (!session.subagents().isEmpty()) {
            sb.append("\n— Subagents —\n");
            for (var sub : session.subagents()) {
                sb.append("  ").append(sub.id())
                        .append(" [").append(sub.status()).append("] ")
                        .append(sub.description()).append("\n");
            }
        }
        if (session.pendingPermission()) {
            sb.append("\n⚠ Permission request pending\n");
        }
        detailLabel.setText(sb.toString());
    }

    private void showMultiDetail(javafx.collections.ObservableList<SessionSnapshot> selected) {
        var sb = new StringBuilder();
        sb.append(selected.size()).append(" sessions selected\n\n");
        int totalTokens = 0, totalMsgs = 0;
        var models = new LinkedHashSet<String>();
        var statuses = new LinkedHashMap<SessionStatus, Integer>();
        for (var s : selected) {
            totalTokens += s.usage().currentTokens();
            totalMsgs += s.usage().messagesCount();
            models.add(s.model());
            statuses.merge(s.status(), 1, Integer::sum);
        }
        sb.append("— Aggregate —\n");
        sb.append("Total Tokens:   ").append(totalTokens).append("\n");
        sb.append("Total Messages: ").append(totalMsgs).append("\n");
        sb.append("Models:         ").append(String.join(", ", models)).append("\n");
        sb.append("Statuses:       ");
        statuses.forEach((st, cnt) -> sb.append(st).append("(").append(cnt).append(") "));
        sb.append("\n");
        detailLabel.setText(sb.toString());
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

    // =====================================================================
    // Usage Tab
    // =====================================================================

    private Tab createUsageTab() {
        usageDashboard = new UsageDashboard(sessionManager);
        usageDashboard.refresh(sessionManager.getSessions());
        return new Tab("Usage", usageDashboard);
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
