package com.github.copilot.tray.ui;

import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.Collection;

/**
 * JavaFX settings window launched from the system tray.
 * Uses programmatic UI construction (no FXML) for simplicity in v1.
 */
public class SettingsWindow {

    private static final int TRAY_SESSION_OVERFLOW = 10;

    private final SessionManager sessionManager;
    private final ConfigStore configStore;
    private final java.util.function.Consumer<String> deleteHandler;
    private final java.util.function.Consumer<String> resumeHandler;
    private Stage stage;
    private TabPane tabPane;

    // Session tab controls
    private TreeView<String> sessionTree;
    private Label detailLabel;
    private HBox actionBar;

    // Track currently selected session for actions
    private SessionSnapshot selectedSession;

    // Preferences tab controls
    private TextField cliPathField;
    private Spinner<Integer> pollIntervalSpinner;
    private Spinner<Integer> warningThresholdSpinner;
    private CheckBox notificationsCheckBox;
    private CheckBox autoStartCheckBox;

    public SettingsWindow(SessionManager sessionManager, ConfigStore configStore,
                          java.util.function.Consumer<String> deleteHandler,
                          java.util.function.Consumer<String> resumeHandler) {
        this.sessionManager = sessionManager;
        this.configStore = configStore;
        this.deleteHandler = deleteHandler;
        this.resumeHandler = resumeHandler;
    }

    /**
     * Show (or bring to front) the settings window.
     * Must be called on the JavaFX Application Thread.
     */
    public void show() {
        Platform.runLater(() -> {
            if (stage == null) {
                stage = createStage();
            }
            refreshSessionTree(sessionManager.getSessions());
            stage.show();
            stage.toFront();
        });
    }

    /**
     * Show the settings window with the Sessions tab selected.
     */
    public void showSessionsTab() {
        Platform.runLater(() -> {
            if (stage == null) {
                stage = createStage();
            }
            refreshSessionTree(sessionManager.getSessions());
            if (tabPane != null) {
                tabPane.getSelectionModel().select(0); // Sessions is first tab
            }
            stage.show();
            stage.toFront();
        });
    }

    /**
     * Returns the overflow threshold. When the total session count exceeds this,
     * the tray menu should redirect to the settings window instead.
     */
    public static int getTraySessionOverflow() {
        return TRAY_SESSION_OVERFLOW;
    }

    /**
     * Refresh session data in the settings window.
     */
    public void onSessionChange(Collection<SessionSnapshot> sessions) {
        if (stage != null && stage.isShowing()) {
            Platform.runLater(() -> refreshSessionTree(sessions));
        }
    }

    private Stage createStage() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().addAll(
                createSessionsTab(),
                createUsageTab(),
                createPreferencesTab(),
                createAboutTab()
        );

        var scene = new Scene(tabPane, 800, 550);
        var s = new Stage();
        s.setTitle("Copilot CLI Tray — Settings");
        s.setScene(scene);
        s.setOnCloseRequest(e -> {
            e.consume();
            s.hide();
        });
        return s;
    }

    // --- Sessions Tab (TreeView with grouped local/remote + active/archived) ---

    private Tab createSessionsTab() {
        sessionTree = new TreeView<>();
        sessionTree.setShowRoot(false);
        sessionTree.setPrefWidth(300);
        sessionTree.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> onTreeSelection(selected));

        detailLabel = new Label("Select a session to view details.");
        detailLabel.setWrapText(true);
        detailLabel.setPadding(new Insets(10));

        // Action buttons
        var resumeBtn = new Button("Resume in Terminal");
        resumeBtn.setOnAction(e -> {
            if (selectedSession != null) resumeHandler.accept(selectedSession.id());
        });

        var cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> {
            if (selectedSession != null) deleteHandler.accept(selectedSession.id());
        });

        var deleteBtn = new Button("Delete");
        deleteBtn.setStyle("-fx-text-fill: red;");
        deleteBtn.setOnAction(e -> {
            if (selectedSession != null) {
                var confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Delete session '" + selectedSession.name() + "'?",
                        ButtonType.YES, ButtonType.NO);
                confirm.setHeaderText(null);
                confirm.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.YES) {
                        deleteHandler.accept(selectedSession.id());
                    }
                });
            }
        });

        actionBar = new HBox(8, resumeBtn, cancelBtn, deleteBtn);
        actionBar.setPadding(new Insets(8));
        actionBar.setDisable(true);

        var detailScroll = new ScrollPane(detailLabel);
        detailScroll.setFitToWidth(true);
        detailScroll.setFitToHeight(true);

        var rightPane = new VBox(detailScroll, actionBar);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        var split = new SplitPane(sessionTree, rightPane);
        split.setDividerPositions(0.38);

        return new Tab("Sessions", split);
    }

    private void onTreeSelection(TreeItem<String> item) {
        if (item == null || item.getValue() == null) {
            selectedSession = null;
            detailLabel.setText("Select a session to view details.");
            actionBar.setDisable(true);
            return;
        }

        // Only leaf nodes (no children) represent sessions
        if (!item.isLeaf()) {
            selectedSession = null;
            detailLabel.setText("Select a session to view details.");
            actionBar.setDisable(true);
            return;
        }

        // The user data is set to session ID in refreshSessionTree
        var sessionId = (String) item.getValue();
        // Match by trying to find a session whose tree label matches
        selectedSession = sessionManager.getSessions().stream()
                .filter(s -> buildTreeLabel(s).equals(sessionId))
                .findFirst()
                .orElse(null);

        if (selectedSession == null) {
            detailLabel.setText("Session not found.");
            actionBar.setDisable(true);
            return;
        }

        actionBar.setDisable(false);
        showSessionDetail(selectedSession);
    }

    private void showSessionDetail(SessionSnapshot session) {
        var sb = new StringBuilder();
        sb.append("ID: ").append(session.id()).append("\n");
        sb.append("Name: ").append(session.name()).append("\n");
        sb.append("Model: ").append(session.model()).append("\n");
        sb.append("Status: ").append(session.status()).append("\n");
        sb.append("Location: ").append(session.remote() ? "Remote" : "Local").append("\n");
        sb.append("Working Directory: ").append(session.workingDirectory()).append("\n");
        sb.append("Created: ").append(session.createdAt()).append("\n");
        sb.append("Last Activity: ").append(session.lastActivityAt()).append("\n\n");

        sb.append("— Usage —\n");
        sb.append("Tokens: ").append(session.usage().currentTokens())
                .append(" / ").append(session.usage().tokenLimit())
                .append(" (").append((int) session.usage().tokenUsagePercent()).append("%)\n");
        sb.append("Messages: ").append(session.usage().messagesCount()).append("\n\n");

        if (!session.subagents().isEmpty()) {
            sb.append("— Subagents —\n");
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

    private void refreshSessionTree(Collection<SessionSnapshot> sessions) {
        if (sessionTree == null) return;

        var root = new TreeItem<String>("Sessions");

        var local = sessions.stream().filter(s -> !s.remote()).toList();
        var remote = sessions.stream().filter(SessionSnapshot::remote).toList();

        root.getChildren().add(buildGroupNode("Local Sessions", local));
        root.getChildren().add(buildGroupNode("Remote Sessions", remote));

        root.setExpanded(true);
        sessionTree.setRoot(root);
    }

    private TreeItem<String> buildGroupNode(String label, java.util.List<SessionSnapshot> sessions) {
        var active = sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED).toList();
        var archived = sessions.stream()
                .filter(s -> s.status() == SessionStatus.ARCHIVED).toList();

        var group = new TreeItem<>(label + " (" + sessions.size() + ")");
        group.setExpanded(true);

        var activeNode = new TreeItem<>("Active (" + active.size() + ")");
        activeNode.setExpanded(true);
        for (var s : active) {
            activeNode.getChildren().add(new TreeItem<>(buildTreeLabel(s)));
        }
        group.getChildren().add(activeNode);

        var archivedNode = new TreeItem<>("Archived (" + archived.size() + ")");
        archivedNode.setExpanded(false); // collapsed by default
        for (var s : archived) {
            archivedNode.getChildren().add(new TreeItem<>(buildTreeLabel(s)));
        }
        group.getChildren().add(archivedNode);

        return group;
    }

    private static String buildTreeLabel(SessionSnapshot s) {
        var label = s.name();
        if (!"unknown".equals(s.model())) {
            label += " [" + s.model() + "]";
        }
        if (s.usage().tokenUsagePercent() > 0) {
            label += " — " + (int) s.usage().tokenUsagePercent() + "%";
        }
        return label;
    }

    // --- Usage Tab ---

    private Tab createUsageTab() {
        var content = new VBox(10);
        content.setPadding(new Insets(15));
        content.getChildren().add(new Label("Usage metrics are updated in real-time from active sessions."));

        // Placeholder — will be enriched in later phases
        var table = new TextArea();
        table.setEditable(false);
        table.setPrefRowCount(20);
        content.getChildren().add(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        // Populate on tab select
        var tab = new Tab("Usage", content);
        tab.setOnSelectionChanged(e -> {
            if (tab.isSelected()) {
                var sb = new StringBuilder();
                sb.append(String.format("%-30s %-10s %-15s %-10s%n", "Session", "Status", "Tokens", "Usage%"));
                sb.append("-".repeat(70)).append("\n");
                for (var session : sessionManager.getSessions()) {
                    sb.append(String.format("%-30s %-10s %6d/%-6d %5.1f%%%n",
                            truncate(session.name(), 30),
                            session.status(),
                            session.usage().currentTokens(),
                            session.usage().tokenLimit(),
                            session.usage().tokenUsagePercent()));
                }
                table.setText(sb.toString());
            }
        });
        return tab;
    }

    // --- Preferences Tab ---

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

    // --- About Tab ---

    private Tab createAboutTab() {
        var content = new VBox(10);
        content.setPadding(new Insets(15));

        content.getChildren().addAll(
                new Label("Copilot CLI Tray"),
                new Label("Version: 1.0.0-SNAPSHOT"),
                new Label("License: MIT"),
                new Separator(),
                new Label("A cross-platform system tray application to track and manage"),
                new Label("GitHub Copilot CLI sessions and remote coding agents."),
                new Separator(),
                new Label("SDK: copilot-sdk-java"),
                new Label("JDK: " + System.getProperty("java.version")),
                new Label("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch")),
                new Separator(),
                createHyperlink("GitHub", "https://github.com/brunoborges/copilot-cli-tray")
        );

        return new Tab("About", content);
    }

    private Hyperlink createHyperlink(String text, String url) {
        var link = new Hyperlink(text + ": " + url);
        link.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ex) {
                // ignore
            }
        });
        return link;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
