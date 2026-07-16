package dev.neon.crates.database;

import dev.neon.crates.NeonCrates;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    private final NeonCrates plugin;
    private final File dbFile;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NeonCrates-Database-Thread");
        thread.setDaemon(true);
        return thread;
    });

    public DatabaseManager(NeonCrates plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        this.dbFile = new File(plugin.getDataFolder(), "database.db");
    }

    public ExecutorService getExecutor() {
        return dbExecutor;
    }

    public synchronized void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = getConnection()) {
                plugin.getLogger().info("[DatabaseManager] Connected to SQLite database successfully.");
            }
            createTables();
        } catch (Exception e) {
            plugin.getLogger().severe("[DatabaseManager] FAILED TO CONNECT TO SQLite: " + e.getMessage());
        }
    }

    private synchronized void createTables() {
        String sqlPlayers = """
                CREATE TABLE IF NOT EXISTS players (
                  uuid        TEXT  NOT NULL PRIMARY KEY,
                  username    TEXT  NOT NULL,
                  last_seen   INTEGER NOT NULL DEFAULT 0
                );
                """;

        String sqlKeys = """
                CREATE TABLE IF NOT EXISTS crate_virtual_keys (
                    uuid TEXT NOT NULL,
                    crate_id TEXT NOT NULL,
                    amount INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, crate_id)
                );
                """;

        String sqlTracking = """
                CREATE TABLE IF NOT EXISTS keyall_tracking (
                    uuid TEXT NOT NULL PRIMARY KEY,
                    next_keyall INTEGER NOT NULL
                );
                """;

        String sqlPending = """
                CREATE TABLE IF NOT EXISTS crate_pending_keys (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    crate_id TEXT NOT NULL,
                    amount INTEGER NOT NULL
                );
                """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sqlPlayers);
            stmt.execute(sqlKeys);
            stmt.execute(sqlTracking);
            stmt.execute(sqlPending);
            plugin.getLogger().info("[DatabaseManager] SQLite tables initialized successfully.");
        } catch (SQLException e) {
            plugin.getLogger().severe("[DatabaseManager] Table initialization failed: " + e.getMessage());
        }
    }

    public void registerPlayer(UUID uuid, String username) {
        CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO players (uuid, username, last_seen)
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, last_seen=excluded.last_seen;
                    """;
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[DatabaseManager] Error registering player: " + e.getMessage());
            }
        }, dbExecutor);
    }

    public UUID getUUIDByName(String username) {
        String sql = "SELECT uuid FROM players WHERE LOWER(username) = LOWER(?) ORDER BY last_seen DESC LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Error resolving name: " + e.getMessage());
        }
        return null;
    }

    public UUID getUUIDByNameFlexible(String username) {
        if (username == null || username.isBlank()) return null;
        UUID direct = getUUIDByName(username);
        if (direct != null) return direct;

        String normalized = username.startsWith(".") ? username.substring(1) : username;
        if (!normalized.equals(username)) {
            UUID withoutDot = getUUIDByName(normalized);
            if (withoutDot != null) return withoutDot;
        }
        return getUUIDByName("." + normalized);
    }

    public Connection getConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA busy_timeout=10000;");
            }
            return conn;
        } catch (Exception e) {
            throw new SQLException("Failed to open SQLite database connection: " + e.getMessage(), e);
        }
    }

    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
        }
    }
}
