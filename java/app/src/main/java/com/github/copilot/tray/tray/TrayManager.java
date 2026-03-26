package com.github.copilot.tray.tray;

import com.github.copilot.sdk.ConnectionState;
import com.github.copilot.tray.sdk.SdkBridge;
import com.github.copilot.tray.sdk.TerminalLauncher;
import com.github.copilot.tray.session.SessionDiskReader;
import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the system tray icon and its context menu.
 * Organizes sessions by working directory.
 */
public class TrayManager {

    private static final Logger LOG = LoggerFactory.getLogger(TrayManager.class);
    private static final int TRAY_GROUP_LIMIT = 10;
    private static final int DIR_LIMIT = 15;

    private final SessionManager sessionManager;
    private final SdkBridge sdkBridge;
    private final TerminalLauncher terminalLauncher;
    private final Runnable onOpenSettings;
    private final Runnable onShowSessions;
    private TrayIcon trayIcon;
    private volatile String cliStatusLabel = "🔴 Copilot CLI is Disconnected";

    public TrayManager(SessionManager sessionManager, SdkBridge sdkBridge,
                       TerminalLauncher terminalLauncher, Runnable onOpenSettings,
                       Runnable onShowSessions) {
        this.sessionManager = sessionManager;
        this.sdkBridge = sdkBridge;
        this.terminalLauncher = terminalLauncher;
        this.onOpenSettings = onOpenSettings;
        this.onShowSessions = onShowSessions;
    }

    public void install() {
        if (!SystemTray.isSupported()) {
            LOG.error("SystemTray is not supported on this platform");
            return;
        }
        var image = loadIcon();
        trayIcon = new TrayIcon(image, "GitHub Copilot Agentic Tray");
        trayIcon.setImageAutoSize(true);
        trayIcon.setPopupMenu(buildMenu(sessionManager.getSessions()));
        // Rebuild menu on every click so it's fresh when opened
        trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                refreshCliStatus();
                trayIcon.setPopupMenu(buildMenu(sessionManager.getSessions()));
            }
        });
        try {
            SystemTray.getSystemTray().add(trayIcon);
            LOG.info("System tray icon installed");
        } catch (AWTException e) {
            LOG.error("Failed to add tray icon", e);
        }
    }

    public void uninstall() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            LOG.info("System tray icon removed");
        }
    }

    public TrayIcon getTrayIcon() { return trayIcon; }

    public void refresh(Collection<SessionSnapshot> sessions) {
        if (trayIcon == null) return;
        refreshCliStatus();
        trayIcon.setPopupMenu(buildMenu(sessions));
    }

    // =====================================================================
    // Menu — organized by working directory
    // =====================================================================

    private PopupMenu buildMenu(Collection<SessionSnapshot> sessions) {
        var menu = new PopupMenu("GitHub Copilot Agentic Tray");

        // CLI status (first item)
        menu.add(disabledItem(buildCliStatusLabel()));
        menu.addSeparator();

        // Dashboard
        menu.add(actionItem("Dashboard", e -> onOpenSettings.run()));
        menu.addSeparator();

        // Group by directory, sorted by most recent activity
        var byDir = sessions.stream()
                .collect(Collectors.groupingBy(SessionSnapshot::workingDirectory));

        // Sort directories: those with active sessions first, then by most recent activity
        var sortedDirs = byDir.entrySet().stream()
                .sorted((a, b) -> {
                    boolean aHasActive = a.getValue().stream().anyMatch(s ->
                            s.status() != SessionStatus.ARCHIVED && s.status() != SessionStatus.CORRUPTED);
                    boolean bHasActive = b.getValue().stream().anyMatch(s ->
                            s.status() != SessionStatus.ARCHIVED && s.status() != SessionStatus.CORRUPTED);
                    if (aHasActive != bHasActive) return bHasActive ? 1 : -1;
                    var aMax = a.getValue().stream().map(SessionSnapshot::lastActivityAt)
                            .filter(Objects::nonNull).max(java.time.Instant::compareTo);
                    var bMax = b.getValue().stream().map(SessionSnapshot::lastActivityAt)
                            .filter(Objects::nonNull).max(java.time.Instant::compareTo);
                    return bMax.orElse(java.time.Instant.EPOCH)
                            .compareTo(aMax.orElse(java.time.Instant.EPOCH));
                })
                .limit(DIR_LIMIT)
                .toList();

        if (sortedDirs.isEmpty()) {
            menu.add(disabledItem("No sessions"));
        } else {
            for (var entry : sortedDirs) {
                menu.add(buildDirectoryMenu(entry.getKey(), entry.getValue()));
            }
            if (byDir.size() > DIR_LIMIT) {
                menu.addSeparator();
                menu.add(actionItem("View All Directories…", e -> onShowSessions.run()));
            }
        }

        menu.addSeparator();

        // Usage summary
        var usageMenu = new Menu("Usage Summary");
        int totalTokens = sessions.stream().mapToInt(s -> s.usage().currentTokens()).sum();
        int totalLimit = sessions.stream().mapToInt(s -> s.usage().tokenLimit()).max().orElse(0);
        usageMenu.add(disabledItem("Tokens: " + formatNumber(totalTokens)
                + (totalLimit > 0 ? " / " + formatNumber(totalLimit) : "")));
        usageMenu.add(disabledItem("Sessions: " + sessions.size()));
        menu.add(usageMenu);

        menu.addSeparator();
        menu.add(actionItem("New Session", e -> terminalLauncher.newSession()));
        menu.addSeparator();
        menu.add(actionItem("Quit", e -> { uninstall(); System.exit(0); }));

        return menu;
    }

    /**
     * Build a submenu for a working directory, containing its sessions.
     */
    private Menu buildDirectoryMenu(String directory, java.util.List<SessionSnapshot> sessions) {
        var dirLabel = shortenPath(directory);
        long activeCount = sessions.stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED && s.status() != SessionStatus.CORRUPTED)
                .count();

        var label = dirLabel + " (" + sessions.size() + ")";
        if (activeCount > 0) label += " ●";
        var dirMenu = new Menu(label);

        // Sort: active first (by last activity desc), then archived, then corrupted
        var sorted = sessions.stream()
                .sorted(Comparator
                        .<SessionSnapshot, Integer>comparing(s ->
                                s.status() == SessionStatus.CORRUPTED ? 2
                                        : s.status() == SessionStatus.ARCHIVED ? 1 : 0)
                        .thenComparing(SessionSnapshot::lastActivityAt, Comparator.nullsFirst(Comparator.reverseOrder())))
                .limit(TRAY_GROUP_LIMIT)
                .toList();

        for (var session : sorted) {
            dirMenu.add(buildSessionMenu(session));
        }

        if (sessions.size() > TRAY_GROUP_LIMIT) {
            dirMenu.addSeparator();
            dirMenu.add(actionItem("View All…", e -> onShowSessions.run()));
        }

        return dirMenu;
    }

    private Menu buildSessionMenu(SessionSnapshot session) {
        var usagePct = (int) session.usage().tokenUsagePercent();
        var label = session.name();
        if (!"unknown".equals(session.model())) label += " [" + session.model() + "]";
        if (session.status() == SessionStatus.CORRUPTED) label += " ⚠";
        var sessionMenu = new Menu(label);

        // Status info
        var statusText = session.status().name();
        if (usagePct > 0) statusText += " (" + usagePct + "% context)";
        if (session.remote()) statusText += " — Remote";
        sessionMenu.add(disabledItem(statusText));

        if (!session.subagents().isEmpty()) {
            sessionMenu.add(disabledItem("Subagents: " + session.subagents().size()));
        }
        if (session.pendingPermission()) {
            sessionMenu.add(disabledItem("⚠ Permission requested"));
        }

        sessionMenu.addSeparator();

        // Actions (based on status)
        if (session.status() != SessionStatus.CORRUPTED) {
            sessionMenu.add(actionItem("Resume in Terminal", e ->
                    terminalLauncher.resumeSession(session.id(), session.workingDirectory())));
        }
        if (session.status() == SessionStatus.BUSY) {
            sessionMenu.add(actionItem("Cancel", e ->
                    sdkBridge.cancelSession(session.id())));
        }
        sessionMenu.add(actionItem("Delete", e ->
                sdkBridge.deleteSession(session.id())
                        .whenComplete((v, ex) -> {
                            if (ex != null) {
                                LOG.warn("SDK delete failed for session {}, proceeding with disk delete", session.id(), ex);
                            }
                            SessionDiskReader.deleteFromDisk(session.id());
                            sessionManager.removeSession(session.id());
                        })));

        return sessionMenu;
    }

    // =====================================================================
    // CLI status
    // =====================================================================

    /**
     * Update CLI status label asynchronously from SDK bridge.
     * Called periodically or on refresh.
     */
    public void refreshCliStatus() {
        sdkBridge.fetchCliStatus().thenAccept(status -> {
            var sb = new StringBuilder();
            var stateStr = switch (status.connectionState()) {
                case CONNECTED -> { sb.append("🟢 "); yield "Connected"; }
                case CONNECTING -> { sb.append("🟡 "); yield "Connecting…"; }
                case DISCONNECTED -> { sb.append("🔴 "); yield "Disconnected"; }
                case ERROR -> { sb.append("🔴 "); yield "Error"; }
            };
            sb.append("Copilot CLI");
            if (status.version() != null) sb.append(" ").append(status.version());
            sb.append(" is ").append(stateStr);
            cliStatusLabel = sb.toString();
        }).exceptionally(ex -> {
            LOG.debug("Failed to fetch CLI status", ex);
            cliStatusLabel = "🔴 Copilot CLI is Disconnected";
            return null;
        });
    }

    private String buildCliStatusLabel() {
        return cliStatusLabel;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Shorten path for tray display: ~/relative or last 2 components. */
    static String shortenPath(String path) {
        if (path == null || path.isEmpty()) return "(unknown)";
        var home = System.getProperty("user.home");
        if (home != null && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        var parts = path.replace('\\', '/').split("/");
        if (parts.length <= 2) return path;
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private Image loadIcon() {
        var url = getClass().getResource("/icons/tray-idle.png");
        if (url != null) return Toolkit.getDefaultToolkit().getImage(url);
        LOG.warn("Tray icon not found");
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
