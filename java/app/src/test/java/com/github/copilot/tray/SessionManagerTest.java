package com.github.copilot.tray;

import com.github.copilot.tray.session.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void addAndRetrieveSession() {
        var manager = new SessionManager();
        manager.addSession("test-1", "claude-sonnet-4.5", "/tmp/project");

        var session = manager.getSession("test-1");
        assertNotNull(session);
        assertEquals("test-1", session.id());
        assertEquals("claude-sonnet-4.5", session.model());
        assertEquals(SessionStatus.ACTIVE, session.status());
    }

    @Test
    void archiveSession() {
        var manager = new SessionManager();
        manager.addSession("test-2", "gpt-5", "/tmp");
        manager.archiveSession("test-2");

        assertEquals(SessionStatus.ARCHIVED, manager.getSession("test-2").status());
    }

    @Test
    void updateUsage() {
        var manager = new SessionManager();
        manager.addSession("test-3", "gpt-5", "/tmp");
        manager.updateUsage("test-3", 5000, 100000, 42);

        var usage = manager.getSession("test-3").usage();
        assertEquals(5000, usage.currentTokens());
        assertEquals(100000, usage.tokenLimit());
        assertEquals(42, usage.messagesCount());
        assertEquals(5.0, usage.tokenUsagePercent(), 0.01);
    }

    @Test
    void changeListenerFires() {
        var manager = new SessionManager();
        var callCount = new AtomicInteger(0);
        manager.addChangeListener(sessions -> callCount.incrementAndGet());

        manager.addSession("test-4", "claude", "/tmp");
        assertEquals(1, callCount.get());

        manager.setStatus("test-4", SessionStatus.BUSY);
        assertEquals(2, callCount.get());
    }

    @Test
    void subagentTracking() {
        var manager = new SessionManager();
        manager.addSession("test-5", "claude", "/tmp");
        manager.addSubagent("test-5", "sub-1", "Explore codebase");

        var session = manager.getSession("test-5");
        assertEquals(1, session.subagents().size());
        assertEquals("sub-1", session.subagents().getFirst().id());
        assertEquals(SubagentStatus.RUNNING, session.subagents().getFirst().status());

        manager.updateSubagent("test-5", "sub-1", SubagentStatus.COMPLETED);
        assertEquals(SubagentStatus.COMPLETED,
                manager.getSession("test-5").subagents().getFirst().status());
    }

    @Test
    void usageSnapshotPercentage() {
        assertEquals(50.0, UsageSnapshot.fromSdk(50000, 100000, 10).tokenUsagePercent(), 0.01);
        assertEquals(0.0, UsageSnapshot.fromSdk(0, 0, 0).tokenUsagePercent(), 0.01);
        assertEquals(100.0, UsageSnapshot.fromSdk(200000, 200000, 5).tokenUsagePercent(), 0.01);
    }

    @Test
    void usageSnapshotBreakdown() {
        // Simulates 90k/200k with 20% buffer
        var u = UsageSnapshot.fromSdk(90000, 200000, 25);
        assertEquals(90000, u.currentTokens());
        assertEquals(200000, u.tokenLimit());
        assertEquals(25, u.messagesCount());
        // Buffer should be ~20% of limit
        assertEquals(40000, u.bufferTokens());
        // System/tools ~30% of used, messages ~70% of used
        assertEquals(27000, u.systemToolsTokens());
        assertEquals(63000, u.messagesTokens());
        // Free space = limit - used - buffer
        assertEquals(70000, u.freeSpaceTokens());
        // Percentages
        assertEquals(45.0, u.tokenUsagePercent(), 0.01);
        assertEquals(35.0, u.freeSpacePercent(), 0.01);
        assertEquals(20.0, u.bufferPercent(), 0.01);
    }

    @Test
    void removeSession() {
        var manager = new SessionManager();
        manager.addSession("test-6", "gpt-5", "/tmp");
        assertNotNull(manager.getSession("test-6"));

        manager.removeSession("test-6");
        assertNull(manager.getSession("test-6"));
    }

    @Test
    void recentSessionStaysActive() {
        var manager = new SessionManager();
        manager.populateFromMetadata("recent-1", "Recent Session", "claude", "/tmp",
                Instant.now().minus(2, ChronoUnit.HOURS), false);

        assertEquals(SessionStatus.ACTIVE, manager.getSession("recent-1").status());
        assertFalse(manager.getSession("recent-1").remote());
    }

    @Test
    void oldSessionIsArchived() {
        var manager = new SessionManager();
        manager.populateFromMetadata("old-1", "Old Session", "gpt-5", "/tmp",
                Instant.now().minus(13, ChronoUnit.HOURS), false);

        assertEquals(SessionStatus.ARCHIVED, manager.getSession("old-1").status());
    }

    @Test
    void sessionAtExactly12HoursStaysActive() {
        var manager = new SessionManager();
        manager.populateFromMetadata("edge-1", "Edge Session", "claude", "/tmp",
                Instant.now().minus(12, ChronoUnit.HOURS).plus(1, ChronoUnit.MINUTES), false);

        assertEquals(SessionStatus.ACTIVE, manager.getSession("edge-1").status());
    }

    @Test
    void remoteSessionFlag() {
        var manager = new SessionManager();
        manager.populateFromMetadata("remote-1", "Remote Session", "claude", "/tmp",
                Instant.now(), true);

        assertTrue(manager.getSession("remote-1").remote());
        assertEquals(SessionStatus.ACTIVE, manager.getSession("remote-1").status());
    }
}
