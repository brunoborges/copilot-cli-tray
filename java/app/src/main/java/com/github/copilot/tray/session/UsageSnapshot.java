package com.github.copilot.tray.session;

/**
 * Immutable snapshot of token/context usage for a session.
 * Models the context window breakdown shown by {@code /context}:
 * System/Tools + Messages + Free Space + Buffer = Token Limit.
 *
 * @param currentTokens    total tokens consumed (system/tools + messages)
 * @param tokenLimit       maximum context window size
 * @param messagesCount    number of conversation messages
 * @param systemToolsTokens tokens used by system prompt and tool definitions
 * @param messagesTokens   tokens used by conversation messages
 * @param bufferTokens     reserved buffer tokens (cannot be used)
 */
public record UsageSnapshot(
        int currentTokens,
        int tokenLimit,
        int messagesCount,
        int systemToolsTokens,
        int messagesTokens,
        int bufferTokens
) {
    /** Token usage as a percentage (0–100). */
    public double tokenUsagePercent() {
        if (tokenLimit <= 0) return 0.0;
        return (currentTokens * 100.0) / tokenLimit;
    }

    /** Free space = limit − used − buffer. */
    public int freeSpaceTokens() {
        return Math.max(0, tokenLimit - currentTokens - bufferTokens);
    }

    /** Free space as a percentage of the limit. */
    public double freeSpacePercent() {
        if (tokenLimit <= 0) return 0.0;
        return (freeSpaceTokens() * 100.0) / tokenLimit;
    }

    /** System/tools as a percentage of the limit. */
    public double systemToolsPercent() {
        if (tokenLimit <= 0) return 0.0;
        return (systemToolsTokens * 100.0) / tokenLimit;
    }

    /** Messages as a percentage of the limit. */
    public double messagesPercent() {
        if (tokenLimit <= 0) return 0.0;
        return (messagesTokens * 100.0) / tokenLimit;
    }

    /** Buffer as a percentage of the limit. */
    public double bufferPercent() {
        if (tokenLimit <= 0) return 0.0;
        return (bufferTokens * 100.0) / tokenLimit;
    }

    /**
     * Create a UsageSnapshot from SDK data, estimating the breakdown.
     * The SDK provides total currentTokens but not the system/messages split.
     * We estimate buffer at ~20% of the limit (matching observed CLI behavior)
     * and split the used tokens proportionally.
     */
    public static UsageSnapshot fromSdk(int currentTokens, int tokenLimit, int messagesCount) {
        int buffer = (int) (tokenLimit * 0.20);
        // Estimate: system/tools is roughly 30% of used tokens, messages is 70%
        int systemTools = (int) (currentTokens * 0.30);
        int messages = currentTokens - systemTools;
        return new UsageSnapshot(currentTokens, tokenLimit, messagesCount,
                systemTools, messages, buffer);
    }

    /** An empty usage snapshot used as default. */
    public static final UsageSnapshot EMPTY = new UsageSnapshot(0, 0, 0, 0, 0, 0);
}
