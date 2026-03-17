package de.winniepat.minePanel.extensions.playermanagement;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.persistence.KnownPlayer;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerManagementExtension implements MinePanelExtension {

    private final Gson gson = new Gson();
    private ExtensionContext context;
    private PlayerMuteRepository muteRepository;
    private PlayerMuteListener muteListener;

    @Override
    public String id() {
        return "player-management";
    }

    @Override
    public String displayName() {
        return "Player Management";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        this.muteRepository = new PlayerMuteRepository(context.database());
        this.muteRepository.initializeSchema();
    }

    @Override
    public void onEnable() {
        muteRepository.clearExpired(Instant.now().toEpochMilli());
        muteListener = new PlayerMuteListener(muteRepository);
        context.plugin().getServer().getPluginManager().registerEvents(muteListener, context.plugin());
    }

    @Override
    public void onDisable() {
        if (muteListener != null) {
            HandlerList.unregisterAll(muteListener);
            muteListener = null;
        }
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.post("/api/extensions/player-management/mute", PanelPermission.MANAGE_PLAYER_MANAGEMENT, (request, response, user) -> {
            MutePayload payload = gson.fromJson(request.body(), MutePayload.class);
            if (payload == null || (isBlank(payload.uuid()) && isBlank(payload.username()))) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            Optional<KnownPlayer> target = resolveTarget(payload.uuid(), payload.username());
            if (target.isEmpty()) {
                return webRegistry.json(response, 404, Map.of("error", "player_not_found"));
            }

            int minutes = payload.durationMinutes() == null ? 0 : Math.max(0, Math.min(43_200, payload.durationMinutes()));
            String reason = isBlank(payload.reason()) ? "Muted by panel moderator" : payload.reason().trim();
            long now = Instant.now().toEpochMilli();
            Long expiresAt = minutes <= 0 ? null : now + minutes * 60_000L;

            muteRepository.upsertMute(target.get().uuid(), target.get().username(), reason, user.username(), now, expiresAt);
            context.panelLogger().log("AUDIT", user.username(), "Muted " + target.get().username() + (minutes > 0 ? " for " + minutes + " minute(s)" : " permanently") + ": " + reason);

            Player online = context.plugin().getServer().getPlayer(target.get().uuid());
            if (online != null) {
                online.sendMessage("You were muted by a moderator. Reason: " + reason);
            }

            java.util.Map<String, Object> resultPayload = new java.util.HashMap<>();
            resultPayload.put("ok", true);
            resultPayload.put("uuid", target.get().uuid().toString());
            resultPayload.put("username", target.get().username());
            resultPayload.put("expiresAt", expiresAt);
            return webRegistry.json(response, 200, resultPayload);
        });

        webRegistry.post("/api/extensions/player-management/unmute", PanelPermission.MANAGE_PLAYER_MANAGEMENT, (request, response, user) -> {
            UnmutePayload payload = gson.fromJson(request.body(), UnmutePayload.class);
            if (payload == null || (isBlank(payload.uuid()) && isBlank(payload.username()))) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            Optional<KnownPlayer> target = resolveTarget(payload.uuid(), payload.username());
            if (target.isEmpty()) {
                return webRegistry.json(response, 404, Map.of("error", "player_not_found"));
            }

            boolean removed = muteRepository.removeMute(target.get().uuid());
            if (!removed) {
                return webRegistry.json(response, 404, Map.of("error", "player_not_muted"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Unmuted " + target.get().username());
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.get("/api/extensions/player-management/mute/:uuid", PanelPermission.VIEW_PLAYER_MANAGEMENT, (request, response, user) -> {
            UUID uuid;
            try {
                uuid = UUID.fromString(request.params("uuid"));
            } catch (IllegalArgumentException exception) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_uuid"));
            }

            muteRepository.clearExpired(Instant.now().toEpochMilli());
            Optional<PlayerMute> mute = muteRepository.findByUuid(uuid);
            if (mute.isEmpty()) {
                return webRegistry.json(response, 200, Map.of("muted", false));
            }

            return webRegistry.json(response, 200, Map.of(
                    "muted", true,
                    "username", mute.get().username(),
                    "reason", mute.get().reason(),
                    "mutedBy", mute.get().mutedBy(),
                    "mutedAt", mute.get().mutedAt(),
                    "expiresAt", mute.get().expiresAt()
            ));
        });
    }

    private Optional<KnownPlayer> resolveTarget(String rawUuid, String rawUsername) {
        if (!isBlank(rawUuid)) {
            try {
                UUID uuid = UUID.fromString(rawUuid.trim());
                Optional<KnownPlayer> known = context.knownPlayerRepository().findByUuid(uuid);
                if (known.isPresent()) {
                    return known;
                }
            } catch (IllegalArgumentException ignored) {
                // Fallback to username lookup.
            }
        }

        if (!isBlank(rawUsername)) {
            Optional<KnownPlayer> knownByName = context.knownPlayerRepository().findByUsername(rawUsername.trim());
            if (knownByName.isPresent()) {
                return knownByName;
            }

            Player online = context.plugin().getServer().getPlayerExact(rawUsername.trim());
            if (online != null) {
                long now = Instant.now().toEpochMilli();
                context.knownPlayerRepository().upsert(online.getUniqueId(), online.getName(), now);
                return context.knownPlayerRepository().findByUuid(online.getUniqueId());
            }
        }

        return Optional.empty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record MutePayload(String uuid, String username, Integer durationMinutes, String reason) {
    }

    private record UnmutePayload(String uuid, String username) {
    }
}

