package dev.neon.crates;

import dev.neon.crates.NeonCrates;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class CrateManager implements Listener {

    private final NeonCrates plugin;
    private final Map<String, CrateConfig> crateConfigs = new HashMap<>();
    private final Map<UUID, PendingSelection> pendingSelections = new HashMap<>();
    private final Map<String, String> openCrateInvTitles = new HashMap<>();
    private final Map<UUID, String> activeEditors = new HashMap<>();
    private final Map<UUID, Integer> playerCratePage = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, CrateReward>> playerVisibleRewards = new java.util.concurrent.ConcurrentHashMap<>();

    public record CrateConfig(
            String id,
            String invTitle,
            int rows,
            Material bgMaterial,
            String bgName,
            List<String> bgLore,
            List<CrateReward> rewards) {
    }

    public record CrateReward(int slot, ItemStack item, List<String> commands) {
    }

    private record PendingSelection(String crateId, CrateReward reward) {
    }

    public CrateManager(NeonCrates plugin) {
        this.plugin = plugin;
        loadCrates();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void loadCrates() {
        crateConfigs.clear();
        openCrateInvTitles.clear();

        File file = new File(plugin.getDataFolder(), "crates.yml");
        if (!file.exists())
            plugin.saveResource("crates.yml", false);

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        for (String crateId : cfg.getKeys(false)) {
            ConfigurationSection cs = cfg.getConfigurationSection(crateId);
            if (cs == null)
                continue;

            String invTitle = cs.getString("inventory.name", "&8Choose 1 Item");
            int rows = cs.getInt("inventory.rows", 3);

            ConfigurationSection bgSec = cs.getConfigurationSection("inventory.background-icon");
            Material bgMat = parseMat(bgSec != null ? bgSec.getString("material", "BLACK_STAINED_GLASS_PANE")
                    : "BLACK_STAINED_GLASS_PANE");
            String bgName = bgSec != null ? bgSec.getString("name", " ") : " ";
            List<String> bgLore = bgSec != null ? bgSec.getStringList("lore") : Collections.emptyList();

            List<CrateReward> rewards = new ArrayList<>();
            ConfigurationSection rewardsSec = cs.getConfigurationSection("rewards");
            if (rewardsSec != null) {
                for (String rKey : rewardsSec.getKeys(false)) {
                    ConfigurationSection r = rewardsSec.getConfigurationSection(rKey);
                    if (r == null)
                        continue;
                    int slot = r.getInt("slot");
                    ItemStack icon = parseIcon(r.getConfigurationSection("icon"));
                    List<String> cmds = r.getStringList("commands");
                    rewards.add(new CrateReward(slot, icon, cmds));
                }
            }

            crateConfigs.put(crateId, new CrateConfig(crateId, invTitle, rows, bgMat, bgName, bgLore, rewards));
        }
        plugin.getLogger().info("[CrateManager] Loaded " + crateConfigs.size() + " crate(s).");
    }

    private ItemStack parseIcon(ConfigurationSection cs) {
        if (cs == null)
            return new ItemStack(Material.STONE);

        Material mat = parseMat(cs.getString("material", "STONE"));
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return item;

        if (cs.contains("name"))
            meta.displayName(plugin.color(cs.getString("name")));

        List<String> loreStrings = cs.getStringList("lore");
        if (!loreStrings.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreStrings)
                lore.add(plugin.color(l));
            meta.lore(lore);
        }

        ConfigurationSection enchSec = cs.getConfigurationSection("enchantments");
        if (enchSec != null) {
            if (mat == Material.ENCHANTED_BOOK && meta instanceof EnchantmentStorageMeta bookMeta) {
                for (String eName : enchSec.getKeys(false)) {
                    Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(eName.toLowerCase()));
                    if (ench != null) {
                        bookMeta.addStoredEnchant(ench, enchSec.getInt(eName), true);
                    }
                }
            } else {
                for (String eName : enchSec.getKeys(false)) {
                    Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(eName.toLowerCase()));
                    if (ench != null) {
                        meta.addEnchant(ench, enchSec.getInt(eName), true);
                    }
                }
            }
        }

        ConfigurationSection trimSec = cs.getConfigurationSection("trim");
        if (trimSec != null && meta instanceof ArmorMeta armorMeta) {
            Registry<TrimMaterial> matReg = Bukkit.getRegistry(TrimMaterial.class);
            Registry<TrimPattern> patReg = Bukkit.getRegistry(TrimPattern.class);
            if (matReg != null && patReg != null) {
                TrimMaterial trimMat = matReg
                        .get(NamespacedKey.minecraft(trimSec.getString("material", "emerald").toLowerCase()));
                TrimPattern trimPat = patReg
                        .get(NamespacedKey.minecraft(trimSec.getString("pattern", "wild").toLowerCase()));
                if (trimMat != null && trimPat != null)
                    armorMeta.setTrim(new ArmorTrim(trimMat, trimPat));
            }
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    public boolean bindBlock(Block block, String crateId) {
        if (!crateConfigs.containsKey(crateId))
            return false;

        File file = new File(plugin.getDataFolder(), "crate_locations.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String key = blockKey(block);
        cfg.set(key, crateId);
        try {
            cfg.save(file);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean unbindBlock(Block block) {
        File file = new File(plugin.getDataFolder(), "crate_locations.yml");
        if (!file.exists())
            return false;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String key = blockKey(block);
        if (!cfg.contains(key))
            return false;
        cfg.set(key, null);
        try {
            cfg.save(file);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getCrateAt(Block block) {
        File file = new File(plugin.getDataFolder(), "crate_locations.yml");
        if (!file.exists())
            return null;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        return cfg.getString(blockKey(block));
    }

    private String blockKey(Block b) {
        return b.getWorld().getName() + "." + b.getX() + "_" + b.getY() + "_" + b.getZ();
    }

    public Set<String> getCrateIds() {
        return Collections.unmodifiableSet(crateConfigs.keySet());
    }

    public void openCrateEditor(Player player, String crateId) {
        CrateConfig config = crateConfigs.get(crateId);
        if (config == null) {
            player.sendMessage("§cCrate configuration not found.");
            return;
        }

        activeEditors.put(player.getUniqueId(), crateId);
        Inventory inv = Bukkit.createInventory(null, config.rows() * 9, Component.text("Crate Editor: " + crateId, NamedTextColor.DARK_GRAY));

        for (CrateReward reward : config.rewards()) {
            if (reward.slot() >= 0 && reward.slot() < inv.getSize()) {
                inv.setItem(reward.slot(), reward.item().clone());
            }
        }

        player.openInventory(inv);
        player.sendMessage("§eOpened Crate Editor for: §6" + crateId + "§e. Place items in slots to edit rewards.");
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;

        UUID uuid = player.getUniqueId();
        if (!activeEditors.containsKey(uuid))
            return;

        String crateId = activeEditors.remove(uuid);
        Inventory inv = event.getInventory();

        File file = new File(plugin.getDataFolder(), "crates.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        CrateConfig oldConfig = crateConfigs.get(crateId);
        Map<Integer, List<String>> existingCommands = new HashMap<>();
        if (oldConfig != null) {
            for (CrateReward r : oldConfig.rewards()) {
                existingCommands.put(r.slot(), r.commands());
            }
        }

        cfg.set(crateId + ".rewards", null);

        int rewardIndex = 1;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR)
                continue;

            String path = crateId + ".rewards." + rewardIndex;
            cfg.set(path + ".slot", slot);
            cfg.set(path + ".icon.material", item.getType().toString());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    String plainName = LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName());
                    cfg.set(path + ".icon.name", plainName);
                }
                if (meta.hasLore()) {
                    List<String> plainLore = new ArrayList<>();
                    for (Component c : meta.lore()) {
                        plainLore.add(LegacyComponentSerializer.legacyAmpersand().serialize(c));
                    }
                    cfg.set(path + ".icon.lore", plainLore);
                }

                Map<Enchantment, Integer> enchants = new HashMap<>();
                if (item.getType() == Material.ENCHANTED_BOOK && meta instanceof EnchantmentStorageMeta bookMeta) {
                    enchants = bookMeta.getStoredEnchants();
                } else {
                    enchants = meta.getEnchants();
                }

                if (!enchants.isEmpty()) {
                    for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
                        String name = entry.getKey().getKey().getKey().toLowerCase();
                        cfg.set(path + ".icon.enchantments." + name, entry.getValue());
                    }
                }

                if (meta instanceof ArmorMeta armorMeta && armorMeta.hasTrim()) {
                    ArmorTrim trim = armorMeta.getTrim();
                    cfg.set(path + ".icon.trim.material", trim.getMaterial().getKey().getKey());
                    cfg.set(path + ".icon.trim.pattern", trim.getPattern().getKey().getKey());
                }
            }

            List<String> cmds = existingCommands.getOrDefault(slot, Collections.emptyList());
            cfg.set(path + ".commands", cmds);
            rewardIndex++;
        }

        try {
            cfg.save(file);
            loadCrates();
            player.sendMessage("§aSuccessfully saved and reloaded crate: §f" + crateId);
        } catch (Exception e) {
            player.sendMessage("§cFailed to save crate edits: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        Block block = event.getClickedBlock();
        if (block == null)
            return;

        String crateId = getCrateAt(block);
        if (crateId == null)
            return;

        event.setCancelled(true);
        openCrateGUI(event.getPlayer(), crateId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        // Safe async database lookup on join to prevent watchdog timeouts
        Bukkit.getAsyncScheduler().runNow(plugin, (scheduledTask) -> {
            String selectSql = "SELECT id, crate_id, amount FROM crate_pending_keys WHERE LOWER(username) = LOWER(?)";
            String deleteSql = "DELETE FROM crate_pending_keys WHERE id = ?";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement selectPs = conn.prepareStatement(selectSql)) {
                selectPs.setString(1, username);
                try (ResultSet rs = selectPs.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String crateId = rs.getString("crate_id");
                        long amount = rs.getLong("amount");

                        plugin.getKeyManager().addVirtualKeys(uuid, crateId, amount);

                        // Safe region message schedule for the player
                        player.getScheduler().run(plugin, (task) -> {
                            player.sendMessage(Component.text()
                                    .append(Component.text("CRATES ", NamedTextColor.GOLD, TextDecoration.BOLD))
                                    .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                                    .append(Component.text("You received ", NamedTextColor.WHITE))
                                    .append(Component.text(amount + "x " + formatCrateName(crateId) + " Key(s) ",
                                            NamedTextColor.YELLOW, TextDecoration.BOLD))
                                    .append(Component.text("sent to you while you were offline!", NamedTextColor.WHITE))
                                    .build());
                        }, null);

                        try (PreparedStatement deletePs = conn.prepareStatement(deleteSql)) {
                            deletePs.setInt(1, id);
                            deletePs.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                // Fail silently on MySQL offline
            }
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (event.getCurrentItem() == null)
            return;
        if (event.getView().title() == null)
            return;

        String title = plainTitle(event.getView().title());

        if (title.contains("Crate Editor:")) {
            return;
        }

        if (openCrateInvTitles.containsKey(title)) {
            event.setCancelled(true);
            String crateId = openCrateInvTitles.get(title);
            CrateConfig config = crateConfigs.get(crateId);
            if (config == null)
                return;

            int slot = event.getRawSlot();
            UUID uuid = player.getUniqueId();

            // Handle Exit Button
            int closeSlot = (config.rows() * 9) - 5;
            if (slot == closeSlot) {
                player.closeInventory();
                return;
            }

            // Handle Tripwire Balance click (Shuffling roll)
            if (slot == 4) {
                String keyItem = crateId + "_key";
                if (!plugin.getKeyManager().hasKey(player, keyItem)) {
                    player.sendMessage(Component.text("You need a ", NamedTextColor.RED)
                            .append(Component.text(formatCrateName(crateId) + " Key", NamedTextColor.GOLD,
                                    TextDecoration.BOLD))
                            .append(Component.text(" to open this crate!", NamedTextColor.RED)));
                    return;
                }
                startCrateShuffle(player, crateId, config);
                return;
            }

            // Handle Page Navigation
            int page = playerCratePage.getOrDefault(uuid, 0);
            if (slot == 30 && page > 0) {
                playerCratePage.put(uuid, page - 1);
                openCrateGUI(player, crateId);
                return;
            }
            int totalRewards = config.rewards().size();
            boolean hasNext = totalRewards > (page + 1) * 14;
            if (slot == 32 && hasNext) {
                playerCratePage.put(uuid, page + 1);
                openCrateGUI(player, crateId);
                return;
            }

            // Handle Reward Click
            CrateReward clicked = null;
            Map<Integer, CrateReward> visible = playerVisibleRewards.get(uuid);
            if (visible != null && visible.containsKey(slot)) {
                clicked = visible.get(slot);
            } else {
                for (CrateReward r : config.rewards()) {
                    if (r.slot() == slot) {
                        clicked = r;
                        break;
                    }
                }
            }

            if (clicked == null)
                return;

            player.closeInventory();
            pendingSelections.put(player.getUniqueId(), new PendingSelection(crateId, clicked));
            openConfirmGUI(player, clicked);
            return;
        }

        if (title.contains("Confirm Reward")) {
            event.setCancelled(true);
            PendingSelection pending = pendingSelections.get(player.getUniqueId());
            if (pending == null) {
                player.closeInventory();
                return;
            }

            int slot = event.getRawSlot();
            if (slot == 11) {
                player.closeInventory();
                String keyItem = pending.crateId() + "_key";
                if (!plugin.getKeyManager().consumeKey(player, keyItem)) {
                    player.sendMessage(Component.text("You need a ", NamedTextColor.RED)
                            .append(Component.text(formatCrateName(pending.crateId()) + " Key", NamedTextColor.GOLD,
                                    TextDecoration.BOLD))
                            .append(Component.text(" to open this crate!", NamedTextColor.RED)));
                    return;
                }
                player.getInventory().addItem(pending.reward().item().clone());
                List<String> commands = new ArrayList<>(pending.reward().commands());
                Bukkit.getGlobalRegionScheduler().run(plugin, (task) -> {
                    for (String cmd : commands) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                cmd.replace("%player%", player.getName()));
                    }
                });
                player.sendMessage(Component.text()
                        .append(Component.text("CRATE ", NamedTextColor.GOLD, TextDecoration.BOLD))
                        .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("You claimed your reward!", NamedTextColor.WHITE))
                        .build());
                pendingSelections.remove(player.getUniqueId());
            } else if (slot == 15) {
                player.closeInventory();
                String crateId = pending.crateId();
                pendingSelections.remove(player.getUniqueId());
                openCrateGUI(player, crateId);
            }
        }
    }

    private void openCrateGUI(Player player, String crateId) {
        CrateConfig config = crateConfigs.get(crateId);
        if (config == null)
            return;

        UUID uuid = player.getUniqueId();
        int page = playerCratePage.getOrDefault(uuid, 0);

        Component title = plugin.color(config.invTitle());
        
        // Force 4 rows if pagination is needed so navigation buttons fit perfectly
        boolean paginate = config.rewards().size() > 14;
        int rows = paginate ? 4 : config.rows();
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);

        // Stunning neon border background
        ItemStack cyanBg = makeGlass(Material.CYAN_STAINED_GLASS_PANE, " ");
        ItemStack purpleBg = makeGlass(Material.PURPLE_STAINED_GLASS_PANE, " ");
        ItemStack blackBg = makeGlass(Material.BLACK_STAINED_GLASS_PANE, " ");

        for (int i = 0; i < inv.getSize(); i++) {
            int r = i / 9;
            int c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) {
                inv.setItem(i, (i % 2 == 0) ? cyanBg : purpleBg);
            } else {
                inv.setItem(i, blackBg);
            }
        }

        // Custom key status item at top-center
        ItemStack keyItem = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta keyMeta = keyItem.getItemMeta();
        if (keyMeta != null) {
            keyMeta.displayName(plugin.color("&b&lRoll Random Reward"));
            long keyCount = plugin.getKeyManager().getVirtualKeyCount(player.getUniqueId(), crateId);
            keyMeta.lore(List.of(
                plugin.color("&7You possess: &e" + keyCount + "x &f" + formatCrateName(crateId) + " Key(s)"),
                plugin.color("&b&lClick to choose a random item!"),
                plugin.color("&e(Shuffles and picks randomly for 1 key)")
            ));
            keyItem.setItemMeta(keyMeta);
        }
        inv.setItem(4, keyItem);

        // Exit button at bottom-center (RED_STAINED_GLASS_PANE)
        ItemStack closeItem = makeGlass(Material.RED_STAINED_GLASS_PANE, "&c&lClose Crate Menu");
        inv.setItem((rows * 9) - 5, closeItem);

        Map<Integer, CrateReward> slotMap = new HashMap<>();

        if (paginate) {
            List<CrateReward> sorted = new ArrayList<>(config.rewards());
            sorted.sort(Comparator.comparingInt(CrateReward::slot));

            int startIdx = page * 14;
            int endIdx = Math.min(sorted.size(), (page + 1) * 14);

            int[] rewardSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
            int slotPtr = 0;

            for (int i = startIdx; i < endIdx; i++) {
                CrateReward reward = sorted.get(i);
                int slot = rewardSlots[slotPtr++];
                inv.setItem(slot, reward.item());
                slotMap.put(slot, reward);
            }

            // Previous Page button
            if (page > 0) {
                ItemStack prev = makeButton(Material.PINK_DYE, "&d&l« Previous Page", List.of("&7Click to go to page " + page));
                inv.setItem(30, prev);
            }

            // Next Page button
            if (endIdx < sorted.size()) {
                ItemStack next = makeButton(Material.LIME_DYE, "&a&lNext Page »", List.of("&7Click to go to page " + (page + 2)));
                inv.setItem(32, next);
            }
        } else {
            // Standard placement if total items fit on 1 page
            for (CrateReward reward : config.rewards()) {
                if (reward.slot() >= 0 && reward.slot() < inv.getSize()) {
                    inv.setItem(reward.slot(), reward.item());
                    slotMap.put(reward.slot(), reward);
                }
            }
        }

        playerVisibleRewards.put(uuid, slotMap);
        openCrateInvTitles.put(plainTitle(title), crateId);
        player.openInventory(inv);
    }

    private void openConfirmGUI(Player player, CrateReward reward) {
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text("✔ Confirm Reward", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));

        ItemStack gray = makeGlass(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++)
            inv.setItem(i, gray);

        inv.setItem(13, reward.item().clone());

        ItemStack confirm = makeButton(Material.GREEN_SHULKER_BOX, "§a§l✔ CONFIRM CLAIM",
                List.of("§7Click to claim this reward."));
        inv.setItem(11, confirm);

        ItemStack cancel = makeButton(Material.RED_SHULKER_BOX, "§c§lX CANCEL CLAIM",
                List.of("§7Go back to the crate."));
        inv.setItem(15, cancel);

        player.openInventory(inv);
    }

    public void createCrate(String crateId) {
        File file = new File(plugin.getDataFolder(), "crates.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        cfg.set(crateId + ".inventory.name", "&8Crate: " + formatCrateName(crateId));
        cfg.set(crateId + ".inventory.rows", 4);
        cfg.set(crateId + ".inventory.background-icon.material", "BLACK_STAINED_GLASS_PANE");
        cfg.set(crateId + ".inventory.background-icon.name", " ");
        cfg.set(crateId + ".rewards", new HashMap<>());

        try {
            cfg.save(file);
            loadCrates();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save new crate: " + e.getMessage());
        }
    }

    private ItemStack makeGlass(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.color(name));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack makeButton(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.color(name));
            List<Component> loreComps = new ArrayList<>();
            for (String l : lore)
                loreComps.add(plugin.color(l));
            meta.lore(loreComps);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material parseMat(String name) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return Material.STONE;
        }
    }

    private String plainTitle(Component comp) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(comp);
    }

    private void startCrateShuffle(Player player, String crateId, CrateConfig config) {
        if (config.rewards().isEmpty()) return;

        String keyItem = crateId + "_key";
        if (!plugin.getKeyManager().consumeKey(player, keyItem)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        playerVisibleRewards.put(uuid, new HashMap<>()); // disable reward clicks during roll

        List<CrateReward> rewards = new ArrayList<>(config.rewards());
        List<Integer> targetSlots = new ArrayList<>();
        
        boolean paginate = config.rewards().size() > 14;
        if (paginate) {
            int[] rewardSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
            for (int s : rewardSlots) targetSlots.add(s);
        } else {
            for (CrateReward r : config.rewards()) targetSlots.add(r.slot());
        }

        Random rand = new Random();

        player.getScheduler().runAtFixedRate(plugin, new java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>() {
            int ticks = 0;
            @Override
            public void accept(io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
                if (!player.isOnline() || player.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                    task.cancel();
                    return;
                }

                ticks++;
                Inventory inv = player.getOpenInventory().getTopInventory();

                if (ticks < 12) {
                    for (int slot : targetSlots) {
                        CrateReward randomReward = rewards.get(rand.nextInt(rewards.size()));
                        inv.setItem(slot, randomReward.item());
                    }
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.6f + (ticks * 0.1f));
                } else {
                    task.cancel();
                    CrateReward finalReward = rewards.get(rand.nextInt(rewards.size()));

                    player.getInventory().addItem(finalReward.item().clone());

                    List<String> commands = new ArrayList<>(finalReward.commands());
                    Bukkit.getGlobalRegionScheduler().run(plugin, (gt) -> {
                        for (String cmd : commands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                    cmd.replace("%player%", player.getName()));
                        }
                    });

                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    
                    Component rewardName = finalReward.item().getItemMeta() != null && finalReward.item().getItemMeta().hasDisplayName() ?
                            finalReward.item().getItemMeta().displayName() :
                            Component.text(finalReward.item().getType().toString().toLowerCase().replace('_', ' '), NamedTextColor.YELLOW);

                    player.sendMessage(Component.text()
                            .append(Component.text("CRATE ", NamedTextColor.GOLD, TextDecoration.BOLD))
                            .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("You won a ", NamedTextColor.WHITE))
                            .append(rewardName)
                            .append(Component.text("!", NamedTextColor.WHITE))
                            .build());

                    playerCratePage.put(uuid, 0); // reset page
                    openCrateGUI(player, crateId);
                }
            }
        }, () -> {}, 1, 3);
    }

    public void unloadPlayerData(UUID uuid) {
        playerCratePage.remove(uuid);
        playerVisibleRewards.remove(uuid);
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
}
