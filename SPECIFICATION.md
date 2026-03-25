# GitHub Copilot Agentic Tray — Project Specification

## Overview

**GitHub Copilot Agentic Tray** is a cross-platform system tray application that provides real-time visibility and management of GitHub Copilot CLI sessions and remote coding agents. It gives developers instant access to session status, usage telemetry, model information, and common session actions — all from the system tray, without requiring a terminal window.

The application integrates with the [GitHub Copilot SDK for Java](https://github.com/github/copilot-sdk-java) for real-time session events, and reads session data from disk (`~/.copilot/session-state/`) for token accuracy, model detection, and offline access.

---

## Goals

- Provide a non-intrusive system tray icon with a live status summary
- Organize sessions by working directory as the primary grouping
- Allow users to see all active, archived, and corrupted sessions at a glance
- Enable key session management actions (resume, cancel, delete, rename) from the tray menu and dashboard
- Display real-time telemetry: token usage, context window breakdown, model name
- Offer a full dashboard window with sessions, usage, prune, preferences, and about tabs
- Be fully cross-platform: **Linux (x86_64, arm64), macOS (x86_64, arm64), Windows (x86_64, arm64)**

---

## Non-Goals

- Replacing the Copilot CLI terminal UX
- Implementing a chat or code editing interface
- Proxying or intercepting Copilot API traffic directly (all communication goes through the SDK/CLI)

---

## Target Platforms

| OS      | Architectures                 | Notes                                  |
|---------|-------------------------------|----------------------------------------|
| macOS   | x86_64, arm64 (Apple Silicon) | Native menu bar icon via AWT SystemTray |
| Linux   | x86_64, arm64                 | System tray via AWT                     |
| Windows | x86_64, arm64                 | System tray via AWT                     |

---

## Technology Stack

| Component         | Technology                                          |
|-------------------|-----------------------------------------------------|
| Language          | Java 25 (JPMS modules)                              |
| Build system      | Maven (multi-module)                                |
| Copilot SDK       | `com.github:copilot-sdk-java:0.1.32-java.0`        |
| System tray       | Java AWT `SystemTray` + `PopupMenu`                |
| Dashboard window  | JavaFX 25.0.1 (OpenJFX)                            |
| Dashboard tiles   | TilesFX 21.0.9                                      |
| JSON parsing      | Jackson Databind 2.19.0                              |
| Logging           | SLF4J 2.0.17                                        |
| Icons             | GitHub Copilot logo PNG at 64×64 with status dots   |

### Why Java?

The GitHub Copilot SDK for Java is the primary integration point. Java 25 with JPMS provides strong modularity. JavaFX delivers a rich cross-platform UI for the dashboard, while AWT SystemTray provides native tray integration on all platforms.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    GitHub Copilot Agentic Tray App                  │
│                                                                     │
│  ┌─────────────┐  ┌──────────────────┐  ┌───────────────────────┐  │
│  │ TrayManager │  │ Session Manager  │  │ Settings Window       │  │
│  │ (AWT menu)  │◄►│ (State + Cache)  │◄►│ (JavaFX + TilesFX)   │  │
│  └─────────────┘  └──────────────────┘  │  ├─ Sessions tab      │  │
│         │                │       │      │  ├─ Usage dashboard    │  │
│         │         ┌──────▼──┐  ┌─▼────┐ │  ├─ Prune panel       │  │
│         │         │ SDK     │  │ Disk │ │  ├─ Preferences        │  │
│         │         │ Bridge  │  │Reader│ │  └─ About              │  │
│         │         └────┬────┘  └──────┘ └───────────────────────┘  │
│         │              │                                            │
│  ┌──────▼──────┐ ┌─────▼─────┐  ┌──────────┐  ┌───────────────┐  │
│  │ Terminal    │ │  Event    │  │ Notifier │  │ Config Store  │  │
│  │ Launcher   │ │  Router   │  │ (AWT)    │  │ (JSON)        │  │
│  └─────────────┘ └───────────┘  └──────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
          │                │
          │                │ IPC (stdin/stdout/JSON-RPC)
          │         ┌──────▼──────┐
          │         │ Copilot CLI │
          │         │ (Process)   │
          │         └─────────────┘
          │
     User actions (terminal windows)
```

### Components

#### 1. TrayManager
- Manages the AWT system tray icon (GitHub Copilot logo with status dot overlays)
- Builds and rebuilds the tray context menu on session state changes
- Groups sessions by working directory as top-level submenus
- Supports 4 tray icon states: IDLE, ACTIVE, BUSY, WARNING
- Directories sorted by activity (active-session directories first)
- Limited to 15 directories and 10 sessions per directory in tray menu

#### 2. Session Manager
- Central in-memory state store for all sessions
- Receives updates from both SDK events (real-time) and disk reads (startup/fallback)
- Maintains session snapshots with status, usage, model, subagents
- Fires change listeners on any state mutation for UI refresh
- Auto-archives sessions whose last activity is older than 12 hours

#### 3. SDK Bridge
- Wraps `CopilotClient` from the Copilot SDK for Java
- Polls `listSessions()` on a configurable interval (default: 5 seconds)
- Attaches to active sessions via `resumeSession()` to receive real-time events
- Handles reconnection on disconnect
- Exposes: `listSessions()`, `attachSession()`, `detachSession()`, `deleteSession()`, `cancelSession()`

#### 4. Event Router
- Maps SDK events to `SessionManager` state mutations
- Handled events: `SessionStartEvent`, `SessionShutdownEvent`, `SessionIdleEvent`, `SessionErrorEvent`, `AssistantTurnStartEvent`, `AssistantTurnEndEvent`, `SessionUsageInfoEvent`, `SessionModelChangeEvent`, `SubagentStartedEvent`, `SubagentCompletedEvent`, `SubagentFailedEvent`, `PermissionRequestedEvent`, `PermissionCompletedEvent`

#### 5. Session Disk Reader
- Reads session data from `~/.copilot/session-state/{sessionId}/`
- Parses `events.jsonl` for token counts, model, message counts, first user message
- Parses `workspace.yaml` for working directory and summary
- Provides fallback data for sessions not actively attached via SDK
- Supports session deletion (recursive directory removal) and summary updates

#### 6. Terminal Launcher
- Opens a new terminal window to run `copilot` or `copilot --resume <id>`
- Platform-specific: `.command` file (macOS), `gnome-terminal`/`konsole`/`xterm` (Linux), `wt` (Windows)
- Sets the terminal's working directory to the session's original `cwd`

#### 7. Notifier
- Sends AWT tray notifications for session lifecycle events
- Notifications: session start, completion, error, corruption, context warning, permission request
- Respects the `notificationsEnabled` preference

#### 8. Config Store
- Loads/saves `config.json` from OS-specific config directories
- Platform paths: `~/Library/Application Support/` (macOS), `%APPDATA%\` (Windows), `$XDG_CONFIG_HOME/` (Linux)

---

## Data Model

### SessionSnapshot
```java
record SessionSnapshot(
    String id,
    String name,
    SessionStatus status,
    String model,
    boolean remote,
    Instant createdAt,
    Instant lastActivityAt,
    String workingDirectory,
    UsageSnapshot usage,
    boolean pendingPermission,
    List<SubagentSnapshot> subagents
)
```

### SessionStatus (enum)
- `ACTIVE` — session is running (initial state, or `SessionStartEvent`)
- `IDLE` — session is open but no current operation (`SessionIdleEvent`, `AssistantTurnEndEvent`)
- `BUSY` — session is processing a request (`AssistantTurnStartEvent`)
- `ARCHIVED` — session is complete or was closed (`SessionShutdownEvent`, or >12h inactive)
- `ERROR` — session encountered an error (`SessionErrorEvent`)
- `CORRUPTED` — session data is unreadable or SDK attachment failed

### UsageSnapshot
```java
record UsageSnapshot(
    int currentTokens,
    int tokenLimit,
    int conversationTokens,
    int systemTokens,
    int toolDefinitionsTokens,
    int bufferTokens,
    int messagesCount
) {
    double usagePercent();       // (currentTokens / tokenLimit) * 100
    int freeTokens();            // tokenLimit - currentTokens
}
```

### SubagentSnapshot
```java
record SubagentSnapshot(
    String id,
    String description,
    SubagentStatus status,
    Instant startedAt
)
```

### SubagentStatus (enum)
- `RUNNING`
- `COMPLETED`
- `FAILED`

---

## Session Discovery

Sessions are discovered through a hybrid approach:

### Primary: SDK Polling
1. `SdkBridge` polls `CopilotClient.listSessions()` at a fixed interval
2. Each `SessionMetadata` provides: `sessionId`, `summary`, `startTime`, `modifiedTime`, `isRemote`, `context` (working directory)
3. New sessions are registered with `SessionManager`

### Enrichment: Disk Reading
4. For each new session, `SessionDiskReader.readStats()` reads `~/.copilot/session-state/{sessionId}/`
5. Disk data supplements SDK metadata with: working directory, model, token counts, message counts, first user message
6. Disk data is preferred for model and token information (more accurate than SDK metadata alone)

### Attachment: Live Events
7. Active sessions are attached via `SdkBridge.attachSession()` using `resumeSession()`
8. Attached sessions receive real-time events (usage, model changes, turn start/end, etc.)
9. Sessions that fail to attach are marked CORRUPTED

### Archival
10. Sessions whose `lastModifiedTime` is older than 12 hours are auto-archived on startup

---

## Token Counting

Token data is sourced through a multi-tier fallback chain:

### Tier 1: Live SDK Events (best)
For attached sessions, `SessionUsageInfoEvent` provides:
- `currentTokens` — tokens currently in context
- `tokenLimit` — maximum context window size
- `messagesLength` — number of messages

Breakdown is estimated: buffer ≈ 20% of limit, system/tools ≈ 30% of used, messages ≈ 70% of used.

### Tier 2: Disk Event Data (good)
`SessionDiskReader` scans `events.jsonl` for real token data from:
- `session.shutdown` events → `currentTokens`, `conversationTokens`, `systemTokens`, `toolDefinitionsTokens`
- `session.compaction_start` events → `conversationTokens`, `systemTokens`, `toolDefinitionsTokens`
- `session.compaction_complete` events → `preCompactionTokens`

Token limit is standardized to 200,000 (Copilot CLI default context window).

### Tier 3: Message Count Heuristic (fallback)
For sessions with no shutdown/compaction events:
- `estimatedTokens = (userMessages + assistantMessages) × 800`
- Breakdown: buffer = 20% of limit, system/tools = 30% of estimated, messages = 70%

### Important
File size of `events.jsonl` is **not** used for token estimation — the file is ~70% tool output data (file reads, etc.) that doesn't count toward context tokens.

---

## Model Detection

Model is detected from multiple sources, in priority order:

1. **Live SDK event**: `SessionModelChangeEvent` provides real-time model updates
2. **Disk events**: `SessionDiskReader` scans `events.jsonl` for:
   - `session.model_change` → `newModel` field
   - `session.resume` → `selectedModel` field
   - `session.shutdown` → `currentModel` field
3. **Fallback**: `"unknown"` if no model data is found

The last model event found in the file is used (most recent).

---

## System Tray Menu Structure

The tray menu is rebuilt dynamically whenever session state changes. Sessions are grouped by working directory.

```
┌─────────────────────────────────────────────────┐
│  📊 Dashboard                                    │
│  ─────────────────────────────────────────────  │
│  ▸ ~/work/my-project                            │
│    ├─ 📝 my-feature [claude-sonnet-4.6]         │
│    │    ├─ BUSY — 42% context                   │
│    │    ├─ Resume in Terminal                    │
│    │    ├─ Cancel                                │
│    │    └─ Delete                                │
│    └─ 📝 fix-bug [gpt-5.2]                     │
│         ├─ IDLE — 18% context                    │
│         ├─ Resume in Terminal                    │
│         └─ Delete                                │
│  ▸ ~/work/other-project                         │
│    └─ 🗃 old-session                            │
│         └─ Delete                                │
│  ─────────────────────────────────────────────  │
│  ▸ Usage Summary                                │
│    ├─ Tokens: 12,345 / 200,000                  │
│    └─ Sessions: 3                                │
│  ─────────────────────────────────────────────  │
│  🔄 New Session                                 │
│  ─────────────────────────────────────────────  │
│  ✕  Quit                                        │
└─────────────────────────────────────────────────┘
```

### Directory Sorting
- Directories with active sessions appear first
- Then sorted by most recent `lastActivityAt`
- Maximum 15 directories shown; overflow gets a "View All Directories…" link

### Session Sorting (within directory)
1. Active/Idle/Busy sessions first
2. Archived sessions next
3. Corrupted sessions last
4. Within each group, sorted by most recent activity

### Per-Session Submenu
- Disabled status line: status name + context % (if known) + "Remote" flag
- Subagent count (if any)
- Permission pending indicator (if waiting)
- Actions: Resume in Terminal (not for CORRUPTED), Cancel (only for BUSY), Delete

### Tray Icon States

| State     | Icon                                                          |
|-----------|---------------------------------------------------------------|
| Idle      | GitHub Copilot logo (no status dot)                           |
| Active    | Copilot logo + green dot (at least one non-archived session)  |
| Busy      | Copilot logo + orange dot (at least one BUSY session)         |
| Warning   | Copilot logo + red dot (ERROR session or context ≥80% full)   |

Icon state priority: WARNING > BUSY > ACTIVE > IDLE.

---

## Dashboard Window

Accessible from the tray menu's "Dashboard" item. A JavaFX `TabPane` with 5 tabs:

### Tab: Sessions

Directory-first master-detail layout:

- **Top bar**: Local / Remote toggle
- **Left pane**: Directory `ListView`
  - Each entry shows: `<shortened path>  [session count]`
  - Badges: `●` = has active sessions, `⚠` = has corrupted sessions
- **Right pane**: Session `TableView` (multi-select enabled)
  - Columns: Name, Model, Status, Usage %, Messages, Created
  - Standard multi-select: Cmd/Ctrl+A, Shift+click, Cmd/Ctrl+click
- **Detail panel** (below table):
  - Single selection: full session details (ID, name, model, directory, status, tokens, messages, subagents)
  - Multi-selection: aggregate totals (session count, total tokens, combined usage, status breakdown)
- **Action bar**: Resume in Terminal, Rename, Cancel, Delete
  - Delete operates on all selected sessions with a single confirmation dialog

### Tab: Usage

TilesFX dashboard with directory filtering:

- **Directory filter**: ComboBox with "All Directories" + per-directory options
- **Session table**: Name, Model, Status, Location, Tokens, Usage %, Messages, Directory
- **Selected session tiles**:
  - Context Window (donut chart)
  - Context Used (gauge)
  - Tokens Used (number)
  - Model (text)
  - Status (text)
- **Context breakdown tiles**:
  - System / Tools tokens
  - Message tokens
  - Free Space
  - Buffer
- **Aggregate tiles** (filtered by directory):
  - Total Sessions
  - Active Sessions
  - Total Tokens

### Tab: Prune

Session cleanup with categorized candidates:

- **Scan button** + "Include trivial sessions" checkbox
- **Category legend**: explains EMPTY, ABANDONED, TRIVIAL, CORRUPTED criteria
- **View toggle**: Flat table / Tree view (by directory, default)
- **Selection**: Per-row checkboxes, select all, select by category, shift-click range
- **Confirm delete** button with freed space summary

Prune categories:
| Category    | Criteria                                               |
|-------------|--------------------------------------------------------|
| EMPTY       | No `events.jsonl` or zero user messages                |
| ABANDONED   | Has user messages but zero assistant responses          |
| TRIVIAL     | ≤5 user messages (optional, controlled by checkbox)    |
| CORRUPTED   | Unreadable/malformed event data or workspace metadata   |

### Tab: Preferences

| Setting                      | Default    | Notes                            |
|------------------------------|------------|----------------------------------|
| Copilot CLI Path             | (auto)     | Path to `copilot` binary         |
| Poll Interval (seconds)      | 5          | SDK session list poll frequency   |
| Context Warning Threshold (%)| 80         | Triggers WARNING icon + notification |
| Enable Notifications         | true       | OS-level tray notifications       |
| Auto-Start on Login          | false      | Launch at system startup          |

Additional settings stored but not yet operational: `theme`, `logLevel`.

### Tab: About

Displays: app name, version, license, description, SDK version, JDK version, OS/architecture, and a link to the GitHub repository.

---

## Session Actions

### Resume in Terminal
- Opens a terminal window and runs `copilot --resume <sessionId>`
- Terminal opens in the session's original working directory
- Platform-specific:
  - **macOS**: Creates a `.command` script with `cd <dir>` + `copilot --resume <id>`, opens with `open`; fallback: `osascript` to Terminal.app
  - **Linux**: Tries `gnome-terminal`, `konsole`, `xterm`, `x-terminal-emulator` with `cd <dir> && copilot --resume <id>`
  - **Windows**: Uses `wt --startingDirectory <dir> copilot --resume <id>`

### Rename Session
- Prompts user with a `TextInputDialog` pre-filled with current name
- Updates `SessionManager` in-memory and persists to `workspace.yaml` `summary:` field

### Cancel Session
- Only available for BUSY sessions
- Calls `SdkBridge.cancelSession()` which invokes `session.abort()` on the attached SDK session
- Does not delete session data

### Delete Session
- Available for all session states
- Prompts for confirmation (batch confirmation when multi-selecting)
- Performs: SDK `deleteSession()` → disk `deleteFromDisk()` → `SessionManager.removeSession()`
- Disk deletion is recursive removal of `~/.copilot/session-state/{sessionId}/`

### New Session
- Opens a new terminal window and launches `copilot` (no flags)

---

## Events & Telemetry

The SDK bridge subscribes to events via `CopilotSession.on(EventClass, handler)` for attached sessions. The Event Router maps these to SessionManager mutations:

### Handled Events

| SDK Event                    | SessionManager Action                          |
|------------------------------|------------------------------------------------|
| `SessionStartEvent`         | Set status → ACTIVE                            |
| `SessionShutdownEvent`      | Archive session                                |
| `SessionIdleEvent`          | Set status → IDLE                              |
| `SessionErrorEvent`         | Set status → ERROR                             |
| `AssistantTurnStartEvent`   | Set status → BUSY                              |
| `AssistantTurnEndEvent`     | Set status → IDLE                              |
| `SessionUsageInfoEvent`     | Update token usage (currentTokens, tokenLimit, messagesLength) |
| `SessionModelChangeEvent`   | Update model name                              |
| `SubagentStartedEvent`      | Add subagent to session                        |
| `SubagentCompletedEvent`    | Mark subagent COMPLETED                        |
| `SubagentFailedEvent`       | Mark subagent FAILED                           |
| `PermissionRequestedEvent`  | Set pending permission flag                    |
| `PermissionCompletedEvent`  | Clear pending permission flag                  |

### SDK APIs Used

```java
// CopilotClient
client.start()                          // Connect to running Copilot CLI
client.stop()                           // Graceful disconnect
client.listSessions()                   // List<SessionMetadata>
client.resumeSession(id, config)        // CopilotSession (attach for events)
client.deleteSession(id)                // Remove session
client.listModels()                     // List<ModelInfo> (available, not yet used)

// CopilotSession
session.on(EventClass, handler)         // Subscribe to typed events
session.abort()                         // Cancel current operation
session.close()                         // Detach from session
```

### Disk Event Types Parsed

From `events.jsonl`:
| Event Type               | Fields Extracted                                              |
|--------------------------|---------------------------------------------------------------|
| `user.message`           | Count; first message content (truncated to 80 chars)          |
| `assistant.message`      | Count                                                         |
| `session.shutdown`       | `currentTokens`, `conversationTokens`, `systemTokens`, `toolDefinitionsTokens`, `currentModel` |
| `session.compaction_start`| `conversationTokens`, `systemTokens`, `toolDefinitionsTokens` |
| `session.compaction_complete` | `preCompactionTokens`                                    |
| `session.model_change`   | `newModel`                                                    |
| `session.resume`         | `selectedModel`                                               |

---

## Notifications (OS-Level)

Sent via AWT `TrayIcon.displayMessage()`:

| Trigger                            | Notification                                     |
|------------------------------------|--------------------------------------------------|
| Session started                    | "📝 Session 'name' started (model)"              |
| Session completed                  | "✅ Session 'name' completed"                    |
| Context ≥ warning threshold        | "⚠️ Session 'name' context window is N% full"   |
| Session error                      | "❌ Session 'name' encountered an error"         |
| Session corrupted                  | "⚠️ Session 'name' data is corrupted"           |
| Permission requested               | "🔐 Session 'name' is waiting for permission"   |

Notifications can be disabled in Preferences.

---

## Configuration & Persistence

Configuration is stored in a platform-standard location:

| OS      | Path                                                       |
|---------|------------------------------------------------------------|
| macOS   | `~/Library/Application Support/copilot-agentic-tray/config.json` |
| Linux   | `${XDG_CONFIG_HOME:-~/.config}/copilot-agentic-tray/config.json` |
| Windows | `%APPDATA%\copilot-agentic-tray\config.json`               |

Session data is read from the Copilot CLI's own session store:
- `~/.copilot/session-state/{sessionId}/`
  - `events.jsonl` — session event log
  - `workspace.yaml` — session metadata (summary, cwd)
  - `checkpoints/` — session checkpoints
  - `files/` — session artifacts

---

## Build & Distribution

### Current Build
- Maven multi-module project (parent + app)
- JPMS module: `com.github.copilot.tray`
- Dependencies copied to `target/mods/` for modular classpath
- Run via `mvn javafx:run` or `java --module-path` command

### Planned: Native Packaging (jlink + jpackage)
- jlink custom runtime image
- jpackage native installers per platform:

| Target         | Installer Format | Output                                          |
|----------------|------------------|--------------------------------------------------|
| macOS x86_64   | `.dmg`           | `GitHub Copilot Agentic Tray-x.y.z.dmg`         |
| macOS arm64    | `.dmg`           | `GitHub Copilot Agentic Tray-x.y.z.dmg`         |
| Linux x86_64   | `.deb`           | `copilot-agentic-tray_x.y.z_amd64.deb`          |
| Linux arm64    | `.deb`           | `copilot-agentic-tray_x.y.z_arm64.deb`          |
| Windows x86_64 | `.msi`           | `GitHub Copilot Agentic Tray-x.y.z.msi`         |

---

## Security Considerations

- The app uses the user's existing `gh` / `GH_TOKEN` credentials already configured for the Copilot CLI
- No credentials are stored by the tray app itself
- The SDK communicates with the CLI process via local IPC; no outbound network calls are made by the tray app
- The dashboard window does not expose or display tokens/credentials
- The app runs with the least necessary privileges (no root required)

---

## Accessibility

- All tray menu items have descriptive labels
- The dashboard window is fully navigable via keyboard (tab order, shortcuts)
- Multi-select uses standard OS conventions (Cmd+A, Shift+click, Cmd/Ctrl+click)
- High-DPI / Retina display support via JavaFX scaling

---

## Open Questions / Future Considerations

- **Premium requests**: The SDK's `SessionUsageInfoEvent` exposes token data; premium request quota may require a separate GitHub API call — to be investigated
- **Read-only session observation**: Currently, attaching to a session via `resumeSession()` may conflict with an active CLI user; a read-only observe mode would be ideal
- **Fleet mode**: Copilot CLI's `/fleet` command spawns parallel subagents; these should appear as child items under their parent session
- **Dark mode icon**: Provide both light and dark tray icons, and auto-select based on OS theme
- **TerminalLauncher CLI path**: Currently hardcodes `copilot`; should respect `AppConfig.cliPath` preference
- **Localization**: English-only for initial releases; i18n infrastructure to be added later
- **Historical usage persistence**: Track token/session usage over time for trend charts
