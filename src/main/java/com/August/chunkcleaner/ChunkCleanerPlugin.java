package com.August.chunkcleaner;

import com.August.chunkcleaner.command.Command;
import com.August.chunkcleaner.config.Config;
import com.August.chunkcleaner.core.ChunkProcessor;
import com.August.chunkcleaner.web.ChunkMapTracker;
import com.August.chunkcleaner.web.WebServerManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ChunkCleanerPlugin extends JavaPlugin {

    private Config filterConfig;
    private ChunkMapTracker tracker;
    private ChunkProcessor processor;
    private WebServerManager webServerManager;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        this.filterConfig = new Config(this);
        this.tracker = new ChunkMapTracker();
        this.processor = new ChunkProcessor(this, filterConfig, tracker);
        this.webServerManager = new WebServerManager(this, filterConfig, tracker);
        this.webServerManager.setProcessor(this.processor);
        this.processor.setWebServerManager(this.webServerManager);

        if ("STARTUP".equalsIgnoreCase(filterConfig.getMode())) {
            this.processor.runTaskSyncForOnLoad();
        }
    }

    @Override
    public void onEnable() {
        if (this.processor == null) {
            saveDefaultConfig();
            this.filterConfig = new Config(this);
            this.tracker = new ChunkMapTracker();
            this.processor = new ChunkProcessor(this, filterConfig, tracker);
            this.webServerManager = new WebServerManager(this, filterConfig, tracker);
            this.webServerManager.setProcessor(this.processor);
            this.processor.setWebServerManager(this.webServerManager);
        }

        Command commandHandler = new Command(this, processor, filterConfig, webServerManager);
        PluginCommand cmd = getCommand("chunkcleaner");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        }
    }

    @Override
    public void onDisable() {
        if (this.webServerManager != null) {
            this.webServerManager.stop();
        }
        if (this.processor != null) {
            this.processor.stopAllTasks();
        }
    }
}

