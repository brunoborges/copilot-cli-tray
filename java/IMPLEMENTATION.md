# Copilot CLI Tray — Java Implementation Guide

This document describes the concrete technical implementation of Copilot CLI Tray: project structure, dependencies, module system, build pipeline, packaging, and CI/CD.

---

## Technology Choices

| Concern              | Choice                              | Rationale                                                   |
|----------------------|-------------------------------------|-------------------------------------------------------------|
| JDK version          | **JDK 25**                          | Latest release; Virtual Threads stable, modern APIs         |
| UI toolkit           | **JavaFX 25** (OpenJFX)             | Cross-platform, modern look, Scene Builder support          |
| Build tool           | **Maven 3.9+**                      | Consistent with `copilot-sdk-java`, wide toolchain support  |
| Runtime packaging    | **`jlink`** (custom runtime image)  | Produces minimal JRE; no full JDK required on end-user machine |
| Installer packaging  | **`jpackage`** (bundled in JDK 14+) | Produces native installers per platform                     |
| System tray          | **Dorkbox SystemTray**              | Cross-platform tray support beyond AWT limitations          |
| Copilot integration  | **`copilot-sdk-java`**              | Official Java SDK for Copilot CLI                           |

### Why `jlink` over GraalVM Native Image?

- `jlink` is stable, officially part of the JDK, and works seamlessly with JavaFX
- JavaFX native image support with GraalVM requires complex reflection config and Gluon substrates
- `jlink` + `jpackage` produces small, self-contained app bundles (typically 60–80 MB) with native launcher
- JavaFX's module graph is well-suited for `jlink`'s tree-shaking

---

## Project Structure

```
java/
├── pom.xml                              # Root POM (parent)
├── IMPLEMENTATION.md                    # This file
├── app/                                 # Main application module
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/
│       │   │   └── com/github/copilot/tray/
│       │   │       ├── Main.java                    # Entry point
│       │   │       ├── TrayApplication.java         # Lifecycle manager
│       │   │       ├── tray/
│       │   │       │   ├── TrayManager.java         # Dorkbox tray integration
│       │   │       │   ├── TrayMenuBuilder.java     # Dynamic menu construction
│       │   │       │   └── TrayIconState.java       # Icon state enum
│       │   │       ├── session/
│       │   │       │   ├── SessionManager.java      # Session state + lifecycle
│       │   │       │   ├── SessionSnapshot.java     # Immutable session value object
│       │   │       │   ├── SessionStatus.java       # Status enum
│       │   │       │   ├── UsageSnapshot.java       # Token/usage value object
│       │   │       │   └── SubagentSnapshot.java    # Subagent value object
│       │   │       ├── sdk/
│       │   │       │   ├── SdkBridge.java           # CopilotClient wrapper
│       │   │       │   ├── EventRouter.java         # Routes SDK events to SessionManager
│       │   │       │   └── TerminalLauncher.java    # Opens terminal for /resume
│       │   │       ├── ui/
│       │   │       │   ├── SettingsApp.java         # JavaFX Application subclass
│       │   │       │   ├── SettingsController.java  # Main window controller
│       │   │       │   └── SessionDetailController.java
│       │   │       ├── config/
│       │   │       │   ├── AppConfig.java           # Config model (Jackson)
│       │   │       │   └── ConfigStore.java         # Read/write config.json
│       │   │       └── notify/
│       │   │           └── Notifier.java            # OS notification abstraction
│       │   ├── resources/
│       │   │   ├── com/github/copilot/tray/ui/
│       │   │   │   ├── settings.fxml
│       │   │   │   └── session-detail.fxml
│       │   │   └── icons/
│       │   │       ├── tray-idle.png               # 22x22, 44x44 (HiDPI)
│       │   │       ├── tray-active.png
│       │   │       ├── tray-busy.png
│       │   │       └── tray-warning.png
│       │   └── module-info.java                    # JPMS module descriptor
│       └── test/
│           └── java/
│               └── com/github/copilot/tray/
│                   ├── SessionManagerTest.java
│                   └── SdkBridgeTest.java
└── dist/
    ├── macos/
    │   ├── entitlements.plist                      # macOS code signing entitlements
    │   └── Info.plist.template                     # Bundle metadata template
    ├── windows/
    │   └── app.ico                                 # Windows icon (multi-res ICO)
    └── linux/
        ├── copilot-cli-tray.desktop                # XDG desktop entry
        └── app.png                                 # 256x256 Linux icon
```

---

## JPMS Module Declaration

```java
// app/src/main/java/module-info.java
module com.github.copilot.tray {
    requires com.github.copilot.sdk;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires com.fasterxml.jackson.databind;
    requires dorkbox.systemTray;
    requires java.desktop;          // java.awt.SystemTray, Desktop, Toolkit
    requires java.logging;
    requires java.prefs;

    opens com.github.copilot.tray.ui to javafx.fxml;
    opens com.github.copilot.tray.config to com.fasterxml.jackson.databind;
    opens com.github.copilot.tray.session to com.fasterxml.jackson.databind;

    exports com.github.copilot.tray;
}
```

---

## Root POM (`java/pom.xml`)

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.github.copilot</groupId>
  <artifactId>copilot-cli-tray-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>Copilot CLI Tray (Parent)</name>
  <description>Cross-platform system tray for GitHub Copilot CLI sessions</description>
  <url>https://github.com/brunoborges/copilot-cli-tray</url>

  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/licenses/MIT</url>
    </license>
  </licenses>

  <modules>
    <module>app</module>
  </modules>

  <properties>
    <java.version>25</java.version>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <!-- Dependency versions -->
    <javafx.version>25</javafx.version>
    <copilot.sdk.version>0.1.32-java.0</copilot.sdk.version>
    <dorkbox.systemtray.version>4.4</dorkbox.systemtray.version>
    <jackson.version>2.18.2</jackson.version>
    <slf4j.version>2.0.16</slf4j.version>
    <junit.version>5.11.4</junit.version>

    <!-- Plugin versions -->
    <maven.compiler.plugin.version>3.13.0</maven.compiler.plugin.version>
    <maven.jar.plugin.version>3.4.2</maven.jar.plugin.version>
    <javafx.maven.plugin.version>0.0.8</javafx.maven.plugin.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Copilot SDK -->
      <dependency>
        <groupId>com.github</groupId>
        <artifactId>copilot-sdk-java</artifactId>
        <version>${copilot.sdk.version}</version>
      </dependency>

      <!-- JavaFX -->
      <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-controls</artifactId>
        <version>${javafx.version}</version>
      </dependency>
      <dependency>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-fxml</artifactId>
        <version>${javafx.version}</version>
      </dependency>

      <!-- System Tray -->
      <dependency>
        <groupId>com.dorkbox</groupId>
        <artifactId>SystemTray</artifactId>
        <version>${dorkbox.systemtray.version}</version>
      </dependency>

      <!-- JSON config -->
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
      </dependency>

      <!-- Logging -->
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-jdk14</artifactId>
        <version>${slf4j.version}</version>
      </dependency>

      <!-- Test -->
      <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>${junit.version}</version>
        <scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

---

## App Module POM (`java/app/pom.xml`) — Key Sections

```xml
<build>
  <plugins>

    <!-- 1. Compiler -->
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>${maven.compiler.plugin.version}</version>
      <configuration>
        <release>${java.version}</release>
        <compilerArgs>
          <arg>--enable-preview</arg>
        </compilerArgs>
      </configuration>
    </plugin>

    <!-- 2. JavaFX Maven Plugin (for local dev: mvn javafx:run) -->
    <plugin>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-maven-plugin</artifactId>
      <version>${javafx.maven.plugin.version}</version>
      <configuration>
        <mainClass>com.github.copilot.tray/com.github.copilot.tray.Main</mainClass>
        <options>
          <option>--enable-preview</option>
        </options>
      </configuration>
    </plugin>

    <!-- 3. Dependency plugin: copy deps to target/mods for jlink -->
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-dependency-plugin</artifactId>
      <executions>
        <execution>
          <id>copy-deps</id>
          <phase>package</phase>
          <goals><goal>copy-dependencies</goal></goals>
          <configuration>
            <outputDirectory>${project.build.directory}/mods</outputDirectory>
          </configuration>
        </execution>
      </executions>
    </plugin>

    <!-- 4. jlink via exec-maven-plugin -->
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>exec-maven-plugin</artifactId>
      <version>3.4.1</version>
      <executions>
        <execution>
          <id>jlink</id>
          <phase>package</phase>
          <goals><goal>exec</goal></goals>
          <configuration>
            <executable>${java.home}/bin/jlink</executable>
            <arguments>
              <argument>--module-path</argument>
              <argument>${project.build.directory}/mods:${project.build.directory}/${project.artifactId}-${project.version}.jar:${java.home}/jmods</argument>
              <argument>--add-modules</argument>
              <argument>com.github.copilot.tray,javafx.controls,javafx.fxml,java.desktop,java.logging,java.prefs</argument>
              <argument>--output</argument>
              <argument>${project.build.directory}/runtime</argument>
              <argument>--strip-debug</argument>
              <argument>--no-header-files</argument>
              <argument>--no-man-pages</argument>
              <argument>--compress=zip-6</argument>
            </arguments>
          </configuration>
        </execution>

        <!-- 5. jpackage: create native installer -->
        <execution>
          <id>jpackage</id>
          <phase>package</phase>
          <goals><goal>exec</goal></goals>
          <configuration>
            <executable>${java.home}/bin/jpackage</executable>
            <arguments>
              <argument>--type</argument>
              <argument>${installer.type}</argument>      <!-- Set per platform profile -->
              <argument>--name</argument>
              <argument>Copilot CLI Tray</argument>
              <argument>--app-version</argument>
              <argument>${project.version}</argument>
              <argument>--vendor</argument>
              <argument>Bruno Borges</argument>
              <argument>--runtime-image</argument>
              <argument>${project.build.directory}/runtime</argument>
              <argument>--module</argument>
              <argument>com.github.copilot.tray/com.github.copilot.tray.Main</argument>
              <argument>--dest</argument>
              <argument>${project.build.directory}/installer</argument>
              <argument>--icon</argument>
              <argument>${platform.icon}</argument>
              <argument>--java-options</argument>
              <argument>--enable-preview</argument>
            </arguments>
          </configuration>
        </execution>
      </executions>
    </plugin>

  </plugins>
</build>

<!-- Platform profiles activate automatically via os.name / os.arch -->
<profiles>

  <!-- macOS DMG -->
  <profile>
    <id>macos</id>
    <activation><os><family>mac</family></os></activation>
    <properties>
      <installer.type>dmg</installer.type>
      <platform.icon>${project.basedir}/../dist/macos/app.icns</platform.icon>
    </properties>
    <build>
      <plugins>
        <plugin>
          <!-- Extra jpackage args for macOS: bundle ID, entitlements -->
          <groupId>org.codehaus.mojo</groupId>
          <artifactId>exec-maven-plugin</artifactId>
          <configuration>
            <environmentVariables>
              <!-- Used in jpackage execution above -->
              <JPACKAGE_EXTRA_ARGS>--mac-bundle-identifier com.github.copilot.tray --mac-package-name "Copilot CLI Tray"</JPACKAGE_EXTRA_ARGS>
            </environmentVariables>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>

  <!-- Linux DEB (for apt) -->
  <profile>
    <id>linux</id>
    <activation><os><family>unix</family><name>Linux</name></os></activation>
    <properties>
      <installer.type>deb</installer.type>
      <platform.icon>${project.basedir}/../dist/linux/app.png</platform.icon>
    </properties>
  </profile>

  <!-- Windows MSI -->
  <profile>
    <id>windows</id>
    <activation><os><family>windows</family></os></activation>
    <properties>
      <installer.type>msi</installer.type>
      <platform.icon>${project.basedir}/../dist/windows/app.ico</platform.icon>
    </properties>
  </profile>

</profiles>
```

---

## Key Source Files

### `Main.java` — Entry Point

```java
package com.github.copilot.tray;

import javafx.application.Platform;

public class Main {
    public static void main(String[] args) {
        // Prevent JavaFX from closing when the settings window is closed
        Platform.setImplicitExit(false);

        var app = new TrayApplication();
        app.start();
    }
}
```

---

### `TrayApplication.java` — Lifecycle

```java
package com.github.copilot.tray;

public class TrayApplication {

    private final ConfigStore configStore = new ConfigStore();
    private final SdkBridge sdkBridge = new SdkBridge();
    private final SessionManager sessionManager = new SessionManager();
    private final TrayManager trayManager;
    private final Notifier notifier = new Notifier();

    public TrayApplication() {
        this.trayManager = new TrayManager(sessionManager, sdkBridge);
    }

    public void start() {
        configStore.load();
        sdkBridge.connect(sessionManager::onSdkEvent);
        trayManager.install();
        sessionManager.addChangeListener(trayManager::refresh);
        sessionManager.addChangeListener(notifier::onSessionChange);
    }

    public void shutdown() {
        trayManager.uninstall();
        sdkBridge.disconnect();
    }
}
```

---

### `SdkBridge.java` — Copilot SDK Integration

```java
package com.github.copilot.tray.sdk;

import com.github.copilot.sdk.*;
import com.github.copilot.sdk.events.*;
import com.github.copilot.sdk.json.*;

public class SdkBridge {

    private CopilotClient client;

    public void connect(Consumer<AbstractSessionEvent> eventSink) {
        client = new CopilotClient();
        client.start()
              .thenCompose(v -> client.listSessions())
              .thenAccept(sessions -> sessions.forEach(meta ->
                  client.resumeSession(meta.id(), new ResumeSessionConfig()
                      .setOnEvent(eventSink))
              ))
              .exceptionally(ex -> { /* log and schedule reconnect */ return null; });
    }

    public CompletableFuture<List<SessionMetadata>> listSessions() {
        return client.listSessions();
    }

    public CompletableFuture<List<ModelInfo>> listModels() {
        return client.listModels();
    }

    public CompletableFuture<Void> deleteSession(String sessionId) {
        return client.deleteSession(sessionId);
    }

    public CompletableFuture<Void> cancelSession(String sessionId) {
        return client.resumeSession(sessionId, new ResumeSessionConfig())
                     .thenCompose(CopilotSession::abort);
    }

    public void disconnect() {
        if (client != null) client.close();
    }
}
```

---

### `EventRouter.java` — SDK Event → SessionManager

Maps SDK events to session state changes. Key subscriptions:

| SDK Event                     | SessionManager action                                  |
|-------------------------------|--------------------------------------------------------|
| `SessionStartEvent`           | `addSession(id, model, workspace)`                     |
| `SessionShutdownEvent`        | `archiveSession(id)`                                   |
| `SessionIdleEvent`            | `setStatus(id, IDLE)`                                  |
| `AssistantTurnStartEvent`     | `setStatus(id, BUSY)`                                  |
| `AssistantTurnEndEvent`       | `setStatus(id, IDLE)`                                  |
| `SessionUsageInfoEvent`       | `updateUsage(id, tokens, limit, msgCount)`             |
| `SessionErrorEvent`           | `setStatus(id, ERROR)`                                 |
| `SessionModelChangeEvent`     | `updateModel(id, newModel)`                            |
| `SubagentStartedEvent`        | `addSubagent(sessionId, subagentId, desc)`             |
| `SubagentCompletedEvent`      | `updateSubagent(sessionId, subagentId, COMPLETED)`     |
| `SubagentFailedEvent`         | `updateSubagent(sessionId, subagentId, FAILED)`        |
| `PermissionRequestedEvent`    | `setPendingPermission(id, true)`                       |
| `PermissionCompletedEvent`    | `setPendingPermission(id, false)`                      |
| `SessionCompactionCompleteEvent` | `resetUsage(id)` then re-read from next UsageInfoEvent |

---

## Build & Package Workflow

### Local Development

```bash
cd java/

# Run the app directly (no packaging)
mvn javafx:run -pl app

# Run tests
mvn test -pl app

# Build fat JAR (for quick testing, no jlink)
mvn package -pl app -P skip-jlink
```

### Full Build with Installer

```bash
# Produces: target/installer/Copilot CLI Tray-1.0.0.dmg  (on macOS)
#           target/installer/copilot-cli-tray_1.0.0_amd64.deb  (on Linux)
#           target/installer/Copilot CLI Tray-1.0.0.msi  (on Windows)
mvn clean package -pl app
```

---

## GitHub Actions CI/CD

### Build Matrix

```yaml
# .github/workflows/build.yml
strategy:
  matrix:
    include:
      - os: macos-13           # macOS Intel
        arch: x86_64
        artifact: dmg
      - os: macos-latest       # macOS Apple Silicon (arm64)
        arch: arm64
        artifact: dmg
      - os: ubuntu-latest      # Linux x86_64
        arch: x86_64
        artifact: deb
      - os: ubuntu-24.04-arm   # Linux arm64
        arch: arm64
        artifact: deb
      - os: windows-latest     # Windows x86_64
        arch: x86_64
        artifact: msi
      - os: windows-11-arm     # Windows arm64 (GitHub-hosted, preview)
        arch: arm64
        artifact: msi
```

### Workflow Steps (per job)

```yaml
steps:
  - uses: actions/checkout@v4

  - name: Set up JDK 25
    uses: actions/setup-java@v4
    with:
      java-version: '25'
      distribution: 'oracle'   # or 'temurin' when JDK 25 available

  # Linux only: install jpackage deb dependencies
  - name: Install jpackage deps (Linux)
    if: runner.os == 'Linux'
    run: sudo apt-get install -y fakeroot rpm

  # Windows only: install WiX toolset for MSI
  - name: Install WiX (Windows)
    if: runner.os == 'Windows'
    run: dotnet tool install --global wix

  - name: Build and package
    run: mvn clean package -pl app
    working-directory: java

  - name: Upload installer artifact
    uses: actions/upload-artifact@v4
    with:
      name: copilot-cli-tray-${{ matrix.os }}-${{ matrix.arch }}
      path: java/app/target/installer/*.${{ matrix.artifact }}
```

### Release Workflow

On a `v*` tag push:
1. Run build matrix across all 6 platforms
2. Download all 6 artifacts
3. Create a GitHub Release with all installers attached
4. Generate SHA-256 checksums for each installer file

---

## Installer Details

### macOS — DMG

`jpackage --type dmg` produces a standard macOS disk image with a drag-to-Applications installer. Additional considerations:

- **Bundle ID**: `com.github.copilot.tray`
- **App category**: `public.app-category.developer-tools`
- **Code signing**: pass `--mac-sign` and `--mac-signing-key-user-name` for notarization
- **Notarization**: required for Gatekeeper on macOS 13+; use `xcrun notarytool` in CI
- **Universal binary**: since macOS arm64 and x86_64 are separate jobs, produce separate DMGs (not a fat binary — jlink images are arch-specific)

```
Copilot CLI Tray-1.0.0.dmg
  └─ Copilot CLI Tray.app/
       ├─ Contents/
       │   ├─ MacOS/Copilot CLI Tray      (launcher script/binary)
       │   ├─ Info.plist
       │   ├─ Resources/app.icns
       │   └─ app/
       │       ├─ runtime/               (jlink minimal JRE)
       │       └─ app.jar                (app module JAR)
```

---

### Linux — DEB (APT-compatible)

`jpackage --type deb` produces a Debian package installable via `apt`:

```bash
# One-time install
sudo dpkg -i copilot-cli-tray_1.0.0_amd64.deb

# Or if hosted in an APT repository:
curl -fsSL https://brunoborges.github.io/copilot-cli-tray/apt/KEY.gpg | sudo apt-key add -
echo "deb https://brunoborges.github.io/copilot-cli-tray/apt stable main" | sudo tee /etc/apt/sources.list.d/copilot-cli-tray.list
sudo apt update && sudo apt install copilot-cli-tray
```

DEB package layout:
```
/opt/copilot-cli-tray/
  ├─ bin/Copilot CLI Tray          (launcher)
  └─ lib/
      ├─ runtime/                   (jlink minimal JRE)
      └─ app/
          └─ copilot-cli-tray.jar

/usr/share/applications/copilot-cli-tray.desktop   (XDG desktop entry)
/usr/share/icons/hicolor/256x256/apps/copilot-cli-tray.png
```

Additional `jpackage` arguments for Linux:
- `--linux-package-name copilot-cli-tray`
- `--linux-app-category Development`
- `--linux-menu-group Development`
- `--linux-shortcut` (adds to application menu)
- `--linux-deb-maintainer brunoborges@github.com`

---

### Windows — MSI

`jpackage --type msi` produces a Windows Installer package. Requires **WiX Toolset 3.x or 4.x** to be installed on the build machine.

```powershell
# Install
msiexec /i "Copilot CLI Tray-1.0.0.msi" /quiet

# Uninstall
msiexec /x "Copilot CLI Tray-1.0.0.msi" /quiet
```

MSI install layout:
```
%ProgramFiles%\Copilot CLI Tray\
  ├─ Copilot CLI Tray.exe          (launcher)
  ├─ runtime\                      (jlink minimal JRE)
  └─ app\
      └─ copilot-cli-tray.jar

%AppData%\Microsoft\Windows\Start Menu\Programs\Copilot CLI Tray.lnk
```

Additional `jpackage` arguments for Windows:
- `--win-menu` (add to Start Menu)
- `--win-shortcut` (add Desktop shortcut, optional)
- `--win-dir-chooser` (allow custom install directory)
- `--win-upgrade-uuid <GUID>` (stable GUID for upgrades — must be set once and never changed)

**Code signing (optional but recommended):**
```bash
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 \
  /f certificate.p12 "Copilot CLI Tray-1.0.0.msi"
```

---

## jlink Runtime Size Estimates

With only the required modules included, the jlink runtime image should be approximately:

| Component         | Approx. Size |
|-------------------|--------------|
| `java.base`       | ~15 MB       |
| `java.desktop`    | ~10 MB       |
| `javafx.*`        | ~25 MB       |
| App JARs + deps   | ~15 MB       |
| **Total (compressed)** | **~50–65 MB** |

Compare to a full JDK 25: ~300+ MB. This makes the installer acceptable for distribution.

---

## Auto-Update Check

On startup, the app queries the GitHub Releases API:

```
GET https://api.github.com/repos/brunoborges/copilot-cli-tray/releases/latest
```

If the `tag_name` is newer than the running version, a tray notification prompts the user to download the latest installer for their platform. The tray menu also shows an "Update Available" item that opens the browser to the release page.

---

## Local Config Paths

Set in `ConfigStore.java` using `System.getProperty("os.name")`:

| OS      | Config Directory                                          |
|---------|-----------------------------------------------------------|
| macOS   | `~/Library/Application Support/copilot-cli-tray/`        |
| Linux   | `${XDG_CONFIG_HOME:-~/.config}/copilot-cli-tray/`        |
| Windows | `%APPDATA%\copilot-cli-tray\`                             |

Files stored:
- `config.json` — user preferences (serialized `AppConfig` via Jackson)
- `sessions-cache.json` — last known session list for fast tray population at startup
- `usage-history.json` — rolling 30-day token/request usage log

---

## Auto-Start on Login

Implemented via `java.util.prefs.Preferences` + platform-specific mechanism:

| OS      | Mechanism                                                             |
|---------|-----------------------------------------------------------------------|
| macOS   | Write `~/Library/LaunchAgents/com.github.copilot.tray.plist`         |
| Linux   | Write `~/.config/autostart/copilot-cli-tray.desktop`                 |
| Windows | Write `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` registry  |

---

## Logging

Uses `java.util.logging` (JUL) backed by SLF4J's `slf4j-jdk14` bridge. Log file location:

| OS      | Log File                                                        |
|---------|-----------------------------------------------------------------|
| macOS   | `~/Library/Logs/copilot-cli-tray/app.log`                      |
| Linux   | `~/.local/share/copilot-cli-tray/logs/app.log`                 |
| Windows | `%LOCALAPPDATA%\copilot-cli-tray\logs\app.log`                 |

Log rotation: keep last 5 files, max 5 MB each.

---

## Next Steps

1. Scaffold Maven project with `mvn archetype:generate`
2. Add `module-info.java` and verify JPMS compatibility of all dependencies
3. Verify Dorkbox SystemTray works as a named module (may need `--add-opens` args)
4. Implement `SdkBridge` with connection + event routing
5. Build basic tray menu (static, hardcoded) to validate Dorkbox + JavaFX coexistence
6. Wire in `SessionManager` and dynamic menu rebuilding
7. Add `jlink` + `jpackage` Maven execution; test installer on each platform
8. Set up GitHub Actions build matrix
