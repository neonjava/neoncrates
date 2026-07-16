package dev.neon.crates.config;

import dev.neon.crates.NeonCrates;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class ConfigManager {

    private final NeonCrates plugin;
    private FileConfiguration config;

    public ConfigManager(NeonCrates plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    // Scheduler Options
    public int getKeyallIntervalSeconds() {
        return config.getInt("keyall-interval-seconds", 3600);
    }

    public int getBonusMinDays() {
        return config.getInt("bonus-min-days", 1);
    }

    public int getBonusMaxDays() {
        return config.getInt("bonus-max-days", 3);
    }

    public List<String> getBonusCrates() {
        return config.getStringList("bonus-crates");
    }
}
