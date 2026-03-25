package com.github.copilot.tray.session;

/**
 * Status of a Copilot CLI session.
 */
public enum SessionStatus {
    ACTIVE,
    IDLE,
    BUSY,
    ARCHIVED,
    ERROR,
    CORRUPTED
}
