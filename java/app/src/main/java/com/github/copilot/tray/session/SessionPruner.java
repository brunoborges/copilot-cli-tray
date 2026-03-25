package com.github.copilot.tray.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Identifies low-value Copilot CLI sessions that can be safely pruned.
 * Scans the on-disk session store (~/.copilot/session-state/) and classifies
 * sessions using heuristics:
 * <ul>
 *   <li><b>EMPTY</b> — No events.jsonl, or zero user messages</li>
 *   <li><b>ABANDONED</b> — Has user messages but no assistant response</li>
 *   <li><b>TRIVIAL</b> — Very short exchanges (≤5 user messages)</li>
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
                    var candidate = analyzeSession(sessionDir, includeTrivial);
                    candidate.ifPresent(candidates::add);
                } catch (Exception e) {
                    // Sessions that throw during analysis are corrupted
                    LOG.debug("Corrupted session dir {}: {}", sessionDir, e.getMessage());
                    var sessionId = sessionDir.getFileName().toString();
                    candidates.add(new PruneCandidate(
                            sessionId, PruneCategory.CORRUPTED, directorySize(sessionDir),
                            Instant.EPOCH, Instant.EPOCH, "(analysis failed: " + e.getMessage() + ")",
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

    // --- Analysis ---

    private Optional<PruneCandidate> analyzeSession(Path sessionDir, boolean includeTrivial) {
        String sessionId = sessionDir.getFileName().toString();
        Path eventsFile = sessionDir.resolve("events.jsonl");
        Path workspaceFile = sessionDir.resolve("workspace.yaml");

        // Parse workspace metadata
        Instant createdAt = Instant.EPOCH;
        Instant updatedAt = Instant.EPOCH;
        String workingDirectory = "";
        if (Files.exists(workspaceFile)) {
            try {
                var meta = parseWorkspaceYaml(workspaceFile);
                createdAt = meta.getOrDefault("created_at", "").isEmpty()
                        ? Instant.EPOCH : parseInstant(meta.get("created_at"));
                updatedAt = meta.getOrDefault("updated_at", "").isEmpty()
                        ? createdAt : parseInstant(meta.get("updated_at"));
                workingDirectory = meta.getOrDefault("cwd", "");
            } catch (Exception e) {
                LOG.debug("Corrupted workspace.yaml in {}: {}", sessionId, e.getMessage());
                long diskSize = directorySize(sessionDir);
                return Optional.of(new PruneCandidate(
                        sessionId, PruneCategory.CORRUPTED, diskSize,
                        createdAt, updatedAt, "(corrupted workspace.yaml)", workingDirectory, 0, 0));
            }
        }

        long diskSize = directorySize(sessionDir);

        // Category: EMPTY — no events.jsonl at all
        if (!Files.exists(eventsFile)) {
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.EMPTY, diskSize,
                    createdAt, updatedAt, "(no events)", workingDirectory, 0, 0));
        }

        // Count event types
        int userMessages = 0;
        int assistantMessages = 0;
        String firstUserMessage = "";
        boolean corrupted = false;

        try (var lines = Files.lines(eventsFile)) {
            for (var line : (Iterable<String>) lines::iterator) {
                var trimmed = line.trim();
                // Detect corrupted lines: non-empty lines that aren't valid JSON objects
                if (!trimmed.isEmpty() && !trimmed.startsWith("{")) {
                    corrupted = true;
                    break;
                }
                if (trimmed.contains("\"user.message\"")) {
                    userMessages++;
                    if (userMessages == 1) {
                        firstUserMessage = extractUserMessage(trimmed);
                    }
                } else if (trimmed.contains("\"assistant.message\"")) {
                    assistantMessages++;
                }
            }
        } catch (Exception e) {
            LOG.debug("Corrupted events.jsonl in {}: {}", sessionId, e.getMessage());
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.CORRUPTED, diskSize,
                    createdAt, updatedAt, "(unreadable events.jsonl)", workingDirectory, 0, 0));
        }

        if (corrupted) {
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.CORRUPTED, diskSize,
                    createdAt, updatedAt, "(malformed events.jsonl)", workingDirectory,
                    userMessages, assistantMessages));
        }

        // Category: EMPTY — events file exists but zero user messages
        if (userMessages == 0) {
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.EMPTY, diskSize,
                    createdAt, updatedAt, "(no user messages)", workingDirectory,
                    userMessages, assistantMessages));
        }

        // Category: ABANDONED — user messages but zero assistant responses
        if (assistantMessages == 0) {
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.ABANDONED, diskSize,
                    createdAt, updatedAt, truncate(firstUserMessage, 80), workingDirectory,
                    userMessages, assistantMessages));
        }

        // Category: TRIVIAL — very short exchanges
        if (includeTrivial && userMessages <= TRIVIAL_MESSAGE_THRESHOLD) {
            return Optional.of(new PruneCandidate(
                    sessionId, PruneCategory.TRIVIAL, diskSize,
                    createdAt, updatedAt, truncate(firstUserMessage, 80), workingDirectory,
                    userMessages, assistantMessages));
        }

        return Optional.empty(); // Not prunable
    }

    /** Extract the user message content from a JSON event line. */
    private static String extractUserMessage(String jsonLine) {
        // Simple extraction without a JSON parser for performance
        // Look for "content":"..." in the data object
        int idx = jsonLine.indexOf("\"content\":");
        if (idx < 0) return "(unknown)";
        idx = jsonLine.indexOf('"', idx + 10);
        if (idx < 0) return "(unknown)";
        int end = jsonLine.indexOf('"', idx + 1);
        // Handle escaped quotes
        while (end > 0 && jsonLine.charAt(end - 1) == '\\') {
            end = jsonLine.indexOf('"', end + 1);
        }
        if (end < 0) return "(unknown)";
        return jsonLine.substring(idx + 1, end)
                .replace("\\n", " ")
                .replace("\\t", " ")
                .trim();
    }

    /** Simple YAML parser for workspace.yaml (key: value on each line). */
    private static Map<String, String> parseWorkspaceYaml(Path yamlFile) {
        Map<String, String> map = new HashMap<>();
        try (var lines = Files.lines(yamlFile)) {
            lines.forEach(line -> {
                int colon = line.indexOf(':');
                if (colon > 0 && !line.startsWith(" ") && !line.startsWith("-")) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    map.put(key, value);
                }
            });
        } catch (IOException e) {
            // ignore
        }
        return map;
    }

    private static Instant parseInstant(String iso8601) {
        try {
            return Instant.parse(iso8601);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static long directorySize(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(f -> {
                        try { return Files.size(f); } catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
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
