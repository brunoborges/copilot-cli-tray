package com.github.copilot.tray.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Identifies low-value Copilot CLI sessions that can be safely pruned.
 * Scans the on-disk session store (~/.copilot/session-state/) and classifies
 * sessions using heuristics:
 * <ul>
 *   <li><b>EMPTY</b> — No events.jsonl, or zero user messages</li>
 *   <li><b>ABANDONED</b> — Has user messages but no assistant response</li>
 *   <li><b>TRIVIAL</b> — Very short exchanges (≤5 user messages)</li>
 *   <li><b>CORRUPTED</b> — Unreadable or incompatible session data</li>
 * </ul>
 */
public class SessionPruner {

    private static final Logger LOG = LoggerFactory.getLogger(SessionPruner.class);

    private static final int TRIVIAL_MESSAGE_THRESHOLD = 5;

    private final Path sessionStoreDir;

    public SessionPruner() {
        this(defaultSessionStoreDir());
    }

    public SessionPruner(Path sessionStoreDir) {
        this.sessionStoreDir = sessionStoreDir;
    }

    /** Category of a prunable session. */
    public enum PruneCategory {
        EMPTY("Empty — no events or user messages"),
        ABANDONED("Abandoned — user message but no assistant response"),
        TRIVIAL("Trivial — ≤" + TRIVIAL_MESSAGE_THRESHOLD + " user messages"),
        CORRUPTED("Corrupted — unreadable or incompatible session data");

        private final String description;

        PruneCategory(String description) {
            this.description = description;
        }

        public String description() { return description; }
    }

    /** Metadata for a single prunable session. */
    public record PruneCandidate(
            String sessionId,
            PruneCategory category,
            long diskSizeBytes,
            Instant createdAt,
            Instant updatedAt,
            String firstUserMessage,
            String workingDirectory,
            int userMessageCount,
            int assistantMessageCount
    ) {
        /** Human-readable age relative to now. */
        public String age() {
            var duration = Duration.between(updatedAt, Instant.now());
            long days = duration.toDays();
            if (days > 0) return days + "d ago";
            long hours = duration.toHours();
            if (hours > 0) return hours + "h ago";
            return duration.toMinutes() + "m ago";
        }

        /** Disk size in human-readable format. */
        public String diskSizeFormatted() {
            if (diskSizeBytes >= 1_048_576) return String.format("%.1f MB", diskSizeBytes / 1_048_576.0);
            if (diskSizeBytes >= 1_024) return String.format("%.1f KB", diskSizeBytes / 1_024.0);
            return diskSizeBytes + " B";
        }
    }

    /** Result of a prune operation. */
    public record PruneResult(
            int deletedCount,
            long totalBytesFreed,
            List<String> deletedSessionIds,
            List<String> failedSessionIds
    ) {
        public String totalBytesFreedFormatted() {
            if (totalBytesFreed >= 1_048_576) return String.format("%.1f MB", totalBytesFreed / 1_048_576.0);
            if (totalBytesFreed >= 1_024) return String.format("%.1f KB", totalBytesFreed / 1_024.0);
            return totalBytesFreed + " B";
        }
    }

    /**
     * Scan and return all sessions that match prune heuristics (dry run).
     * Does not delete anything.
     */
    public List<PruneCandidate> scan() {
        return scan(true);
    }

    /**
     * Scan for prunable sessions.
     * @param includeTrivial if true, also flag sessions with ≤5 user messages
     */
    public List<PruneCandidate> scan(boolean includeTrivial) {
        if (!Files.isDirectory(sessionStoreDir)) {
            LOG.warn("Session store directory not found: {}", sessionStoreDir);
            return List.of();
        }

        List<PruneCandidate> candidates = new ArrayList<>();

        try (var dirs = Files.list(sessionStoreDir)) {
            dirs.filter(Files::isDirectory).forEach(sessionDir -> {
                try {
                    var info = SessionDiskReader.readDiskInfo(sessionDir);
                    var candidate = classify(info, includeTrivial);
                    candidate.ifPresent(candidates::add);
                } catch (Exception e) {
                    // Sessions that throw during analysis are corrupted
                    LOG.debug("Corrupted session dir {}: {}", sessionDir, e.getMessage());
                    var sessionId = sessionDir.getFileName().toString();
                    candidates.add(new PruneCandidate(
                            sessionId, PruneCategory.CORRUPTED,
                            SessionDiskReader.directorySize(sessionDir),
                            Instant.EPOCH, Instant.EPOCH,
                            "(analysis failed: " + e.getMessage() + ")",
                            "", 0, 0));
                }
            });
        } catch (IOException e) {
            LOG.error("Failed to scan session store", e);
        }

        // Sort by category priority (EMPTY first), then by age (oldest first)
        candidates.sort(Comparator
                .comparing(PruneCandidate::category)
                .thenComparing(PruneCandidate::updatedAt));

        return List.copyOf(candidates);
    }

    /**
     * Delete the specified sessions from disk.
     * Returns a result with counts and freed space.
     */
    public PruneResult delete(List<PruneCandidate> candidates) {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long totalFreed = 0;

        for (var candidate : candidates) {
            var sessionDir = sessionStoreDir.resolve(candidate.sessionId());
            if (!Files.isDirectory(sessionDir)) {
                failed.add(candidate.sessionId());
                continue;
            }
            try {
                deleteDirectoryRecursively(sessionDir);
                deleted.add(candidate.sessionId());
                totalFreed += candidate.diskSizeBytes();
                LOG.info("Pruned session: {} [{}] ({})",
                        candidate.sessionId(), candidate.category(), candidate.diskSizeFormatted());
            } catch (IOException e) {
                LOG.error("Failed to delete session {}", candidate.sessionId(), e);
                failed.add(candidate.sessionId());
            }
        }

        return new PruneResult(deleted.size(), totalFreed, List.copyOf(deleted), List.copyOf(failed));
    }

    // --- Classification from SessionDiskInfo ---

    /**
     * Classify a SessionDiskInfo into an optional PruneCandidate.
     * Returns empty if the session is not prunable.
     */
    static Optional<PruneCandidate> classify(SessionDiskReader.SessionDiskInfo info,
                                              boolean includeTrivial) {
        if (info.corrupted()) {
            String msg = info.corruptionReason() != null
                    ? "(" + info.corruptionReason() + ")" : "(corrupted)";
            return Optional.of(new PruneCandidate(
                    info.sessionId(), PruneCategory.CORRUPTED, info.diskSizeBytes(),
                    info.createdAt(), info.updatedAt(), msg,
                    info.workingDirectory(), info.userMessages(), info.assistantMessages()));
        }

        if (!info.hasEvents()) {
            return Optional.of(new PruneCandidate(
                    info.sessionId(), PruneCategory.EMPTY, info.diskSizeBytes(),
                    info.createdAt(), info.updatedAt(), "(no events)",
                    info.workingDirectory(), 0, 0));
        }

        if (info.userMessages() == 0) {
            return Optional.of(new PruneCandidate(
                    info.sessionId(), PruneCategory.EMPTY, info.diskSizeBytes(),
                    info.createdAt(), info.updatedAt(), "(no user messages)",
                    info.workingDirectory(), info.userMessages(), info.assistantMessages()));
        }

        if (info.assistantMessages() == 0) {
            return Optional.of(new PruneCandidate(
                    info.sessionId(), PruneCategory.ABANDONED, info.diskSizeBytes(),
                    info.createdAt(), info.updatedAt(),
                    truncate(info.firstUserMessage(), 80),
                    info.workingDirectory(), info.userMessages(), info.assistantMessages()));
        }

        if (includeTrivial && info.userMessages() <= TRIVIAL_MESSAGE_THRESHOLD) {
            return Optional.of(new PruneCandidate(
                    info.sessionId(), PruneCategory.TRIVIAL, info.diskSizeBytes(),
                    info.createdAt(), info.updatedAt(),
                    truncate(info.firstUserMessage(), 80),
                    info.workingDirectory(), info.userMessages(), info.assistantMessages()));
        }

        return Optional.empty(); // Not prunable
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.delete(path); } catch (IOException e) {
                            throw new RuntimeException("Failed to delete: " + path, e);
                        }
                    });
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "(empty)";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }

    private static Path defaultSessionStoreDir() {
        return Path.of(System.getProperty("user.home"), ".copilot", "session-state");
    }
}
