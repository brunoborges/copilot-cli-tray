package com.github.copilot.tray.sdk;

import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.ConnectionState;
import com.github.copilot.sdk.json.CopilotClientOptions;
import com.github.copilot.sdk.json.ModelInfo;
import com.github.copilot.sdk.json.PermissionHandler;
import com.github.copilot.sdk.json.ResumeSessionConfig;
import com.github.copilot.sdk.json.SessionMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bridge between the application and the Copilot SDK.
 * Manages the CopilotClient lifecycle and provides methods to query/control sessions.
 */
public class SdkBridge {

    private static final Logger LOG = LoggerFactory.getLogger(SdkBridge.class);

    private CopilotClient client;
    private final Map<String, CopilotSession> attachedSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "sdk-bridge-poll");
        t.setDaemon(true);
        return t;
    });
    private Consumer<List<SessionMetadata>> sessionListCallback;
    private volatile boolean connected = false;

    /**
     * Connect to the Copilot CLI process. Starts the client and begins polling.
     *
     * @param sessionListCallback called each poll cycle with the updated session list
     * @param pollIntervalSeconds how often to poll session list
     */
    public void connect(Consumer<List<SessionMetadata>> sessionListCallback, int pollIntervalSeconds) {
        this.sessionListCallback = sessionListCallback;

        client = new CopilotClient();
        client.start()
                .thenRun(() -> {
                    connected = true;
                    LOG.info("SDK bridge connected to Copilot CLI");
                    // Start polling for session list
                    scheduler.scheduleAtFixedRate(this::pollSessions,
                            0, pollIntervalSeconds, TimeUnit.SECONDS);
                })
                .exceptionally(ex -> {
                    LOG.error("Failed to connect to Copilot CLI", ex);
                    scheduleReconnect();
                    return null;
                });
    }

    /**
     * Attach to a session to receive its events (usage, subagents, etc).
     * Uses ResumeSessionConfig.setOnEvent to receive all events for this session.
     */
    public void attachSession(String sessionId, EventRouter eventRouter) {
        if (client == null || !connected) return;
        if (attachedSessions.containsKey(sessionId)) return;

        var config = new ResumeSessionConfig()
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                .setOnEvent(event -> eventRouter.route(sessionId, event));
        client.resumeSession(sessionId, config)
                .thenAccept(session -> {
                    attachedSessions.put(sessionId, session);
                    LOG.info("Attached to session {}", sessionId);
                })
                .exceptionally(ex -> {
                    LOG.warn("Failed to attach to session {}", sessionId, ex);
                    return null;
                });
    }

    public CompletableFuture<List<SessionMetadata>> listSessions() {
        if (client == null || !connected) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.listSessions();
    }

    public CompletableFuture<List<ModelInfo>> listModels() {
        if (client == null || !connected) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.listModels();
    }

    public CompletableFuture<Void> deleteSession(String sessionId) {
        detachSession(sessionId);
        if (client == null || !connected) {
            return CompletableFuture.completedFuture(null);
        }
        return client.deleteSession(sessionId);
    }

    public CompletableFuture<Void> cancelSession(String sessionId) {
        var session = attachedSessions.get(sessionId);
        if (session != null) {
            return session.abort();
        }
        return CompletableFuture.completedFuture(null);
    }

    public void detachSession(String sessionId) {
        var session = attachedSessions.remove(sessionId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                LOG.warn("Error closing session {}", sessionId, e);
            }
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void disconnect() {
        scheduler.shutdownNow();
        attachedSessions.values().forEach(s -> {
            try { s.close(); } catch (Exception ignored) {}
        });
        attachedSessions.clear();
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn("Error closing CopilotClient", e);
            }
        }
        connected = false;
        LOG.info("SDK bridge disconnected");
    }

    private void pollSessions() {
        if (client == null || !connected) return;
        try {
            client.listSessions()
                    .thenAccept(sessions -> {
                        if (sessionListCallback != null) {
                            sessionListCallback.accept(sessions);
                        }
                    })
                    .exceptionally(ex -> {
                        LOG.warn("Session poll failed", ex);
                        return null;
                    });
        } catch (Exception e) {
            LOG.warn("Session poll error", e);
        }
    }

    private void scheduleReconnect() {
        LOG.info("Scheduling reconnect in 10 seconds...");
        scheduler.schedule(() -> {
            if (!connected && client != null) {
                client.start()
                        .thenRun(() -> {
                            connected = true;
                            LOG.info("Reconnected to Copilot CLI");
                        })
                        .exceptionally(ex -> {
                            LOG.warn("Reconnect failed", ex);
                            scheduleReconnect();
                            return null;
                        });
            }
        }, 10, TimeUnit.SECONDS);
    }
}
