package dev.neon.crates;

import dev.neon.crates.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

public class KeyManager {

    private final NeonCrates plugin;
    private final NamespacedKey keyType;
    private final Random random = new Random();
    private final Map<UUID, Integer> bonusCountdownSeconds = new ConcurrentHashMap<>();
    
    // In-memory cache for online players' virtual keys to prevent synchronous database calls blocking region ticks
    // playerUUID -> (crateId -> keyCount)
    private final Map<UUID, Map<String, Long>> virtualKeyCache = new ConcurrentHashMap<>();
    
    // playerUUID -> nextKeyall timestamp
    private final Map<UUID, Long> keyallTrackingCache = new ConcurrentHashMap<>();

    private ScheduledTask asyncTask;

    public KeyManager(NeonCrates plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "crate_key_type");
    }

    public void startSchedulers() {
        asyncTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (task) -> {
            long now = System.currentTimeMillis() / 1000L;
            ConfigManager cfg = plugin.getConfigManager();
            int interval = cfg.getKeyallIntervalSeconds();

            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uuid = p.getUniqueId();
                long nextForPlayer = ensureAndGetNextKeyall(uuid, now, interval);
                if (now >= nextForPlayer) {
                    addVirtualKeys(uuid, "common-crate", 1);
                    setNextKeyall(uuid, now + interval);
                    p.sendMessage(Component.text()
                            .append(Component.text("KEYALL ", NamedTextColor.AQUA, TextDecoration.BOLD))
                            .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("You received 1 virtual Common Crate key!", NamedTextColor.WHITE))
                            .build());
                }

                int bonusLeft = bonusCountdownSeconds.compute(uuid, (k, v) -> v == null ? randomBonusSeconds(cfg) : v - 1);
                if (bonusLeft <= 0) {
                    String crateId = randomBonusCrate(cfg);
                    if (crateId != null) {
                        addVirtualKeys(uuid, crateId, 1);
                        p.sendMessage(Component.text()
                                .append(Component.text("BONUS KEY ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                                .append(Component.text("You received 1 virtual " + formatCrateName(crateId) + " key!",
                                        NamedTextColor.WHITE))
                                .build());
                    }
                    bonusCountdownSeconds.put(uuid, randomBonusSeconds(cfg));
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stopSchedulers() {
        if (asyncTask != null) {
            asyncTask.cancel();
        }
    }

    // Load data asynchronously on player join
    public void loadPlayerData(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            // 1. Load virtual keys
            Map<String, Long> keys = new ConcurrentHashMap<>();
            String sqlKeys = "SELECT crate_id, amount FROM crate_virtual_keys WHERE uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlKeys)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.put(rs.getString("crate_id"), rs.getLong("amount"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[KeyManager] Error loading virtual keys for " + uuid + ": " + e.getMessage());
            }
            virtualKeyCache.put(uuid, keys);

            // 2. Load keyall tracking
            String sqlTracking = "SELECT next_keyall FROM keyall_tracking WHERE uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sqlTracking)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        keyallTrackingCache.put(uuid, rs.getLong("next_keyall"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[KeyManager] Error loading keyall tracking for " + uuid + ": " + e.getMessage());
            }
        }, plugin.getDatabaseManager().getExecutor());
    }

    public void unloadPlayerData(UUID uuid) {
        virtualKeyCache.remove(uuid);
        keyallTrackingCache.remove(uuid);
    }

    public boolean hasKey(Player player, String keyId) {
        String crateId = normalizeCrateId(keyId);
        if (getVirtualKeyCount(player.getUniqueId(), crateId) > 0) {
            return true;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR)
                continue;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null)
                continue;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (!pdc.has(keyType, PersistentDataType.STRING))
                continue;
            if (keyId.equals(pdc.get(keyType, PersistentDataType.STRING)))
                return true;
        }
        return false;
    }

    public boolean consumeKey(Player player, String keyId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR)
                continue;
            ItemMeta meta = stack.getItemMeta();
            if (meta == null)
                continue;
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(keyType, PersistentDataType.STRING)) {
                if (keyId.equals(pdc.get(keyType, PersistentDataType.STRING))) {
                    stack.setAmount(stack.getAmount() - 1);
                    return true;
                }
            }
        }

        String crateId = normalizeCrateId(keyId);
        return consumeVirtualKey(player.getUniqueId(), crateId, 1);
    }

    public String getKeyallCountdown(UUID uuid) {
        long now = System.currentTimeMillis() / 1000L;
        ConfigManager cfg = plugin.getConfigManager();
        long next = ensureAndGetNextKeyall(uuid, now, cfg.getKeyallIntervalSeconds());
        long left = Math.max(0, next - now);
        long min = left / 60;
        long sec = left % 60;
        return min + "m " + sec + "s";
    }

    private long ensureAndGetNextKeyall(UUID uuid, long nowEpochSeconds, int interval) {
        if (keyallTrackingCache.containsKey(uuid)) {
            return keyallTrackingCache.get(uuid);
        }

        String selectSql = "SELECT next_keyall FROM keyall_tracking WHERE uuid = ? LIMIT 1";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong("next_keyall");
                    keyallTrackingCache.put(uuid, val);
                    return val;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[KeyManager] Error fetching next keyall time for " + uuid + ": " + e.getMessage());
        }

        long seed = nowEpochSeconds + interval;
        setNextKeyall(uuid, seed);
        return seed;
    }

    private void setNextKeyall(UUID uuid, long nextEpoch) {
        keyallTrackingCache.put(uuid, nextEpoch);
        CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO keyall_tracking (uuid, next_keyall)
                    VALUES (?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET next_keyall = excluded.next_keyall;
                    """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, nextEpoch);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[KeyManager] Error saving next keyall time for " + uuid + ": " + e.getMessage());
            }
        }, plugin.getDatabaseManager().getExecutor());
    }

    private int randomBonusSeconds(ConfigManager cfg) {
        int minDays = cfg.getBonusMinDays();
        int maxDays = cfg.getBonusMaxDays();
        int diff = Math.max(1, maxDays - minDays + 1);
        int days = minDays + random.nextInt(diff);
        return days * 24 * 60 * 60;
    }

    private String randomBonusCrate(ConfigManager cfg) {
        List<String> crates = cfg.getBonusCrates();
        if (crates == null || crates.isEmpty()) {
            return null;
        }
        return crates.get(random.nextInt(crates.size()));
    }

    private String formatCrateName(String id) {
        String[] parts = id.split("-|_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty())
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    private String normalizeCrateId(String keyOrCrateId) {
        if (keyOrCrateId.endsWith("_key")) {
            return keyOrCrateId.substring(0, keyOrCrateId.length() - 4);
        }
        return keyOrCrateId;
    }

    public void addVirtualKeys(UUID uuid, String crateId, long amount) {
        if (amount <= 0) return;
        
        Map<String, Long> playerKeys = virtualKeyCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        playerKeys.put(crateId, playerKeys.getOrDefault(crateId, 0L) + amount);

        CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO crate_virtual_keys (uuid, crate_id, amount)
                    VALUES (?, ?, ?)
                    ON CONFLICT(uuid, crate_id) DO UPDATE SET amount = amount + excluded.amount;
                    """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, crateId);
                ps.setLong(3, amount);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[KeyManager] Error adding virtual keys for " + uuid + " (Crate: " + crateId + "): " + e.getMessage());
            }
        }, plugin.getDatabaseManager().getExecutor());
    }

    public long getVirtualKeyCount(UUID uuid, String crateId) {
        if (virtualKeyCache.containsKey(uuid)) {
            return virtualKeyCache.get(uuid).getOrDefault(crateId, 0L);
        }

        String sql = "SELECT amount FROM crate_virtual_keys WHERE uuid = ? AND crate_id = ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, crateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong("amount");
                    Map<String, Long> playerKeys = virtualKeyCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                    playerKeys.put(crateId, val);
                    return val;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[KeyManager] Error fetching virtual key count for " + uuid + " (Crate: " + crateId + "): " + e.getMessage());
        }
        return 0L;
    }

    public boolean consumeVirtualKey(UUID uuid, String crateId, long amount) {
        if (amount <= 0) return true;

        Map<String, Long> playerKeys = virtualKeyCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        long current = playerKeys.getOrDefault(crateId, 0L);
        if (current < amount) {
            current = getVirtualKeyCount(uuid, crateId);
            if (current < amount) return false;
        }

        playerKeys.put(crateId, current - amount);

        CompletableFuture.runAsync(() -> {
            String ensureSql = """
                    INSERT INTO crate_virtual_keys (uuid, crate_id, amount)
                    VALUES (?, ?, 0)
                    ON CONFLICT(uuid, crate_id) DO UPDATE SET amount = amount;
                    """;
            String consumeSql = """
                    UPDATE crate_virtual_keys
                    SET amount = amount - ?
                    WHERE uuid = ? AND crate_id = ? AND amount >= ?
                    """;
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                try (PreparedStatement ensure = conn.prepareStatement(ensureSql)) {
                    ensure.setString(1, uuid.toString());
                    ensure.setString(2, crateId);
                    ensure.executeUpdate();
                }
                try (PreparedStatement consume = conn.prepareStatement(consumeSql)) {
                    consume.setLong(1, amount);
                    consume.setString(2, uuid.toString());
                    consume.setString(3, crateId);
                    consume.setLong(4, amount);
                    consume.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[KeyManager] Error consuming virtual keys for " + uuid + " (Crate: " + crateId + ", Amount: " + amount + "): " + e.getMessage());
            }
        }, plugin.getDatabaseManager().getExecutor());
        return true;
    }
}
