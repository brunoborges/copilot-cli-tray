package com.github.copilot.tray.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * Stats read from disk for a single session.
     */
    public record DiskStats(
            int userMessages,
            int assistantMessages,
            long fileSizeBytes,
            String workingDirectory,
            String firstUserMessage,
            String model,
            int currentTokens,
            int conversationTokens,
            int systemTokens,
            int toolDefinitionsTokens
    ) {
        public int totalMessages() { return userMessages + assistantMessages; }

        /** Build a UsageSnapshot from real token data when available, heuristic fallback. */
        public UsageSnapshot toUsageSnapshot() {
            if (currentTokens > 0) {
                // Real token data from session events
                int limit = DEFAULT_TOKEN_LIMIT;
                int sysTools = systemTokens + toolDefinitionsTokens;
                int msgs = conversationTokens;
                int buffer = Math.max(0, limit - currentTokens);
                return new UsageSnapshot(currentTokens, limit, userMessages + assistantMessages,
                        sysTools, msgs, buffer);
            }
            if (conversationTokens > 0) {
                // Partial data from compaction events
                int total = conversationTokens + systemTokens + toolDefinitionsTokens;
                int limit = DEFAULT_TOKEN_LIMIT;
                int buffer = Math.max(0, limit - total);
                return new UsageSnapshot(total, limit, userMessages + assistantMessages,
                        systemTokens + toolDefinitionsTokens, conversationTokens, buffer);
            }
            // No real token data — use message-count heuristic (~800 tokens/message pair)
            int estimatedTokens = (userMessages + assistantMessages) * 800;
            int limit = DEFAULT_TOKEN_LIMIT;
            int buffer = (int) (limit * 0.20);
            int sysTools = (int) (estimatedTokens * 0.30);
            int msgs = estimatedTokens - sysTools;
            return new UsageSnapshot(estimatedTokens, limit, userMessages + assistantMessages,
                    sysTools, msgs, buffer);
        }

        public static final DiskStats EMPTY = new DiskStats(0, 0, 0, "", "", "", 0, 0, 0, 0);
    }

    /**
     * Read stats for a session from its on-disk directory.
     */
    public static DiskStats readStats(String sessionId) {
        return readStats(SESSION_STORE.resolve(sessionId));
    }

    /**
     * Read stats for a session from a given directory.
     */
    public static DiskStats readStats(Path sessionDir) {
        var eventsFile = sessionDir.resolve("events.jsonl");
        var workspaceFile = sessionDir.resolve("workspace.yaml");

        String workingDirectory = "";
        if (Files.exists(workspaceFile)) {
            workingDirectory = readWorkspaceField(workspaceFile, "cwd");
        }

        if (!Files.exists(eventsFile)) {
            return new DiskStats(0, 0, 0, workingDirectory, "", "", 0, 0, 0, 0);
        }

        long fileSize = 0;
        try {
            fileSize = Files.size(eventsFile);
        } catch (IOException e) {
            LOG.debug("Cannot read file size for {}", eventsFile);
        }

        int userMessages = 0;
        int assistantMessages = 0;
        String firstUserMessage = "";
        String model = "";
        int currentTokens = 0;
        int conversationTokens = 0;
        int systemTokens = 0;
        int toolDefinitionsTokens = 0;

        try (var lines = Files.lines(eventsFile)) {
            for (var line : (Iterable<String>) lines::iterator) {
                if (line.contains("\"user.message\"")) {
                    userMessages++;
                    if (userMessages == 1) {
                        firstUserMessage = extractContent(line);
                    }
                } else if (line.contains("\"assistant.message\"")) {
                    assistantMessages++;
                } else if (line.contains("\"session.model_change\"")) {
                    var m = extractJsonField(line, "newModel");
                    if (!m.isEmpty()) model = m;
                } else if (line.contains("\"session.resume\"")) {
                    var m = extractJsonField(line, "selectedModel");
                    if (!m.isEmpty()) model = m;
                } else if (line.contains("\"session.shutdown\"")) {
                    var m = extractJsonField(line, "currentModel");
                    if (!m.isEmpty()) model = m;
                    int ct = extractIntField(line, "currentTokens");
                    if (ct > 0) currentTokens = ct;
                    int conv = extractIntField(line, "conversationTokens");
                    if (conv > 0) conversationTokens = conv;
                    int sys = extractIntField(line, "systemTokens");
                    if (sys > 0) systemTokens = sys;
                    int td = extractIntField(line, "toolDefinitionsTokens");
                    if (td > 0) toolDefinitionsTokens = td;
                } else if (line.contains("\"session.compaction_start\"")) {
                    int conv = extractIntField(line, "conversationTokens");
                    if (conv > 0) conversationTokens = conv;
                    int sys = extractIntField(line, "systemTokens");
                    if (sys > 0) systemTokens = sys;
                    int td = extractIntField(line, "toolDefinitionsTokens");
                    if (td > 0) toolDefinitionsTokens = td;
                } else if (line.contains("\"session.compaction_complete\"")) {
                    int pre = extractIntField(line, "preCompactionTokens");
                    if (pre > 0 && currentTokens == 0) currentTokens = pre;
                }
            }
        } catch (IOException e) {
            LOG.debug("Error reading events for {}: {}", sessionDir.getFileName(), e.getMessage());
        }

        return new DiskStats(userMessages, assistantMessages, fileSize,
                workingDirectory, firstUserMessage, model,
                currentTokens, conversationTokens, systemTokens, toolDefinitionsTokens);
    }

    private static String readWorkspaceField(Path workspaceFile, String field) {
        try (var lines = Files.lines(workspaceFile)) {
            var prefix = field + ": ";
            return lines.filter(l -> l.startsWith(prefix))
                    .map(l -> l.substring(prefix.length()).trim())
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            return "";
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
        // Skip whitespace
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
     * Delete a session's directory from disk.
     * @return true if successfully deleted, false if not found or error
     */
    public static boolean deleteFromDisk(String sessionId) {
        var sessionDir = SESSION_STORE.resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            LOG.debug("Session directory not found for deletion: {}", sessionId);
            return false;
        }
        try {
            deleteDirectoryRecursively(sessionDir);
            LOG.info("Deleted session from disk: {}", sessionId);
            return true;
        } catch (IOException e) {
            LOG.error("Failed to delete session {} from disk", sessionId, e);
            return false;
        }
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
