package dev.neon.crates;

import dev.neon.crates.NeonCrates;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

public class CrateCommand implements CommandExecutor, TabCompleter, Listener {

    private final NeonCrates plugin;
    private final Map<UUID, String> pendingBind = new HashMap<>();
    private final Set<UUID> pendingUnbind = new HashSet<>();

    public CrateCommand(NeonCrates plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("neoncrates.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /crate create <crateId>");
                    return true;
                }
                String crateId = args[1];
                if (plugin.getCrateManager().getCrateIds().contains(crateId)) {
                    sender.sendMessage(Component.text("Crate already exists.", NamedTextColor.RED));
                    return true;
                }
                plugin.getCrateManager().createCrate(crateId);
                sender.sendMessage(Component.text("Successfully created new crate: " + crateId + ". Use /crate edit " + crateId + " to customize rewards.", NamedTextColor.GREEN));
            }

            case "bind" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /crate bind <crateName>");
                    return true;
                }
                String crateId = args[1];
                if (!plugin.getCrateManager().getCrateIds().contains(crateId)) {
                    sender.sendMessage(Component.text("Unknown crate: " + crateId
                            + ". Available: " + plugin.getCrateManager().getCrateIds(), NamedTextColor.RED));
                    return true;
                }
                pendingBind.put(player.getUniqueId(), crateId);
                player.sendMessage(Component.text("Right-click a block to bind it as the ", NamedTextColor.YELLOW)
                        .append(Component.text(crateId, NamedTextColor.GOLD))
                        .append(Component.text(" crate.", NamedTextColor.YELLOW)));
            }

            case "unbind" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only.");
                    return true;
                }
                pendingUnbind.add(player.getUniqueId());
                player.sendMessage(Component.text("Right-click a block to unbind it.", NamedTextColor.YELLOW));
            }

            case "give", "givekey" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /crate givekey <player> <crate> [amount]");
                    return true;
                }
                String targetName = args[1];
                String crateId = args[2];
                int amount = args.length >= 4 ? parseInt(args[3], 1) : 1;

                if (!plugin.getCrateManager().getCrateIds().contains(crateId)) {
                    sender.sendMessage(Component.text("Unknown crate: " + crateId, NamedTextColor.RED));
                    return true;
                }

                Player target = plugin.getServer().getPlayer(targetName);
                if (target != null) {
                    plugin.getKeyManager().addVirtualKeys(target.getUniqueId(), crateId, amount);
                    sender.sendMessage(Component.text("Given " + amount + " virtual " + crateId + " key(s) to online player "
                            + target.getName(), NamedTextColor.GREEN));
                } else {
                    // Resolve database checks asynchronously
                    Bukkit.getAsyncScheduler().runNow(plugin, (scheduledTask) -> {
                        UUID resolvedUuid = plugin.getDatabaseManager().getUUIDByNameFlexible(targetName);
                        if (resolvedUuid != null) {
                            plugin.getKeyManager().addVirtualKeys(resolvedUuid, crateId, amount);
                            sender.sendMessage(Component.text("Given " + amount + " virtual " + crateId + " key(s) to offline player "
                                    + targetName, NamedTextColor.GREEN));
                        } else {
                            String sql = "INSERT INTO crate_pending_keys (username, crate_id, amount) VALUES (?, ?, ?)";
                            try (Connection conn = plugin.getDatabaseManager().getConnection();
                                 PreparedStatement ps = conn.prepareStatement(sql)) {
                                ps.setString(1, targetName);
                                ps.setString(2, crateId);
                                ps.setLong(3, amount);
                                ps.executeUpdate();
                                sender.sendMessage(Component.text("Queued " + amount + " virtual " + crateId + " key(s) for offline player "
                                        + targetName, NamedTextColor.GREEN));
                            } catch (SQLException e) {
                                sender.sendMessage(Component.text("Database connection unavailable. Failed to queue keys.", NamedTextColor.RED));
                            }
                        }
                    });
                }
            }

            case "keyall" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /crate keyall <crate>");
                    return true;
                }
                String crateId = args[1];
                int count = 0;
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    plugin.getKeyManager().addVirtualKeys(p.getUniqueId(), crateId, 1);
                    count++;
                }
                sender.sendMessage(Component.text("Gave 1 virtual " + crateId + " key to " + count + " player(s).", NamedTextColor.GREEN));
            }

            case "edit" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cPlayers only.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /crate edit <crateName>");
                    return true;
                }
                String crateId = args[1];
                if (!plugin.getCrateManager().getCrateIds().contains(crateId)) {
                    sender.sendMessage(Component.text("Unknown crate: " + crateId, NamedTextColor.RED));
                    return true;
                }
                plugin.getCrateManager().openCrateEditor(player, crateId);
            }

            case "reload" -> {
                plugin.getCrateManager().loadCrates();
                sender.sendMessage(Component.text("Crates configuration reloaded.", NamedTextColor.GREEN));
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();

        if (pendingBind.containsKey(uid)) {
            event.setCancelled(true);
            String crateId = pendingBind.remove(uid);
            if (plugin.getCrateManager().bindBlock(block, crateId)) {
                player.sendMessage(Component.text("Block bound to crate: " + crateId, NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Failed to bind block.", NamedTextColor.RED));
            }
        } else if (pendingUnbind.remove(uid)) {
            event.setCancelled(true);
            if (plugin.getCrateManager().unbindBlock(block)) {
                player.sendMessage(Component.text("Block unbound.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("That block is not bound to a crate.", NamedTextColor.RED));
            }
        }
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§6NeonCrates Commands:");
        s.sendMessage("§e/crate create <crate>     §7— create new empty crate config");
        s.sendMessage("§e/crate bind <crate>       §7— bind right-clicked block");
        s.sendMessage("§e/crate unbind              §7— unbind right-clicked block");
        s.sendMessage("§e/crate givekey <p> <c> [n] §7— give keys (offline supported)");
        s.sendMessage("§e/crate keyall <crate>      §7— give key to all online players");
        s.sendMessage("§e/crate edit <crate>        §7— edit crate rewards in GUI");
        s.sendMessage("§e/crate reload              §7— reload config files");
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        if (args.length == 1)
            return Arrays.asList("create", "bind", "unbind", "give", "givekey", "keyall", "reload", "edit");
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givekey"))) {
            List<String> list = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                list.add(p.getName());
            }
            return list;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givekey"))) {
            return new ArrayList<>(plugin.getCrateManager().getCrateIds());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("bind") || args[0].equalsIgnoreCase("keyall") || args[0].equalsIgnoreCase("edit"))) {
            return new ArrayList<>(plugin.getCrateManager().getCrateIds());
        }
        return Collections.emptyList();
    }
}
