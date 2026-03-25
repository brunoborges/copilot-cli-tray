package com.github.copilot.tray.notify;

import com.github.copilot.tray.config.AppConfig;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends OS-level notifications for session lifecycle events.
 * Uses java.awt.SystemTray's TrayIcon.displayMessage().
 */
public class Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

    private TrayIcon trayIcon;
    private AppConfig config;
    private final Map<String, SessionStatus> lastKnownStatus = new ConcurrentHashMap<>();

    public void setTrayIcon(TrayIcon trayIcon) {
        this.trayIcon = trayIcon;
    }

    public void setConfig(AppConfig config) {
        this.config = config;
    }

    /**
     * Called whenever session state changes. Compares with last known status
     * and sends notifications for meaningful transitions.
     */
    public void onSessionChange(Collection<SessionSnapshot> sessions) {
        if (config != null && !config.isNotificationsEnabled()) return;
        if (trayIcon == null) return;

        for (var session : sessions) {
            var previous = lastKnownStatus.get(session.id());
            var current = session.status();
            lastKnownStatus.put(session.id(), current);

            if (previous == null && current == SessionStatus.ACTIVE) {
                notify("Session Started",
                        "📝 Session '" + session.name() + "' started (" + session.model() + ")",
                        TrayIcon.MessageType.INFO);
            } else if (previous != null && previous != SessionStatus.ARCHIVED && current == SessionStatus.ARCHIVED) {
                notify("Session Completed",
                        "✅ Session '" + session.name() + "' completed",
                        TrayIcon.MessageType.INFO);
            } else if (current == SessionStatus.ERROR && previous != SessionStatus.ERROR) {
                notify("Session Error",
                        "❌ Session '" + session.name() + "' encountered an error",
                        TrayIcon.MessageType.ERROR);
            } else if (current == SessionStatus.CORRUPTED && previous != SessionStatus.CORRUPTED) {
                notify("Session Corrupted",
                        "⚠️ Session '" + session.name() + "' is corrupted or incompatible",
                        TrayIcon.MessageType.WARNING);
            }

            // Context window warning
            if (session.usage().tokenUsagePercent() >= getWarningThreshold()
                    && session.status() != SessionStatus.ARCHIVED
                    && session.status() != SessionStatus.CORRUPTED) {
                notify("Context Warning",
                        "⚠️ Session '" + session.name() + "' context is "
                                + (int) session.usage().tokenUsagePercent() + "% full",
                        TrayIcon.MessageType.WARNING);
            }

            // Permission request
            if (session.pendingPermission() && previous != null) {
                notify("Permission Requested",
                        "🔐 Session '" + session.name() + "' is waiting for permission",
                        TrayIcon.MessageType.INFO);
            }
        }
    }

    private int getWarningThreshold() {
        return config != null ? config.getContextWarningThreshold() : 80;
    }

    private void notify(String title, String message, TrayIcon.MessageType type) {
        try {
            trayIcon.displayMessage(title, message, type);
        } catch (Exception e) {
            LOG.warn("Failed to display notification: {}", message, e);
        }
    }
}
