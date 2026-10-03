package com.August.chunkcleaner.config;

import com.August.chunkcleaner.ChunkCleanerPlugin;

public record Config(ChunkCleanerPlugin plugin) {

    public String getLanguage() {
        return plugin.getConfig().getString("language", "auto").trim().toLowerCase();
    }

    public void setLanguage(String lang) {
        plugin.getConfig().set("language", lang.trim().toLowerCase());
        plugin.saveConfig();
    }

    public long getMinInhabitedTime() {
        return plugin.getConfig().getLong("min_inhabited_time", 20);
    }

    public void setMinInhabitedTime(long ticks) {
        plugin.getConfig().set("min_inhabited_time", Math.max(0, ticks));
        plugin.saveConfig();
    }

    public String getMode() {
        return plugin.getConfig().getString("cleanup_mode", "RUNTIME").toUpperCase();
    }

    public void setMode(String mode) {
        plugin.getConfig().set("cleanup_mode", mode.toUpperCase());
        plugin.saveConfig();
    }

    public long getRegionDelayMs() {
        return plugin.getConfig().getLong("region_delay_ms", 10);
    }

    public void setRegionDelayMs(long ms) {
        plugin.getConfig().set("region_delay_ms", Math.max(0, ms));
        plugin.saveConfig();
    }

    public boolean isProgressFeedbackEnabled() {
        return plugin.getConfig().getBoolean("progress_feedback_enabled", true);
    }

    public void setProgressFeedbackEnabled(boolean enabled) {
        plugin.getConfig().set("progress_feedback_enabled", enabled);
        plugin.saveConfig();
    }

    public int getProgressFeedbackSeconds() {
        return plugin.getConfig().getInt("progress_feedback_seconds", 20);
    }

    public void setProgressFeedbackSeconds(int seconds) {
        plugin.getConfig().set("progress_feedback_seconds", Math.max(1, seconds));
        plugin.saveConfig();
    }

    public int getWebPort() {
        return plugin.getConfig().getInt("web.port", 8765);
    }

    public void setWebPort(int port) {
        plugin.getConfig().set("web.port", Math.max(1, Math.min(65535, port)));
        plugin.saveConfig();
    }

    public String getWebHost() {
        return plugin.getConfig().getString("web.host", "127.0.0.1");
    }

    public void setWebHost(String host) {
        plugin.getConfig().set("web.host", host != null && !host.isBlank() ? host.trim() : "127.0.0.1");
        plugin.saveConfig();
    }

    public int getWebIdleTimeoutMinutes() {
        return plugin.getConfig().getInt("web.idle_timeout_minutes", 15);
    }

    public void setWebIdleTimeoutMinutes(int minutes) {
        plugin.getConfig().set("web.idle_timeout_minutes", Math.max(0, minutes));
        plugin.saveConfig();
    }

    public boolean isWebAutoStart() {
        return plugin.getConfig().getBoolean("web.auto_start_on_scan", false);
    }

    public void setWebAutoStart(boolean autoStart) {
        plugin.getConfig().set("web.auto_start_on_scan", autoStart);
        plugin.saveConfig();
    }
}

