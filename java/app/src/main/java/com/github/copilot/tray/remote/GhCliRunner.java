package com.github.copilot.tray.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Executes {@code gh agent-task} CLI commands and parses JSON output.
 */
public class GhCliRunner {

    private static final Logger LOG = LoggerFactory.getLogger(GhCliRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 30;

    private String ghPath = "gh";

    /**
     * Override the path to the gh CLI binary (default: "gh" on PATH).
     */
    public void setGhPath(String path) {
        this.ghPath = path;
    }

    /**
     * Check if gh CLI is available and authenticated.
     */
    public boolean isAvailable() {
        try {
            var result = runCommand(List.of(ghPath, "auth", "status"));
            return result.exitCode == 0;
        } catch (Exception e) {
            LOG.debug("gh CLI not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * List agent tasks with full JSON fields.
     */
    public CompletableFuture<List<AgentTask>> listTasks(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = runCommand(List.of(ghPath, "agent-task", "list",
                        "--json", AgentTask.JSON_FIELDS,
                        "--limit", String.valueOf(limit)));
                if (result.exitCode != 0) {
                    LOG.warn("gh agent-task list failed (exit {}): {}", result.exitCode, result.stderr);
                    return List.of();
                }
                return MAPPER.readValue(result.stdout, new TypeReference<List<AgentTask>>() {});
            } catch (Exception e) {
                LOG.warn("Failed to list agent tasks: {}", e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * View a single agent task by ID.
     */
    public CompletableFuture<AgentTask> viewTask(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = runCommand(List.of(ghPath, "agent-task", "view", sessionId,
                        "--json", AgentTask.JSON_FIELDS));
                if (result.exitCode != 0) {
                    LOG.warn("gh agent-task view failed (exit {}): {}", result.exitCode, result.stderr);
                    return null;
                }
                return MAPPER.readValue(result.stdout, AgentTask.class);
            } catch (Exception e) {
                LOG.warn("Failed to view agent task {}: {}", sessionId, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Get logs for an agent task (blocking, returns full text).
     */
    public CompletableFuture<String> getTaskLogs(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = runCommand(List.of(ghPath, "agent-task", "view", sessionId, "--log"), 60);
                if (result.exitCode != 0) {
                    return "Error fetching logs (exit " + result.exitCode + "): " + result.stderr;
                }
                return result.stdout;
            } catch (Exception e) {
                return "Error fetching logs: " + e.getMessage();
            }
        });
    }

    /**
     * Follow logs for an agent task (streaming). Lines are delivered to the consumer.
     * Returns the Process so it can be destroyed to stop following.
     */
    public Process followTaskLogs(String sessionId, Consumer<String> lineConsumer) throws IOException {
        var pb = new ProcessBuilder(ghPath, "agent-task", "view", sessionId, "--follow")
                .redirectErrorStream(true);
        var process = pb.start();
        Thread.ofVirtual().start(() -> {
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineConsumer.accept(line);
                }
            } catch (IOException e) {
                LOG.debug("Follow stream ended: {}", e.getMessage());
            }
        });
        return process;
    }

    /**
     * Open an agent task in the browser.
     */
    public void openInBrowser(String sessionId) {
        CompletableFuture.runAsync(() -> {
            try {
                runCommand(List.of(ghPath, "agent-task", "view", sessionId, "--web"));
            } catch (Exception e) {
                LOG.warn("Failed to open agent task in browser: {}", e.getMessage());
            }
        });
    }

    /**
     * Create a new agent task.
     */
    public CompletableFuture<Boolean> createTask(String description, String repo, String baseBranch, String customAgent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var cmd = new java.util.ArrayList<>(List.of(ghPath, "agent-task", "create", description));
                if (repo != null && !repo.isBlank()) {
                    cmd.addAll(List.of("-R", repo));
                }
                if (baseBranch != null && !baseBranch.isBlank()) {
                    cmd.addAll(List.of("-b", baseBranch));
                }
                if (customAgent != null && !customAgent.isBlank()) {
                    cmd.addAll(List.of("-a", customAgent));
                }
                var result = runCommand(cmd, 60);
                if (result.exitCode != 0) {
                    LOG.warn("gh agent-task create failed (exit {}): {}", result.exitCode, result.stderr);
                    return false;
                }
                return true;
            } catch (Exception e) {
                LOG.warn("Failed to create agent task: {}", e.getMessage());
                return false;
            }
        });
    }

    private CommandResult runCommand(List<String> command) throws IOException, InterruptedException {
        return runCommand(command, TIMEOUT_SECONDS);
    }

    private CommandResult runCommand(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        LOG.debug("Running: {}", String.join(" ", command));
        var pb = new ProcessBuilder(command).redirectErrorStream(false);
        var process = pb.start();

        var stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes()); }
            catch (IOException e) { return ""; }
        });
        var stderrFuture = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getErrorStream().readAllBytes()); }
            catch (IOException e) { return ""; }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "", "Command timed out");
        }
        return new CommandResult(process.exitValue(),
                stdoutFuture.join(), stderrFuture.join());
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}

    /**
     * Data model for a GitHub agent task returned by {@code gh agent-task list/view --json}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentTask(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("state") String state,
            @JsonProperty("repository") String repository,
            @JsonProperty("user") String user,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("completedAt") Instant completedAt,
            @JsonProperty("pullRequestNumber") Integer pullRequestNumber,
            @JsonProperty("pullRequestState") String pullRequestState,
            @JsonProperty("pullRequestTitle") String pullRequestTitle,
            @JsonProperty("pullRequestUrl") String pullRequestUrl
    ) {
        static final String JSON_FIELDS = "id,name,state,repository,user,createdAt,updatedAt,"
                + "completedAt,pullRequestNumber,pullRequestState,pullRequestTitle,pullRequestUrl";
    }
}
