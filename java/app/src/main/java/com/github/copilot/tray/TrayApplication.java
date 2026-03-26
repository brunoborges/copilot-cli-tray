package com.github.copilot.tray;

import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.notify.Notifier;
import com.github.copilot.tray.remote.GhCliRunner;
import com.github.copilot.tray.remote.RemoteSessionPoller;
import com.github.copilot.tray.sdk.EventRouter;
import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.sdk.TerminalLauncher;
import com.github.copilot.tray.session.SessionDiskReader;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.tray.TrayManager;
import com.github.copilot.tray.ui.SettingsWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application lifecycle manager. Wires all components together
 * and manages startup/shutdown sequence.
 */
public class TrayApplication {

    private static final Logger LOG = LoggerFactory.getLogger(TrayApplication.class);

    private final ConfigStore configStore;
    private final SessionManager sessionManager;
    private final SdkBridge sdkBridge;
    private final EventRouter eventRouter;
    private final TerminalLauncher terminalLauncher;
    private final TrayManager trayManager;
    private final Notifier notifier;
    private final SettingsWindow settingsWindow;
    private final GhCliRunner ghCliRunner;
    private final RemoteSessionPoller remotePoller;

    public TrayApplication() {
        this.configStore = new ConfigStore();
        this.sessionManager = new SessionManager();
        this.sdkBridge = new SdkBridge();
        this.eventRouter = new EventRouter(sessionManager);
        this.terminalLauncher = new TerminalLauncher();
        this.notifier = new Notifier();
        this.ghCliRunner = new GhCliRunner();
        this.remotePoller = new RemoteSessionPoller(ghCliRunner, sessionManager);
        this.settingsWindow = new SettingsWindow(sessionManager, configStore, sdkBridge, ghCliRunner,
                sessionId -> {
                    try {
                        sdkBridge.deleteSession(sessionId).join();
                    } catch (Exception ex) {
                        LOG.warn("SDK delete failed for session {}, proceeding with disk delete", sessionId, ex);
                    }
                    SessionDiskReader.deleteFromDisk(sessionId);
                    sessionManager.removeSession(sessionId);
                },
                sessionId -> {
                    var session = sessionManager.getSession(sessionId);
                    String dir = null;
                    if (session != null) {
                        dir = session.workingDirectory();
                    } else {
                        // Fallback for prune-only sessions not in SessionManager
                        var stats = SessionDiskReader.readStats(sessionId);
                        dir = stats.workingDirectory();
                    }
                    terminalLauncher.resumeSession(sessionId, dir);
                });
        this.trayManager = new TrayManager(sessionManager, sdkBridge,
                terminalLauncher, settingsWindow::show, settingsWindow::showSessionsTab);
    }

    /**
     * Start the application: load config, connect SDK, install tray.
     */
    public void start() {
        LOG.info("Starting GitHub Copilot Agentic Tray");

        // Load configuration
        configStore.load();
        var config = configStore.getConfig();

        // Install system tray icon
        trayManager.install();

        // Wire notifier to tray icon
        notifier.setTrayIcon(trayManager.getTrayIcon());
        notifier.setConfig(config);

        // Register change listeners
        sessionManager.addChangeListener(trayManager::refresh);
        sessionManager.addChangeListener(notifier::onSessionChange);
        sessionManager.addChangeListener(settingsWindow::onSessionChange);

        // Open dashboard on startup if configured
        if (config.isOpenDashboardOnStartup()) {
            javafx.application.Platform.runLater(settingsWindow::show);
        }

        // Connect to Copilot CLI
        sdkBridge.connect(metadataList -> {
            // Reconcile session list from SDK with our state
            var sdkIds = new java.util.HashSet<String>();
            for (var meta : metadataList) {
                var id = meta.getSessionId();
                sdkIds.add(id);
                if (sessionManager.getSession(id) == null) {
                    // Parse last modified time from ISO 8601 string
                    java.time.Instant lastModified = null;
                    if (meta.getModifiedTime() != null) {
                        try {
                            lastModified = java.time.Instant.parse(meta.getModifiedTime());
                        } catch (Exception e) {
                            LOG.debug("Could not parse modifiedTime: {}", meta.getModifiedTime());
                        }
                    }

                    sessionManager.populateFromMetadata(
                            id,
                            meta.getSummary() != null ? meta.getSummary() : id,
                            null, // model not in metadata
                            null, // workspace not directly in metadata
                            lastModified,
                            meta.isRemote()
                    );
                }
            }

            // Discover sessions on disk that the SDK didn't return
            for (var diskId : SessionDiskReader.listSessionIds()) {
                if (!sdkIds.contains(diskId) && sessionManager.getSession(diskId) == null) {
                    sessionManager.populateFromMetadata(diskId, diskId, null, null, null, false);
                }
            }

            sessionManager.fireChange();
        }, config.getPollIntervalSeconds());

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

        // Start remote session poller
        remotePoller.start(config.getPollIntervalSeconds() * 2);

        LOG.info("GitHub Copilot Agentic Tray started");
    }

    /**
     * Graceful shutdown.
     */
    public void shutdown() {
        LOG.info("Shutting down GitHub Copilot Agentic Tray");
        remotePoller.stop();
        trayManager.uninstall();
        sdkBridge.disconnect();
        configStore.save();
    }
}
