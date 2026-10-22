package de.winniepat.minePanel.extensions.worldbackups;

import com.google.gson.Gson;
import de.winniepat.minePanel.extensions.ExtensionContext;
import de.winniepat.minePanel.extensions.ExtensionNavigationTab;
import de.winniepat.minePanel.extensions.ExtensionWebRegistry;
import de.winniepat.minePanel.extensions.MinePanelExtension;
import de.winniepat.minePanel.users.PanelPermission;

import java.nio.file.Path;
import java.util.*;

public final class WorldBackupsExtension implements MinePanelExtension {

    private final Gson gson = new Gson();

    private ExtensionContext context;
    private WorldBackupService backupService;

    @Override
    public String id() {
        return "world-backups";
    }

    @Override
    public String displayName() {
        return "World Backups";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
        Path backupRoot = context.plugin().getDataFolder().toPath().resolve("backups");
        this.backupService = new WorldBackupService(context.plugin(), backupRoot);
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/world-backups", PanelPermission.VIEW_BACKUPS, (request, response, user) -> {
            String world = request.queryParams("world");
            if (world != null && !world.isBlank()) {
                List<Map<String, Object>> backups = backupService.listBackups(world).stream().map(this::toPayload).toList();
                return webRegistry.json(response, 200, Map.of("world", world, "backups", backups));
            }

            Map<String, Object> payload = new HashMap<>();
            for (String worldKey : backupService.supportedWorldKeys()) {
                List<Map<String, Object>> backups = backupService.listBackups(worldKey).stream().map(this::toPayload).toList();
                payload.put(worldKey, backups);
            }

            payload.put("worlds", backupService.supportedWorldKeys());
            return webRegistry.json(response, 200, payload);
        });

        webRegistry.post("/api/extensions/world-backups/create", PanelPermission.MANAGE_BACKUPS, (request, response, user) -> {
            CreateBackupPayload payload = gson.fromJson(request.body(), CreateBackupPayload.class);
            if (payload == null || isBlank(payload.world()) || isBlank(payload.name())) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            WorldBackupService.BackupCreateResult result = backupService.createBackup(payload.world(), payload.name());
            if (!result.success()) {
                Map<String, Object> errorPayload = new HashMap<>();
                errorPayload.put("error", result.error());
                if (!isBlank(result.details())) {
                    errorPayload.put("details", result.details());
                }

                context.plugin().getLogger().warning("World backup failed for world='" + payload.world()
                        + "', name='" + payload.name() + "': "
                        + (isBlank(result.details()) ? result.error() : result.details()));

                int status;
                if ("invalid_world".equals(result.error()) || "invalid_name".equals(result.error())) {
                    status = 400;
                } else if ("world_not_found".equals(result.error())) {
                    status = 404;
                } else {
                    status = 500;
                }
                return webRegistry.json(response, status, errorPayload);
            }

            context.panelLogger().log("AUDIT", user.username(), "Created " + result.backup().world() + " backup: " + result.backup().fileName());
            return webRegistry.json(response, 200, Map.of("ok", true, "backup", toPayload(result.backup())));
        });

        webRegistry.post("/api/extensions/world-backups/delete", PanelPermission.MANAGE_BACKUPS, (request, response, user) -> {
            DeleteBackupPayload payload = gson.fromJson(request.body(), DeleteBackupPayload.class);
            if (payload == null || isBlank(payload.world()) || isBlank(payload.fileName())) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_payload"));
            }

            WorldBackupService.BackupDeleteResult result = backupService.deleteBackup(payload.world(), payload.fileName());
            if (!result.success()) {
                Map<String, Object> errorPayload = new HashMap<>();
                errorPayload.put("error", result.error());
                if (!isBlank(result.details())) {
                    errorPayload.put("details", result.details());
                }

                int status;
                if ("invalid_world".equals(result.error()) || "invalid_file_name".equals(result.error())) {
                    status = 400;
                } else if ("backup_not_found".equals(result.error())) {
                    status = 404;
                } else {
                    status = 500;
                }
                return webRegistry.json(response, status, errorPayload);
            }

            context.panelLogger().log("AUDIT", user.username(), "Deleted " + result.world() + " backup: " + result.fileName());
            return webRegistry.json(response, 200, Map.of("ok", true, "fileName", result.fileName(), "world", result.world()));
        });
    }

    @Override
    public List<ExtensionNavigationTab> navigationTabs() {
        return List.of(new ExtensionNavigationTab("server", "Backups", "/dashboard/world-backups"));
    }

    private Map<String, Object> toPayload(WorldBackupService.WorldBackupEntry backup) {
        return Map.of(
                "fileName", backup.fileName(),
                "name", backup.name(),
                "createdAt", backup.createdAt(),
                "sizeBytes", backup.sizeBytes(),
                "world", backup.world()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CreateBackupPayload(String world, String name) {
    }

    private record DeleteBackupPayload(String world, String fileName) {
    }
}


