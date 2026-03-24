package com.github.copilot.tray.tray;

import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.sdk.TerminalLauncher;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * Manages the system tray icon and its context menu.
 * Uses java.awt.SystemTray for broad cross-platform compatibility.
 */
public class TrayManager {

    private static final Logger LOG = LoggerFactory.getLogger(TrayManager.class);

    private final SessionManager sessionManager;
    private final SdkBridge sdkBridge;
    private final TerminalLauncher terminalLauncher;
    private final Runnable onOpenSettings;
    private final Runnable onShowSessions;
    private TrayIcon trayIcon;

    public TrayManager(SessionManager sessionManager, SdkBridge sdkBridge,
                       TerminalLauncher terminalLauncher, Runnable onOpenSettings,
                       Runnable onShowSessions) {
        this.sessionManager = sessionManager;
        this.sdkBridge = sdkBridge;
        this.terminalLauncher = terminalLauncher;
        this.onOpenSettings = onOpenSettings;
        this.onShowSessions = onShowSessions;
    }

    /**
     * Install the system tray icon. Call once at startup.
     */
    public void install() {
        if (!SystemTray.isSupported()) {
            LOG.error("SystemTray is not supported on this platform");
            return;
        }

        var image = loadIcon(TrayIconState.IDLE);
        trayIcon = new TrayIcon(image, TrayIconState.IDLE.getTooltip());
        trayIcon.setImageAutoSize(true);
        trayIcon.setPopupMenu(buildMenu(sessionManager.getSessions()));

        try {
            SystemTray.getSystemTray().add(trayIcon);
            LOG.info("System tray icon installed");
        } catch (AWTException e) {
            LOG.error("Failed to add tray icon", e);
        }
    }

    /**
     * Remove the tray icon.
     */
    public void uninstall() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            LOG.info("System tray icon removed");
        }
    }

    /**
     * Returns the TrayIcon for use by Notifier.
     */
    public TrayIcon getTrayIcon() {
        return trayIcon;
    }

    /**
     * Called when session state changes. Rebuilds the tray menu and updates the icon.
     */
    public void refresh(Collection<SessionSnapshot> sessions) {
        if (trayIcon == null) return;

        var state = computeIconState(sessions);
        trayIcon.setImage(loadIcon(state));
        trayIcon.setToolTip(state.getTooltip());
        trayIcon.setPopupMenu(buildMenu(sessions));
    }

    private PopupMenu buildMenu(Collection<SessionSnapshot> sessions) {
        var menu = new PopupMenu("Copilot CLI Tray");

        // If more than 10 sessions total, show a summary + link to settings window
        if (sessions.size() > 10) {
            long localCount = sessions.stream().filter(s -> !s.remote()).count();
            long remoteCount = sessions.stream().filter(SessionSnapshot::remote).count();
            long activeCount = sessions.stream()
                    .filter(s -> s.status() != SessionStatus.ARCHIVED).count();

            menu.add(disabledItem(sessions.size() + " sessions (" + activeCount
                    + " active, " + localCount + " local, " + remoteCount + " remote)"));
            menu.add(actionItem("View All Sessions...", e -> onShowSessions.run()));
        } else {
            // Split sessions by local vs remote, then by active vs archived
            var localSessions = sessions.stream().filter(s -> !s.remote()).toList();
            var remoteSessions = sessions.stream().filter(SessionSnapshot::remote).toList();

            menu.add(buildSessionGroup("Local Sessions", localSessions));
            menu.add(buildSessionGroup("Remote Sessions", remoteSessions));
        }

        menu.addSeparator();

        // Usage summary
        var usageMenu = new Menu("Usage Summary");
        int totalTokens = sessions.stream().mapToInt(s -> s.usage().currentTokens()).sum();
        int totalLimit = sessions.stream().mapToInt(s -> s.usage().tokenLimit()).max().orElse(0);
        usageMenu.add(disabledItem("Tokens: " + formatNumber(totalTokens)
                + (totalLimit > 0 ? " / " + formatNumber(totalLimit) : "")));
        menu.add(usageMenu);

        menu.addSeparator();

        // Settings
        menu.add(actionItem("Open Settings...", e -> onOpenSettings.run()));

        // New session
        menu.add(actionItem("New Session", e -> terminalLauncher.newSession()));

        menu.addSeparator();

        // Quit
        menu.add(actionItem("Quit", e -> {
            uninstall();
            System.exit(0);
        }));

        return menu;
    }

    /**
     * Build a menu group (e.g. "Local Sessions" or "Remote Sessions") with
     * active and archived sub-sections.
     */
    private Menu buildSessionGroup(String label, java.util.List<SessionSnapshot> sessions) {
        var active = sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED)
                .toList();
        var archived = sessions.stream()
                .filter(s -> s.status() == SessionStatus.ARCHIVED)
                .toList();

        var groupMenu = new Menu(label + " (" + sessions.size() + ")");

        if (sessions.isEmpty()) {
            groupMenu.add(disabledItem("None"));
            return groupMenu;
        }

        // Active sub-section
        var activeMenu = new Menu("Active (" + active.size() + ")");
        if (active.isEmpty()) {
            activeMenu.add(disabledItem("None"));
        } else {
            for (var session : active) {
                activeMenu.add(buildSessionMenu(session));
            }
        }
        groupMenu.add(activeMenu);

        // Archived sub-section
        var archivedMenu = new Menu("Archived (" + archived.size() + ")");
        if (archived.isEmpty()) {
            archivedMenu.add(disabledItem("None"));
        } else {
            for (var session : archived) {
                var item = new Menu(session.name());
                item.add(actionItem("Resume in Terminal", e ->
                        terminalLauncher.resumeSession(session.id())));
                item.add(actionItem("Delete", e ->
                        sdkBridge.deleteSession(session.id())
                                .thenRun(() -> sessionManager.removeSession(session.id()))));
                archivedMenu.add(item);
            }
        }
        groupMenu.add(archivedMenu);

        return groupMenu;
    }

    private Menu buildSessionMenu(SessionSnapshot session) {
        var usagePct = (int) session.usage().tokenUsagePercent();
        var label = session.name() + " [" + session.model() + "]";
        var sessionMenu = new Menu(label);

        var statusText = "Status: " + session.status();
        if (usagePct > 0) {
            statusText += " (" + usagePct + "% context)";
        }
        sessionMenu.add(disabledItem(statusText));

        if (!session.subagents().isEmpty()) {
            sessionMenu.add(disabledItem("Subagents: " + session.subagents().size()));
        }

        if (session.pendingPermission()) {
            sessionMenu.add(disabledItem("⚠ Permission requested"));
        }

        sessionMenu.addSeparator();

        sessionMenu.add(actionItem("Resume in Terminal", e ->
                terminalLauncher.resumeSession(session.id())));

        if (session.status() == SessionStatus.BUSY) {
            sessionMenu.add(actionItem("Cancel", e ->
                    sdkBridge.cancelSession(session.id())));
        }

        sessionMenu.add(actionItem("Delete", e ->
                sdkBridge.deleteSession(session.id())
                        .thenRun(() -> sessionManager.removeSession(session.id()))));

        return sessionMenu;
    }

    private TrayIconState computeIconState(Collection<SessionSnapshot> sessions) {
        boolean hasError = sessions.stream().anyMatch(s -> s.status() == SessionStatus.ERROR);
        boolean hasWarning = sessions.stream().anyMatch(s -> s.usage().tokenUsagePercent() >= 80);
        boolean hasBusy = sessions.stream().anyMatch(s -> s.status() == SessionStatus.BUSY);
        boolean hasActive = sessions.stream().anyMatch(s ->
                s.status() != SessionStatus.ARCHIVED);

        if (hasError || hasWarning) return TrayIconState.WARNING;
        if (hasBusy) return TrayIconState.BUSY;
        if (hasActive) return TrayIconState.ACTIVE;
        return TrayIconState.IDLE;
    }

    private Image loadIcon(TrayIconState state) {
        var url = getClass().getResource("/icons/" + state.getIconFilename());
        if (url != null) {
            return Toolkit.getDefaultToolkit().getImage(url);
        }
        LOG.warn("Icon not found: {}", state.getIconFilename());
        // Fallback: 1x1 transparent image
        return new java.awt.image.BufferedImage(22, 22, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    private static MenuItem actionItem(String label, ActionListener action) {
        var item = new MenuItem(label);
        item.addActionListener(action);
        return item;
    }

    private static MenuItem disabledItem(String label) {
        var item = new MenuItem(label);
        item.setEnabled(false);
        return item;
    }

    private static String formatNumber(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
