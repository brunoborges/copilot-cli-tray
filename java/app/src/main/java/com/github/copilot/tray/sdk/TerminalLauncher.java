package com.github.copilot.tray.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Opens a platform-appropriate terminal emulator to resume a Copilot CLI session.
 */
public class TerminalLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalLauncher.class);

    /**
     * Open a new terminal window running {@code copilot --resume sessionId}
     * in the session's original working directory.
     */
    public void resumeSession(String sessionId, String workingDirectory) {
        var command = buildCommand("copilot --resume " + sessionId + " --banner", workingDirectory);
        LOG.info("Launching terminal for session {} in {}: {}", sessionId, workingDirectory, command);
        launch(command, workingDirectory);
    }

    /**
     * Open a new terminal window running a fresh {@code copilot} session.
     */
    public void newSession() {
        newSession(null);
    }

    /**
     * Open a new terminal window running a fresh {@code copilot} session
     * in the given working directory.
     */
    public void newSession(String workingDirectory) {
        var command = buildCommand("copilot --banner", workingDirectory);
        LOG.info("Launching new terminal session in {}: {}", workingDirectory, command);
        launch(command, workingDirectory);
    }

    private void launch(List<String> command, String workingDirectory) {
        try {
            var pb = new ProcessBuilder(command).inheritIO();
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                var dir = new File(workingDirectory);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            pb.start();
        } catch (IOException e) {
            LOG.error("Failed to launch terminal: {}", command, e);
        }
    }

    private List<String> buildCommand(String shellCmd, String workingDirectory) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return buildMacCommand(shellCmd, workingDirectory);
        } else if (os.contains("win")) {
            return buildWindowsCommand(shellCmd, workingDirectory);
        } else {
            return buildLinuxCommand(shellCmd, workingDirectory);
        }
    }

    private List<String> buildMacCommand(String shellCmd, String workingDirectory) {
        try {
            var tmp = File.createTempFile("copilot-tray-", ".command");
            tmp.deleteOnExit();
            try (var writer = new java.io.FileWriter(tmp)) {
                writer.write("#!/bin/bash\n");
                if (workingDirectory != null && !workingDirectory.isEmpty()) {
                    writer.write("cd " + escapeShell(workingDirectory) + "\n");
                }
                writer.write(shellCmd + "\n");
            }
            tmp.setExecutable(true);
            return List.of("open", tmp.getAbsolutePath());
        } catch (IOException e) {
            LOG.error("Failed to create .command file", e);
            var cdPrefix = workingDirectory != null ? "cd " + escapeShell(workingDirectory) + " && " : "";
            return List.of("osascript",
                    "-e", "tell application \"Terminal\"",
                    "-e", "activate",
                    "-e", "do script \"" + cdPrefix + shellCmd + "\"",
                    "-e", "end tell");
        }
    }

    private List<String> buildWindowsCommand(String shellCmd, String workingDirectory) {
        var args = new java.util.ArrayList<String>();
        args.add("cmd");
        args.add("/c");
        args.add("start");
        args.add("wt");
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            args.add("--startingDirectory");
            args.add(workingDirectory);
        }
        for (var part : shellCmd.split(" ")) {
            args.add(part);
        }
        return List.copyOf(args);
    }

    private List<String> buildLinuxCommand(String shellCmd, String workingDirectory) {
        var cdPrefix = (workingDirectory != null && !workingDirectory.isEmpty())
                ? "cd " + escapeShell(workingDirectory) + " && " : "";
        var fullCmd = cdPrefix + shellCmd;
        return List.of("sh", "-c",
                "if command -v gnome-terminal > /dev/null 2>&1; then gnome-terminal -- bash -c '" + fullCmd + "; exec bash'; "
                        + "elif command -v konsole > /dev/null 2>&1; then konsole -e bash -c '" + fullCmd + "; exec bash'; "
                        + "elif command -v xterm > /dev/null 2>&1; then xterm -e '" + fullCmd + "'; "
                        + "else x-terminal-emulator -e '" + fullCmd + "'; fi");
    }

    /** Escape a path for safe use in a shell command. */
    private static String escapeShell(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
