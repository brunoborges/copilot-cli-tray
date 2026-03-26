package com.github.copilot.tray.session;

/**
 * Immutable snapshot of token/context usage for a session.
 * Models the context window breakdown:
 * System/Tools + Messages + Available = Token Limit.
 *
 * @param currentTokens    total tokens consumed (system/tools + messages)
 * @param tokenLimit       maximum context window size
 * @param messagesCount    number of conversation messages
 * @param systemToolsTokens tokens used by system prompt and tool definitions
 * @param messagesTokens   tokens used by conversation messages
 */
public record UsageSnapshot(
        int currentTokens,
        int tokenLimit,
        int messagesCount,
        int userMessagesCount,
        int assistantMessagesCount,
        int systemToolsTokens,
        int messagesTokens
) {
    /** Token usage as a percentage (0–100). */
    public double tokenUsagePercent() {
        if (tokenLimit <= 0) return 0.0;
        return (currentTokens * 100.0) / tokenLimit;
    }

    /** Available = limit − used. */
    public int availableTokens() {
        return Math.max(0, tokenLimit - currentTokens);
    }

    /** Available as a percentage of the limit. */
    public double availablePercent() {
        if (tokenLimit <= 0) return 0.0;
        return (availableTokens() * 100.0) / tokenLimit;
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

    /**
     * Create a UsageSnapshot from SDK data, estimating the breakdown.
     * The SDK provides total currentTokens but not the system/messages split.
     * We split the used tokens proportionally (30% system, 70% messages).
     */
    public static UsageSnapshot fromSdk(int currentTokens, int tokenLimit, int messagesCount) {
        int systemTools = (int) (currentTokens * 0.30);
        int messages = currentTokens - systemTools;
        return new UsageSnapshot(currentTokens, tokenLimit, messagesCount, 0, 0,
                systemTools, messages);
    }

    /** An empty usage snapshot used as default. */
    public static final UsageSnapshot EMPTY = new UsageSnapshot(0, 0, 0, 0, 0, 0, 0);
}
