package dev.neonjava.neoncrates.telemetry;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class PluginMetrics {

    private final JavaPlugin plugin;
    private final String endpointUrl;
    private static String serverId;

    public PluginMetrics(JavaPlugin plugin, String endpointUrl) {
        this.plugin = plugin;
        this.endpointUrl = endpointUrl;

        if (serverId == null) {
            serverId = plugin.getConfig().getString("server-id");
            if (serverId == null || serverId.isEmpty() || serverId.equals("auto")) {
                serverId = UUID.randomUUID().toString();
                plugin.getConfig().set("server-id", serverId);
                plugin.saveConfig();
            }
        }
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::sendPing, 20L, 300 * 20L); // Ping every 5 minutes
    }

    private void sendPing() {
        try {
            URL url = new URL(endpointUrl + "/api/telemetry/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", plugin.getName() + "-Plugin");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String jsonPayload = String.format(
                    "{\"pluginName\":\"%s\",\"pluginVersion\":\"%s\",\"serverId\":\"%s\",\"serverVersion\":\"%s\",\"onlinePlayers\":%d,\"maxPlayers\":%d,\"port\":%d}",
                    escapeJson(plugin.getName()),
                    escapeJson(plugin.getDescription().getVersion()),
                    escapeJson(serverId),
                    escapeJson(Bukkit.getVersion()),
                    Bukkit.getOnlinePlayers().size(),
                    Bukkit.getMaxPlayers(),
                    Bukkit.getPort()
            );

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            conn.getResponseCode();
        } catch (Exception ignored) {
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
