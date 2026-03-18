package de.winniepat.minePanel.extensions.whitelist;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;

import java.util.List;
import java.util.Map;

public final class WhitelistExtension implements MinePanelExtension {

    private final Gson gson = new Gson();

    private ExtensionContext context;
    private WhitelistService whitelistService;

    @Override
    public String id() {
        return "whitelist";
    }

    @Override
    public String displayName() {
        return "Whitelist";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        this.whitelistService = new WhitelistService(context);
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/whitelist/status", PanelPermission.VIEW_WHITELIST, (request, response, user) -> {
            try {
                boolean enabled = whitelistService.isWhitelistEnabled();
                return webRegistry.json(response, 200, Map.of("enabled", enabled));
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "whitelist_status_failed", "details", exception.getMessage()));
            }
        });

        webRegistry.post("/api/extensions/whitelist/toggle", PanelPermission.MANAGE_WHITELIST, (request, response, user) -> {
            TogglePayload payload = gson.fromJson(request.body(), TogglePayload.class);
            if (payload == null || payload.enabled() == null) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            boolean changed;
            try {
                changed = whitelistService.setWhitelistEnabled(payload.enabled());
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "whitelist_toggle_failed", "details", exception.getMessage()));
            }

            context.panelLogger().log(
                    "AUDIT",
                    user.username(),
                    (payload.enabled() ? "Enabled" : "Disabled") + " server whitelist" + (changed ? "" : " (no change)")
            );
            return webRegistry.json(response, 200, Map.of("ok", true, "enabled", payload.enabled(), "changed", changed));
        });

        webRegistry.get("/api/extensions/whitelist", PanelPermission.VIEW_WHITELIST, (request, response, user) -> {
            try {
                List<Map<String, Object>> entries = whitelistService.listEntries();
                return webRegistry.json(response, 200, Map.of("entries", entries, "count", entries.size()));
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "whitelist_list_failed", "details", exception.getMessage()));
            }
        });

        webRegistry.post("/api/extensions/whitelist/add", PanelPermission.MANAGE_WHITELIST, (request, response, user) -> {
            UsernamePayload payload = gson.fromJson(request.body(), UsernamePayload.class);
            String username = payload == null ? null : payload.username();

            WhitelistService.ChangeResult result;
            try {
                result = whitelistService.addByUsername(username);
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "whitelist_add_failed", "details", exception.getMessage()));
            }

            if (!result.success()) {
                return webRegistry.json(response, 400, Map.of("error", result.error()));
            }

            context.panelLogger().log("AUDIT", user.username(),
                    (result.changed() ? "Added " : "Kept ") + result.username() + " on whitelist");
            return webRegistry.json(response, 200, Map.of(
                    "ok", true,
                    "changed", result.changed(),
                    "uuid", result.uuid() == null ? "" : result.uuid().toString(),
                    "username", result.username()
            ));
        });

        webRegistry.post("/api/extensions/whitelist/remove", PanelPermission.MANAGE_WHITELIST, (request, response, user) -> {
            UsernamePayload payload = gson.fromJson(request.body(), UsernamePayload.class);
            String username = payload == null ? null : payload.username();

            WhitelistService.ChangeResult result;
            try {
                result = whitelistService.removeByUsername(username);
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "whitelist_remove_failed", "details", exception.getMessage()));
            }

            if (!result.success()) {
                int status = "not_whitelisted".equals(result.error()) ? 404 : 400;
                return webRegistry.json(response, status, Map.of("error", result.error()));
            }

            context.panelLogger().log("AUDIT", user.username(), "Removed " + result.username() + " from whitelist");
            return webRegistry.json(response, 200, Map.of(
                    "ok", true,
                    "changed", result.changed(),
                    "uuid", result.uuid() == null ? "" : result.uuid().toString(),
                    "username", result.username()
            ));
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Whitelist", "/dashboard/whitelist"));
    }

    private record UsernamePayload(String username) {
    }

    private record TogglePayload(Boolean enabled) {
    }
}

