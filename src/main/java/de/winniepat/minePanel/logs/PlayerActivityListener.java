package de.winniepat.minePanel.logs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.winniepat.minePanel.MinePanel;
import de.winniepat.minePanel.persistence.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public final class PlayerActivityListener implements Listener {

    private final MinePanel plugin;
    private final KnownPlayerRepository knownPlayerRepository;
    private final PlayerActivityRepository playerActivityRepository;
    private final JoinLeaveEventRepository joinLeaveEventRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public PlayerActivityListener(
            MinePanel plugin,
            KnownPlayerRepository knownPlayerRepository,
            PlayerActivityRepository playerActivityRepository,
            JoinLeaveEventRepository joinLeaveEventRepository
    ) {
        this.plugin = plugin;
        this.knownPlayerRepository = knownPlayerRepository;
        this.playerActivityRepository = playerActivityRepository;
        this.joinLeaveEventRepository = joinLeaveEventRepository;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = Instant.now().toEpochMilli();

        knownPlayerRepository.upsert(uuid, player.getName(), now);
        joinLeaveEventRepository.appendJoinEvent(uuid, player.getName(), now);

        String ip = "";
        if (player.getAddress() != null && player.getAddress().getAddress() != null) {
            ip = player.getAddress().getAddress().getHostAddress();
        }
        playerActivityRepository.onJoin(uuid, now, ip);

        if (!ip.isBlank()) {
            String ipAddress = ip;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                String country = resolveCountry(ipAddress);
                playerActivityRepository.updateCountry(uuid, country);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long now = Instant.now().toEpochMilli();
        knownPlayerRepository.upsert(player.getUniqueId(), player.getName(), now);
        playerActivityRepository.onQuit(player.getUniqueId(), now);
        joinLeaveEventRepository.appendLeaveEvent(player.getUniqueId(), player.getName(), now);
    }

    private String resolveCountry(String ipAddress) {
        if (isLocalAddress(ipAddress)) {
            return "Local";
        }

        try {
            String encodedIp = URLEncoder.encode(ipAddress, StandardCharsets.UTF_8);
            String url = "http://ip-api.com/json/" + encodedIp + "?fields=status,country";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                return "Unknown";
            }

            JsonObject payload = gson.fromJson(response.body(), JsonObject.class);
            if (payload == null || !payload.has("status")) {
                return "Unknown";
            }
            if (!"success".equalsIgnoreCase(payload.get("status").getAsString())) {
                return "Unknown";
            }
            if (!payload.has("country") || payload.get("country").isJsonNull()) {
                return "Unknown";
            }
            return payload.get("country").getAsString();
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private boolean isLocalAddress(String ipAddress) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (Exception ignored) {
            return true;
        }
    }
}

