package com.github.copilot.tray.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads session statistics from the on-disk session store.
 * Used to populate usage data for sessions that aren't actively attached via the SDK.
 */
public final class SessionDiskReader {

    private static final Logger LOG = LoggerFactory.getLogger(SessionDiskReader.class);

    private static final Path SESSION_STORE = Path.of(
            System.getProperty("user.home"), ".copilot", "session-state");

    private static final int DEFAULT_TOKEN_LIMIT = 200_000;

    private SessionDiskReader() {}

    /**
     * Unified disk info for a single session, capturing ALL data that both
     * SessionManager and SessionPruner need from a single disk read.
     */
    public record SessionDiskInfo(
            String sessionId,
            int userMessages,
            int assistantMessages,
            long eventFileSizeBytes,
            long diskSizeBytes,
            String workingDirectory,
            String firstUserMessage,
            String model,
            int currentTokens,
            int conversationTokens,
            int systemTokens,
            int toolDefinitionsTokens,
            Instant createdAt,
            Instant updatedAt,
            boolean corrupted,
            String corruptionReason
    ) {
        public boolean hasEvents() { return eventFileSizeBytes > 0 || userMessages > 0 || assistantMessages > 0; }
        public int totalMessages() { return userMessages + assistantMessages; }

        /** Build a UsageSnapshot from real token data when available, heuristic fallback. */
        public UsageSnapshot toUsageSnapshot() {
            if (currentTokens > 0) {
                int sysTools = systemTokens + toolDefinitionsTokens;
                return new UsageSnapshot(currentTokens, DEFAULT_TOKEN_LIMIT, totalMessages(),
                        userMessages, assistantMessages, sysTools, conversationTokens);
            }
            if (conversationTokens > 0) {
                int total = conversationTokens + systemTokens + toolDefinitionsTokens;
                return new UsageSnapshot(total, DEFAULT_TOKEN_LIMIT, totalMessages(),
                        userMessages, assistantMessages,
                        systemTokens + toolDefinitionsTokens, conversationTokens);
            }
            int estimatedTokens = totalMessages() * 800;
            int sysTools = (int) (estimatedTokens * 0.30);
            int msgs = estimatedTokens - sysTools;
            return new UsageSnapshot(estimatedTokens, DEFAULT_TOKEN_LIMIT, totalMessages(),
                    userMessages, assistantMessages, sysTools, msgs);
        }
    }

    /**
     * List all session IDs found on disk.
     * Discovers both directory-based sessions and legacy .jsonl file sessions.
     */
    public static List<String> listSessionIds() {
        if (!Files.isDirectory(SESSION_STORE)) return List.of();
        try (var entries = Files.list(SESSION_STORE)) {
            return entries
                    .map(p -> {
                        var name = p.getFileName().toString();
                        if (Files.isDirectory(p)) return name;
                        if (name.endsWith(".jsonl")) return name.substring(0, name.length() - 6);
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            LOG.warn("Failed to list session-state directory", e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // readDiskInfo — unified read for both SessionManager and SessionPruner
    // -----------------------------------------------------------------------

    /**
     * Read full disk info for a session by ID (from default session store).
     * Handles both directory-based (modern) and .jsonl file (legacy) formats.
     */
    public static SessionDiskInfo readDiskInfo(String sessionId) {
        var sessionDir = SESSION_STORE.resolve(sessionId);
        if (Files.isDirectory(sessionDir)) {
            return readDiskInfo(sessionDir);
        }
        // Legacy: single .jsonl file
        var jsonlFile = SESSION_STORE.resolve(sessionId + ".jsonl");
        if (Files.exists(jsonlFile)) {
            return readDiskInfoFromLegacyFile(sessionId, jsonlFile);
        }
        return new SessionDiskInfo(sessionId, 0, 0, 0, 0, "", "", "", 0, 0, 0, 0,
                Instant.EPOCH, Instant.EPOCH, false, null);
    }

    /**
     * Read full disk info for a session from a given directory.
     */
    public static SessionDiskInfo readDiskInfo(Path sessionDir) {
        String sessionId = sessionDir.getFileName().toString();
        Path eventsFile = sessionDir.resolve("events.jsonl");
        Path workspaceFile = sessionDir.resolve("workspace.yaml");

        // Parse workspace metadata
        Instant createdAt = Instant.EPOCH;
        Instant updatedAt = Instant.EPOCH;
        String workingDirectory = "";
        boolean corrupted = false;
        String corruptionReason = null;

        if (Files.exists(workspaceFile)) {
            try {
                var meta = readWorkspaceMetadata(workspaceFile);
                var createdStr = meta.getOrDefault("created_at", "");
                var updatedStr = meta.getOrDefault("updated_at", "");
                createdAt = createdStr.isEmpty() ? Instant.EPOCH : parseInstant(createdStr);
                updatedAt = updatedStr.isEmpty() ? createdAt : parseInstant(updatedStr);
                workingDirectory = meta.getOrDefault("cwd", "");
            } catch (Exception e) {
                LOG.debug("Corrupted workspace.yaml in {}: {}", sessionId, e.getMessage());
                corrupted = true;
                corruptionReason = "corrupted workspace.yaml";
            }
        }

        long diskSize = directorySize(sessionDir);

        if (!Files.exists(eventsFile)) {
            return new SessionDiskInfo(sessionId, 0, 0, 0, diskSize,
                    workingDirectory, "", "", 0, 0, 0, 0,
                    createdAt, updatedAt, corrupted, corruptionReason);
        }

        long eventFileSize = 0;
        try {
            eventFileSize = Files.size(eventsFile);
        } catch (IOException e) {
            LOG.debug("Cannot read file size for {}", eventsFile);
        }

        return parseEventsFile(sessionId, eventsFile, workingDirectory, eventFileSize, diskSize,
                createdAt, updatedAt, corrupted, corruptionReason);
    }

    /** Read disk info from a legacy single .jsonl file (no workspace.yaml). */
    private static SessionDiskInfo readDiskInfoFromLegacyFile(String sessionId, Path eventsFile) {
        long fileSize = 0;
        try {
            fileSize = Files.size(eventsFile);
        } catch (IOException e) { /* ignore */ }
        return parseEventsFile(sessionId, eventsFile, "", fileSize, fileSize,
                Instant.EPOCH, Instant.EPOCH, false, null);
    }

    /**
     * Read disk info for all sessions found on disk.
     */
    public static List<SessionDiskInfo> readAllDiskInfo() {
        if (!Files.isDirectory(SESSION_STORE)) return List.of();
        try (var entries = Files.list(SESSION_STORE)) {
            return entries
                    .filter(Files::isDirectory)
                    .map(SessionDiskReader::readDiskInfo)
                    .toList();
        } catch (IOException e) {
            LOG.warn("Failed to list session-state directory", e);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Internal parsing
    // -----------------------------------------------------------------------

    private static SessionDiskInfo parseEventsFile(String sessionId, Path eventsFile,
                                                    String workingDirectory, long eventFileSize,
                                                    long diskSize, Instant createdAt, Instant updatedAt,
                                                    boolean alreadyCorrupted, String existingCorruptionReason) {
        int userMessages = 0;
        int assistantMessages = 0;
        String firstUserMessage = "";
        String model = "";
        int currentTokens = 0;
        int conversationTokens = 0;
        int systemTokens = 0;
        int toolDefinitionsTokens = 0;
        boolean corrupted = alreadyCorrupted;
        String corruptionReason = existingCorruptionReason;

        try (var lines = Files.lines(eventsFile)) {
            for (var line : (Iterable<String>) lines::iterator) {
                var trimmed = line.trim();
                // Detect corrupted lines: non-empty lines that aren't valid JSON objects
                if (!trimmed.isEmpty() && !trimmed.startsWith("{")) {
                    corrupted = true;
                    if (corruptionReason == null) corruptionReason = "malformed events.jsonl";
                    break;
                }
                if (trimmed.contains("\"user.message\"")) {
                    userMessages++;
                    if (userMessages == 1) {
                        firstUserMessage = extractContent(trimmed);
                    }
                } else if (trimmed.contains("\"assistant.message\"")) {
                    assistantMessages++;
                } else if (trimmed.contains("\"session.model_change\"")) {
                    var m = extractJsonField(trimmed, "newModel");
                    if (!m.isEmpty()) model = m;
                } else if (trimmed.contains("\"session.resume\"")) {
                    var m = extractJsonField(trimmed, "selectedModel");
                    if (!m.isEmpty()) model = m;
                } else if (trimmed.contains("\"session.shutdown\"")) {
                    var m = extractJsonField(trimmed, "currentModel");
                    if (!m.isEmpty()) model = m;
                    int ct = extractIntField(trimmed, "currentTokens");
                    if (ct > 0) currentTokens = ct;
                    int conv = extractIntField(trimmed, "conversationTokens");
                    if (conv > 0) conversationTokens = conv;
                    int sys = extractIntField(trimmed, "systemTokens");
                    if (sys > 0) systemTokens = sys;
                    int td = extractIntField(trimmed, "toolDefinitionsTokens");
                    if (td > 0) toolDefinitionsTokens = td;
                } else if (trimmed.contains("\"session.compaction_start\"")) {
                    int conv = extractIntField(trimmed, "conversationTokens");
                    if (conv > 0) conversationTokens = conv;
                    int sys = extractIntField(trimmed, "systemTokens");
                    if (sys > 0) systemTokens = sys;
                    int td = extractIntField(trimmed, "toolDefinitionsTokens");
                    if (td > 0) toolDefinitionsTokens = td;
                } else if (trimmed.contains("\"session.compaction_complete\"")) {
                    int pre = extractIntField(trimmed, "preCompactionTokens");
                    if (pre > 0 && currentTokens == 0) currentTokens = pre;
                }
            }
        } catch (Exception e) {
            LOG.debug("Error reading events for {}: {}", eventsFile.getFileName(), e.getMessage());
            corrupted = true;
            if (corruptionReason == null) corruptionReason = "unreadable events.jsonl";
        }

        return new SessionDiskInfo(sessionId, userMessages, assistantMessages,
                eventFileSize, diskSize, workingDirectory, firstUserMessage, model,
                currentTokens, conversationTokens, systemTokens, toolDefinitionsTokens,
                createdAt, updatedAt, corrupted, corruptionReason);
    }

    /** Simple YAML parser for workspace.yaml (key: value on each line). */
    private static Map<String, String> readWorkspaceMetadata(Path workspaceFile) {
        Map<String, String> map = new HashMap<>();
        try (var lines = Files.lines(workspaceFile)) {
            lines.forEach(line -> {
                int colon = line.indexOf(':');
                if (colon > 0 && !line.startsWith(" ") && !line.startsWith("-")) {
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    map.put(key, value);
                }
            });
        } catch (IOException e) { /* ignore */ }
        return map;
    }

    private static Instant parseInstant(String iso8601) {
        try { return Instant.parse(iso8601); } catch (Exception e) { return Instant.EPOCH; }
    }

    /** Calculate the total size of a directory tree in bytes. */
    public static long directorySize(Path dir) {
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

    private static String extractContent(String jsonLine) {
        int idx = jsonLine.indexOf("\"content\":");
        if (idx < 0) return "";
        int start = jsonLine.indexOf('"', idx + 10);
        if (start < 0) return "";
        int end = jsonLine.indexOf('"', start + 1);
        if (end < 0) return "";
        var content = jsonLine.substring(start + 1, Math.min(end, start + 81));
        return content.length() > 80 ? content.substring(0, 77) + "..." : content;
    }

    /** Extract a simple string field value from a JSON line (fast, no full parse). */
    private static String extractJsonField(String jsonLine, String field) {
        var needle = "\"" + field + "\":\"";
        int idx = jsonLine.indexOf(needle);
        if (idx < 0) return "";
        int start = idx + needle.length();
        int end = jsonLine.indexOf('"', start);
        if (end < 0) return "";
        return jsonLine.substring(start, end);
    }

    /** Extract an integer field value from a JSON line. */
    private static int extractIntField(String jsonLine, String field) {
        var needle = "\"" + field + "\":";
        int idx = jsonLine.indexOf(needle);
        if (idx < 0) return 0;
        int start = idx + needle.length();
        while (start < jsonLine.length() && jsonLine.charAt(start) == ' ') start++;
        int end = start;
        while (end < jsonLine.length() && Character.isDigit(jsonLine.charAt(end))) end++;
        if (end == start) return 0;
        try {
            return Integer.parseInt(jsonLine.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Update the summary field in workspace.yaml for a session.
     */
    public static void updateSummary(String sessionId, String newSummary) {
        var wsFile = SESSION_STORE.resolve(sessionId).resolve("workspace.yaml");
        if (!Files.isRegularFile(wsFile)) {
            LOG.debug("workspace.yaml not found for {}", sessionId);
            return;
        }
        try {
            var lines = Files.readAllLines(wsFile);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("summary:")) {
                    lines.set(i, "summary: " + newSummary);
                    found = true;
                    break;
                }
            }
            if (!found) {
                lines.add("summary: " + newSummary);
            }
            Files.write(wsFile, lines);
            LOG.info("Updated summary for session {}: {}", sessionId, newSummary);
        } catch (IOException e) {
            LOG.error("Failed to update summary for session {}", sessionId, e);
        }
    }

    /**
     * Delete a session from disk. Handles both formats:
     * - Modern: directory {@code session-state/<id>/} containing events.jsonl
     * - Legacy: single file {@code session-state/<id>.jsonl}
     * @return true if successfully deleted, false if not found or error
     */
    public static boolean deleteFromDisk(String sessionId) {
        var sessionDir = SESSION_STORE.resolve(sessionId);
        if (Files.isDirectory(sessionDir)) {
            try {
                deleteDirectoryRecursively(sessionDir);
                LOG.info("Deleted session directory from disk: {}", sessionId);
                return true;
            } catch (IOException e) {
                LOG.error("Failed to delete session {} from disk", sessionId, e);
                return false;
            }
        }
        // Legacy format: single .jsonl file
        var jsonlFile = SESSION_STORE.resolve(sessionId + ".jsonl");
        if (Files.exists(jsonlFile)) {
            try {
                Files.delete(jsonlFile);
                LOG.info("Deleted legacy session file from disk: {}", sessionId);
                return true;
            } catch (IOException e) {
                LOG.error("Failed to delete legacy session file {} from disk", sessionId, e);
                return false;
            }
        }
        LOG.debug("Session not found on disk for deletion: {}", sessionId);
        return false;
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.delete(path); }
                        catch (IOException e) { LOG.debug("Failed to delete {}", path); }
                    });
        }
    }
}
