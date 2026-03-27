package com.github.copilot.tray.remote;

import com.github.copilot.tray.session.SessionManager;
import com.github.copilot.tray.session.SessionSnapshot;
import com.github.copilot.tray.session.SessionStatus;
import com.github.copilot.tray.session.UsageSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically polls {@code gh agent-task list} and feeds remote sessions
 * into the {@link SessionManager}.
 */
public class RemoteSessionPoller {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteSessionPoller.class);

    public enum PollingState { NOT_STARTED, POLLING, READY }

    private final GhCliRunner ghCli;
    private final SessionManager sessionManager;
    private ScheduledExecutorService scheduler;
    private boolean ghAvailable;
    private volatile PollingState pollingState = PollingState.NOT_STARTED;

    public RemoteSessionPoller(GhCliRunner ghCli, SessionManager sessionManager) {
        this.ghCli = ghCli;
        this.sessionManager = sessionManager;
    }

    /**
     * Start polling at the given interval. Checks gh availability on first poll.
     */
    public void start(int intervalSeconds) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = Thread.ofVirtual().unstarted(r);
            t.setName("remote-session-poller");
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 2, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("Remote session poller started (interval: {}s)", intervalSeconds);
    }

    /**
     * Stop polling.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            LOG.info("Remote session poller stopped");
        }
    }

    /**
     * Whether the gh CLI is available (determined after first poll attempt).
     */
    public boolean isGhAvailable() {
        return ghAvailable;
    }

    /**
     * Current polling state — UI can show loading indicator if POLLING.
     */
    public PollingState getPollingState() {
        return pollingState;
    }

    private void poll() {
        try {
            if (pollingState == PollingState.NOT_STARTED) {
                pollingState = PollingState.POLLING;
            }

            if (!ghAvailable) {
                ghAvailable = ghCli.isAvailable();
                if (!ghAvailable) {
                    LOG.debug("gh CLI not available, skipping remote poll");
                    return;
                }
                LOG.info("gh CLI detected, starting remote session discovery");
            }

            var tasks = ghCli.listTasks(50).join();
            boolean firstPoll = pollingState == PollingState.POLLING;
            pollingState = PollingState.READY;

            if (tasks.isEmpty()) {
                LOG.debug("No remote agent tasks found");
                if (firstPoll) sessionManager.fireChange(); // clear loading state
                return;
            }

            boolean changed = false;
            for (var task : tasks) {
                var existing = sessionManager.getSession(task.id());
                var snapshot = toSnapshot(task);

                if (existing == null) {
                    sessionManager.putSession(snapshot);
                    changed = true;
                } else if (!existing.status().equals(snapshot.status())
                        || !existing.name().equals(snapshot.name())) {
                    sessionManager.putSession(snapshot);
                    changed = true;
                }
            }

            if (changed || firstPoll) {
                sessionManager.fireChange();
            }
        } catch (Exception e) {
            LOG.warn("Remote poll failed: {}", e.getMessage());
        }
    }

    static SessionSnapshot toSnapshot(GhCliRunner.AgentTask task) {
        var status = mapState(task.state());
        var repo = task.repository() != null ? task.repository() : "(no repository)";
        var name = task.name() != null ? task.name() : task.id();
        var created = parseInstant(task.createdAt());
        var updated = parseInstant(task.updatedAt());

        return new SessionSnapshot(
                task.id(),
                name,
                status,
                "remote-agent",
                created,
                updated != null ? updated : created,
                repo,
                UsageSnapshot.EMPTY,
                List.of(),
                false,
                true,
                task.pullRequestNumber(),
                task.pullRequestState(),
                task.pullRequestTitle(),
                task.pullRequestUrl(),
                task.user(),
                task.state(),
                0
        );
    }

    static SessionStatus mapState(String ghState) {
        if (ghState == null) return SessionStatus.ARCHIVED;
        return switch (ghState.toLowerCase()) {
            case "running", "queued", "in_progress" -> SessionStatus.ACTIVE;
            case "idle", "waiting" -> SessionStatus.IDLE;
            case "completed" -> SessionStatus.ARCHIVED;
            case "timed_out", "failed", "error" -> SessionStatus.ERROR;
            default -> SessionStatus.ARCHIVED;
        };
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            LOG.warn("Failed to parse instant: {}", value);
            return Instant.now();
        }
    }
}
