package de.winniepat.minePanel.extensions.maintenance;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;
import org.bukkit.event.HandlerList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MaintenanceExtension implements MinePanelExtension {

    private final Gson gson = new Gson();

    private ExtensionContext context;
    private MaintenanceService maintenanceService;
    private MaintenanceJoinListener joinListener;
    private MaintenanceMotdListener motdListener;

    @Override
    public String id() {
        return "maintenance";
    }

    @Override
    public String displayName() {
        return "Maintenance";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        this.maintenanceService = new MaintenanceService(context);
    }

    @Override
    public void onEnable() {
        this.joinListener = new MaintenanceJoinListener(maintenanceService);
        this.motdListener = new MaintenanceMotdListener(maintenanceService);
        context.plugin().getServer().getPluginManager().registerEvents(joinListener, context.plugin());
        context.plugin().getServer().getPluginManager().registerEvents(motdListener, context.plugin());
    }

    @Override
    public void onDisable() {
        if (joinListener != null) {
            HandlerList.unregisterAll(joinListener);
            joinListener = null;
        }
        if (motdListener != null) {
            HandlerList.unregisterAll(motdListener);
            motdListener = null;
        }
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/maintenance/status", PanelPermission.VIEW_MAINTENANCE, (request, response, user) -> {
            MaintenanceService.MaintenanceSnapshot snapshot = maintenanceService.snapshot();
            return webRegistry.json(response, 200, Map.of(
                    "enabled", snapshot.enabled(),
                    "reason", snapshot.reason(),
                    "motd", snapshot.motd(),
                    "changedBy", snapshot.changedBy(),
                    "changedAt", snapshot.changedAt(),
                    "affectedOnlinePlayers", snapshot.affectedOnlinePlayers(),
                    "bypassPermission", MaintenanceService.BYPASS_PERMISSION
            ));
        });

        webRegistry.post("/api/extensions/maintenance/enable", PanelPermission.MANAGE_MAINTENANCE, (request, response, user) -> {
            TogglePayload payload = gson.fromJson(request.body(), TogglePayload.class);
            String reason = payload != null ? payload.reason() : null;
            String motd = payload != null ? payload.motd() : null;
            boolean kickNonStaff = payload != null && Boolean.TRUE.equals(payload.kickNonStaff());

            int kicked;
            try {
                kicked = maintenanceService.enable(user.username(), reason, motd, kickNonStaff);
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "maintenance_enable_failed", "details", exception.getMessage()));
            }
            context.panelLogger().log(
                    "AUDIT",
                    user.username(),
                    "Enabled maintenance mode" + (kickNonStaff ? " and kicked " + kicked + " player(s)" : "")
            );

            return webRegistry.json(response, 200, toStatusPayload(true, kicked));
        });

        webRegistry.post("/api/extensions/maintenance/disable", PanelPermission.MANAGE_MAINTENANCE, (request, response, user) -> {
            maintenanceService.disable(user.username());
            context.panelLogger().log("AUDIT", user.username(), "Disabled maintenance mode");
            return webRegistry.json(response, 200, toStatusPayload(true, 0));
        });

        webRegistry.post("/api/extensions/maintenance/kick-nonstaff", PanelPermission.MANAGE_MAINTENANCE, (request, response, user) -> {
            if (!maintenanceService.isEnabled()) {
                return webRegistry.json(response, 400, Map.of("error", "maintenance_not_enabled"));
            }

            int kicked;
            try {
                kicked = maintenanceService.kickNonStaffPlayers();
            } catch (IllegalStateException exception) {
                return webRegistry.json(response, 500, Map.of("error", "maintenance_kick_failed", "details", exception.getMessage()));
            }
            context.panelLogger().log("AUDIT", user.username(), "Kicked " + kicked + " non-staff player(s) during maintenance");
            return webRegistry.json(response, 200, toStatusPayload(true, kicked));
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Maintenance", "/dashboard/maintenance"));
    }

    private Map<String, Object> toStatusPayload(boolean ok, int kicked) {
        MaintenanceService.MaintenanceSnapshot snapshot = maintenanceService.snapshot();
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", ok);
        payload.put("enabled", snapshot.enabled());
        payload.put("reason", snapshot.reason());
        payload.put("motd", snapshot.motd());
        payload.put("changedBy", snapshot.changedBy());
        payload.put("changedAt", snapshot.changedAt());
        payload.put("affectedOnlinePlayers", snapshot.affectedOnlinePlayers());
        payload.put("kicked", kicked);
        payload.put("bypassPermission", MaintenanceService.BYPASS_PERMISSION);
        return payload;
    }

    private record TogglePayload(String reason, String motd, Boolean kickNonStaff) {
    }
}

