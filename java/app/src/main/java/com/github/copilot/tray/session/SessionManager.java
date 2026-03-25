package com.github.copilot.tray.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Maintains the state of all known Copilot CLI sessions.
 * Thread-safe: can be updated from SDK event threads and read from the UI thread.
 */
public class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** Sessions older than this are considered archived. */
    public static final Duration ACTIVE_THRESHOLD = Duration.ofHours(12);

    private final Map<String, SessionSnapshot> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<Collection<SessionSnapshot>>> changeListeners = new CopyOnWriteArrayList<>();

    public void addChangeListener(Consumer<Collection<SessionSnapshot>> listener) {
        changeListeners.add(listener);
    }

    public Collection<SessionSnapshot> getSessions() {
        return List.copyOf(sessions.values());
    }

    public SessionSnapshot getSession(String id) {
        return sessions.get(id);
    }

    // --- Mutation methods (called by EventRouter) ---

    public void addSession(String id, String model, String workingDirectory) {
        LOG.info("Session added: {} (model={}, dir={})", id, model, workingDirectory);
        sessions.put(id, SessionSnapshot.initial(id, model, workingDirectory));
        notifyListeners();
    }

    public void removeSession(String id) {
        LOG.info("Session removed: {}", id);
        sessions.remove(id);
        notifyListeners();
    }

    public void archiveSession(String id) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(SessionStatus.ARCHIVED));
        LOG.info("Session archived: {}", id);
        notifyListeners();
    }

    public void setStatus(String id, SessionStatus status) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(status));
        LOG.debug("Session {} status → {}", id, status);
        notifyListeners();
    }

    public void updateUsage(String id, int currentTokens, int tokenLimit, int messagesCount) {
        sessions.computeIfPresent(id, (k, s) ->
                s.withUsage(UsageSnapshot.fromSdk(currentTokens, tokenLimit, messagesCount)));
        notifyListeners();
    }

    public void updateModel(String id, String model) {
        sessions.computeIfPresent(id, (k, s) -> s.withModel(model));
        LOG.info("Session {} model → {}", id, model);
        notifyListeners();
    }

    public void updateName(String id, String name) {
        sessions.computeIfPresent(id, (k, s) -> s.withName(name));
        notifyListeners();
    }

    public void setPendingPermission(String id, boolean pending) {
        sessions.computeIfPresent(id, (k, s) -> s.withPendingPermission(pending));
        notifyListeners();
    }

    public void addSubagent(String sessionId, String subagentId, String description) {
        sessions.computeIfPresent(sessionId, (k, s) -> {
            var subs = new ArrayList<>(s.subagents());
            subs.add(new SubagentSnapshot(subagentId, description,
                    SubagentStatus.RUNNING, java.time.Instant.now()));
            return s.withSubagents(List.copyOf(subs));
        });
        notifyListeners();
    }

    public void updateSubagent(String sessionId, String subagentId, SubagentStatus status) {
        sessions.computeIfPresent(sessionId, (k, s) -> {
            var subs = s.subagents().stream()
                    .map(sub -> sub.id().equals(subagentId) ? sub.withStatus(status) : sub)
                    .toList();
            return s.withSubagents(subs);
        });
        notifyListeners();
    }

    /**
     * Populate sessions from an initial list (e.g. from listSessions() at startup).
     * Sessions whose last activity is older than 12 hours are marked ARCHIVED.
     * Reads events.jsonl from disk to populate usage data (message counts, estimated tokens).
     */
    public void populateFromMetadata(String id, String name, String model,
                                     String workingDirectory, Instant lastModified,
                                     boolean remote) {
        if (!sessions.containsKey(id)) {
            // Read disk stats for message counts and token estimates
            var diskStats = SessionDiskReader.readStats(id);
            var resolvedDir = workingDirectory != null ? workingDirectory
                    : diskStats.workingDirectory();
            var resolvedName = (name != null && !name.equals(id)) ? name
                    : (!diskStats.firstUserMessage().isEmpty() ? diskStats.firstUserMessage() : id);

            var snapshot = SessionSnapshot.initial(id, model, resolvedDir)
                    .withName(resolvedName)
                    .withRemote(remote)
                    .withUsage(diskStats.toUsageSnapshot());
            if (lastModified != null) {
                snapshot = snapshot.withLastActivity(lastModified);
                if (lastModified.isBefore(Instant.now().minus(ACTIVE_THRESHOLD))) {
                    snapshot = snapshot.withStatus(SessionStatus.ARCHIVED);
                }
            }
            sessions.put(id, snapshot);
        }
    }

    /**
     * Trigger listeners (call after batch population).
     */
    public void fireChange() {
        notifyListeners();
    }

    /**
     * Mark a session as corrupted (unresumable, incompatible data).
     */
    public void markCorrupted(String id) {
        sessions.computeIfPresent(id, (k, s) -> s.withStatus(SessionStatus.CORRUPTED));
        LOG.warn("Session marked as corrupted: {}", id);
        notifyListeners();
    }

    private void notifyListeners() {
        var snapshot = getSessions();
        for (var listener : changeListeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                LOG.error("Change listener error", e);
            }
        }
    }
}
