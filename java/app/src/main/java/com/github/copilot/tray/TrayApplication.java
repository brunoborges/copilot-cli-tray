package com.github.copilot.tray;

import com.github.copilot.tray.config.ConfigStore;
import com.github.copilot.tray.notify.Notifier;
import com.github.copilot.tray.sdk.EventRouter;
import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.sdk.TerminalLauncher;
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

    public TrayApplication() {
        this.configStore = new ConfigStore();
        this.sessionManager = new SessionManager();
        this.sdkBridge = new SdkBridge();
        this.eventRouter = new EventRouter(sessionManager);
        this.terminalLauncher = new TerminalLauncher();
        this.notifier = new Notifier();
        this.settingsWindow = new SettingsWindow(sessionManager, configStore,
                sessionId -> sdkBridge.deleteSession(sessionId)
                        .thenRun(() -> sessionManager.removeSession(sessionId)),
                sessionId -> terminalLauncher.resumeSession(sessionId));
        this.trayManager = new TrayManager(sessionManager, sdkBridge,
                terminalLauncher, settingsWindow::show, settingsWindow::showSessionsTab);
    }

    /**
     * Start the application: load config, connect SDK, install tray.
     */
    public void start() {
        LOG.info("Starting Copilot CLI Tray");

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

        // Connect to Copilot CLI
        sdkBridge.connect(metadataList -> {
            // Reconcile session list from SDK with our state
            for (var meta : metadataList) {
                var id = meta.getSessionId();
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

                    // Only attach for detailed events if session is active (within 12h)
                    var session = sessionManager.getSession(id);
                    if (session != null && session.status() != com.github.copilot.tray.session.SessionStatus.ARCHIVED) {
                        sdkBridge.attachSession(id, eventRouter);
                    }
                }
            }
            sessionManager.fireChange();
        }, config.getPollIntervalSeconds());

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

        LOG.info("Copilot CLI Tray started");
    }

    /**
     * Graceful shutdown.
     */
    public void shutdown() {
        LOG.info("Shutting down Copilot CLI Tray");
        trayManager.uninstall();
        sdkBridge.disconnect();
        configStore.save();
    }
}
