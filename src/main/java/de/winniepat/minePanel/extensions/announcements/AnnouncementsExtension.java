package de.winniepat.minePanel.extensions.announcements;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;

import java.util.List;
import java.util.Map;

public final class AnnouncementsExtension implements MinePanelExtension {

    private final Gson gson = new Gson();

    private ExtensionContext context;
    private AnnouncementService service;

    @Override
    public String id() {
        return "announcements";
    }

    @Override
    public String displayName() {
        return "Announcements";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        AnnouncementRepository repository = new AnnouncementRepository(context.database());
        repository.initializeSchema();
        this.service = new AnnouncementService(context, repository);
    }

    @Override
    public void onEnable() {
        service.start();
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/announcements", PanelPermission.VIEW_ANNOUNCEMENTS, (request, response, user) -> {
            AnnouncementService.AnnouncementState state = service.state();
            List<Map<String, Object>> messages = state.messages().stream()
                    .map(message -> Map.<String, Object>of(
                            "id", message.id(),
                            "message", message.message(),
                            "enabled", message.enabled(),
                            "sortOrder", message.sortOrder(),
                            "createdAt", message.createdAt(),
                            "updatedAt", message.updatedAt()
                    ))
                    .toList();

            return webRegistry.json(response, 200, Map.of(
                    "enabled", state.enabled(),
                    "intervalSeconds", state.intervalSeconds(),
                    "nextRunAt", state.nextRunAt(),
                    "messages", messages
            ));
        });

        webRegistry.post("/api/extensions/announcements/config", PanelPermission.MANAGE_ANNOUNCEMENTS, (request, response, user) -> {
            ConfigPayload payload = gson.fromJson(request.body(), ConfigPayload.class);
            if (payload == null || payload.enabled() == null || payload.intervalSeconds() == null) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            service.updateConfig(payload.enabled(), payload.intervalSeconds());
            context.panelLogger().log("AUDIT", user.username(), "Updated announcements configuration");
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.post("/api/extensions/announcements/messages", PanelPermission.MANAGE_ANNOUNCEMENTS, (request, response, user) -> {
            MessagePayload payload = gson.fromJson(request.body(), MessagePayload.class);
            String message = payload == null ? "" : sanitizeMessage(payload.message());
            if (message.isBlank()) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_message"));
            }

            long id = service.addMessage(message);
            if (id <= 0) {
                return webRegistry.json(response, 500, Map.of("error", "create_failed"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Added announcement #" + id);
            return webRegistry.json(response, 201, Map.of("ok", true, "id", id));
        });

        webRegistry.post("/api/extensions/announcements/messages/:id/delete", PanelPermission.MANAGE_ANNOUNCEMENTS, (request, response, user) -> {
            long id = parseId(request.params("id"));
            if (id <= 0) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_message_id"));
            }

            if (!service.deleteMessage(id)) {
                return webRegistry.json(response, 404, Map.of("error", "message_not_found"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Deleted announcement #" + id);
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.post("/api/extensions/announcements/messages/:id/toggle", PanelPermission.MANAGE_ANNOUNCEMENTS, (request, response, user) -> {
            long id = parseId(request.params("id"));
            TogglePayload payload = gson.fromJson(request.body(), TogglePayload.class);
            if (id <= 0 || payload == null || payload.enabled() == null) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            if (!service.setMessageEnabled(id, payload.enabled())) {
                return webRegistry.json(response, 404, Map.of("error", "message_not_found"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Set announcement #" + id + " enabled=" + payload.enabled());
            return webRegistry.json(response, 200, Map.of("ok", true));
        });

        webRegistry.post("/api/extensions/announcements/send-now", PanelPermission.MANAGE_ANNOUNCEMENTS, (request, response, user) -> {
            if (!service.sendNow()) {
                return webRegistry.json(response, 400, Map.of("error", "no_enabled_messages"));
            }

            context.panelLogger().log("AUDIT", user.username(), "Sent announcement manually");
            return webRegistry.json(response, 200, Map.of("ok", true));
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Announcements", "/dashboard/announcements"));
    }

    private long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String sanitizeMessage(String raw) {
        if (raw == null) {
            return "";
        }

        String trimmed = raw.trim();
        if (trimmed.length() > 220) {
            return trimmed.substring(0, 220);
        }
        return trimmed;
    }

    private record ConfigPayload(Boolean enabled, Integer intervalSeconds) {
    }

    private record MessagePayload(String message) {
    }

    private record TogglePayload(Boolean enabled) {
    }
}

