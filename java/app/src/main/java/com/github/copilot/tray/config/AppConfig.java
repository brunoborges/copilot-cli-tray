package com.github.copilot.tray.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Application configuration model, serialized to/from config.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    private String cliPath = "";
    private String ghCliPath = "";
    private int pollIntervalSeconds = 5;
    private int contextWarningThreshold = 80;
    private boolean notificationsEnabled = true;
    private String theme = "system";
    private String logLevel = "INFO";
    private boolean autoStart = false;
    private boolean openDashboardOnStartup = false;

    public AppConfig() {}

    public String getCliPath() { return cliPath; }
    public void setCliPath(String cliPath) { this.cliPath = cliPath; }

    public String getGhCliPath() { return ghCliPath; }
    public void setGhCliPath(String ghCliPath) { this.ghCliPath = ghCliPath; }

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    public int getContextWarningThreshold() { return contextWarningThreshold; }
    public void setContextWarningThreshold(int contextWarningThreshold) { this.contextWarningThreshold = contextWarningThreshold; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

    public boolean isOpenDashboardOnStartup() { return openDashboardOnStartup; }
    public void setOpenDashboardOnStartup(boolean openDashboardOnStartup) { this.openDashboardOnStartup = openDashboardOnStartup; }
}
