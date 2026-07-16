package dev.neon.crates.placeholder;

import dev.neon.crates.NeonCrates;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CratesPlaceholderExpansion extends PlaceholderExpansion {

    private final NeonCrates plugin;

    public CratesPlaceholderExpansion(NeonCrates plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "neoncrates";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Neon";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "0";
        }

        // Param format expected: <crateId>_bal
        if (params.endsWith("_bal")) {
            String crateId = params.substring(0, params.length() - 4);
            long amount = plugin.getKeyManager().getVirtualKeyCount(player.getUniqueId(), crateId);
            return String.valueOf(amount);
        }

        return null;
    }
}
