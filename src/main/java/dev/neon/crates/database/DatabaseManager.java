package dev.neon.crates.database;

import dev.neon.crates.NeonCrates;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
        if (!dbFile.exists()) {
            try {
                plugin.saveResource("database.db", false);
                plugin.getLogger().info("[DatabaseManager] Copied default database.db from plugin resources.");
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[DatabaseManager] Default database.db was not found in plugin jar resources, a new blank database will be created.");
            }
        }
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

    public void mergePlayerData(UUID currentUuid, String username) {
        CompletableFuture.runAsync(() -> {
            mergePlayerDataSync(currentUuid, username);
        }, dbExecutor);
    }

    public void mergePlayerDataSync(UUID currentUuid, String username) {
        List<String> otherUuids = new ArrayList<>();
        String sqlSelectUuids = "SELECT DISTINCT uuid FROM players WHERE LOWER(username) = LOWER(?) AND uuid != ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlSelectUuids)) {
            ps.setString(1, username);
            ps.setString(2, currentUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    otherUuids.add(rs.getString("uuid"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Error fetching other UUIDs for merge: " + e.getMessage());
            return;
        }

        if (otherUuids.isEmpty()) return;

        plugin.getLogger().info("[DatabaseManager] Found " + otherUuids.size() + " other UUID(s) for player " + username + ". Merging keys...");

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String oldUuid : otherUuids) {
                    String selectKeysSql = "SELECT crate_id, amount FROM crate_virtual_keys WHERE uuid = ?";
                    try (PreparedStatement psKeys = conn.prepareStatement(selectKeysSql)) {
                        psKeys.setString(1, oldUuid);
                        try (ResultSet rsKeys = psKeys.executeQuery()) {
                            while (rsKeys.next()) {
                                String crateId = rsKeys.getString("crate_id");
                                long amount = rsKeys.getLong("amount");
                                if (amount > 0) {
                                    String insertMergeSql = """
                                            INSERT INTO crate_virtual_keys (uuid, crate_id, amount)
                                            VALUES (?, ?, ?)
                                            ON CONFLICT(uuid, crate_id) DO UPDATE SET amount = amount + excluded.amount;
                                            """;
                                    try (PreparedStatement psMerge = conn.prepareStatement(insertMergeSql)) {
                                        psMerge.setString(1, currentUuid.toString());
                                        psMerge.setString(2, crateId);
                                        psMerge.setLong(3, amount);
                                        psMerge.executeUpdate();
                                    }
                                }
                            }
                        }
                    }

                    String deleteOldKeysSql = "DELETE FROM crate_virtual_keys WHERE uuid = ?";
                    try (PreparedStatement psDel = conn.prepareStatement(deleteOldKeysSql)) {
                        psDel.setString(1, oldUuid);
                        psDel.executeUpdate();
                    }

                    String deleteOldTracking = "DELETE FROM keyall_tracking WHERE uuid = ?";
                    try (PreparedStatement psDelTrack = conn.prepareStatement(deleteOldTracking)) {
                        psDelTrack.setString(1, oldUuid);
                        psDelTrack.executeUpdate();
                    }

                    String deleteOldPlayer = "DELETE FROM players WHERE uuid = ?";
                    try (PreparedStatement psDelPlayer = conn.prepareStatement(deleteOldPlayer)) {
                        psDelPlayer.setString(1, oldUuid);
                        psDelPlayer.executeUpdate();
                    }
                }
                conn.commit();
                plugin.getLogger().info("[DatabaseManager] Successfully merged keys for " + username + " under current UUID: " + currentUuid);
            } catch (SQLException e) {
                conn.rollback();
                plugin.getLogger().severe("[DatabaseManager] Error transaction rollback during merge: " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("[DatabaseManager] Database error during merge: " + e.getMessage());
        }
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
