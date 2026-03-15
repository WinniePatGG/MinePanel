package de.winniepat.minePanel.extensions.luckperms;

import de.winniepat.minePanel.extensions.*;
import de.winniepat.minePanel.users.PanelPermission;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

import java.util.*;
import java.util.concurrent.TimeUnit;

public final class LuckPermsExtension implements MinePanelExtension {

    private ExtensionContext context;

    @Override
    public String id() {
        return "luckperms";
    }

    @Override
    public String displayName() {
        return "LuckPerms Integration";
    }

    @Override
    public void onLoad(ExtensionContext context) {
        this.context = context;
    }

    @Override
    public void registerWebRoutes(ExtensionWebRegistry webRegistry) {
        webRegistry.get("/api/extensions/luckperms/player/:uuid", PanelPermission.VIEW_DASHBOARD, (request, response, user) -> {
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(request.params("uuid"));
            } catch (IllegalArgumentException exception) {
                return webRegistry.json(response, 400, Map.of("error", "invalid_uuid"));
            }

            if (!isLuckPermsAvailable()) {
                return webRegistry.json(response, 200, Map.of(
                        "available", false,
                        "error", "luckperms_not_present"
                ));
            }

            try {
                LuckPerms api = LuckPermsProvider.get();
                User lpUser = api.getUserManager().loadUser(playerUuid).get(3, TimeUnit.SECONDS);
                QueryOptions queryOptions = api.getContextManager()
                        .getQueryOptions(lpUser)
                        .orElse(api.getContextManager().getStaticQueryOptions());

                CachedMetaData metaData = lpUser.getCachedData().getMetaData(queryOptions);
                String prefix = sanitize(metaData.getPrefix());
                String suffix = sanitize(metaData.getSuffix());

                List<String> groups = lpUser.getInheritedGroups(queryOptions).stream()
                        .map(Group::getName)
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();

                List<String> permissions = lpUser.getCachedData().getPermissionData(queryOptions).getPermissionMap().entrySet().stream()
                        .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                        .map(Map.Entry::getKey)
                        .distinct()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();

                List<String> limitedPermissions = permissions.stream().limit(200).toList();
                boolean permissionsTruncated = permissions.size() > limitedPermissions.size();

                Map<String, Object> payload = new HashMap<>();
                payload.put("available", true);
                payload.put("primaryGroup", sanitize(lpUser.getPrimaryGroup()));
                payload.put("prefix", prefix);
                payload.put("suffix", suffix);
                payload.put("groups", groups);
                payload.put("permissions", limitedPermissions);
                payload.put("permissionsCount", permissions.size());
                payload.put("permissionsTruncated", permissionsTruncated);
                payload.put("generatedAt", System.currentTimeMillis());
                return webRegistry.json(response, 200, payload);
            } catch (Exception exception) {
                return webRegistry.json(response, 500, Map.of(
                        "available", false,
                        "error", "luckperms_lookup_failed",
                        "details", exception.getClass().getSimpleName()
                ));
            }
        });
    }

    private boolean isLuckPermsAvailable() {
        return context.plugin().getServer().getPluginManager().getPlugin("LuckPerms") != null;
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}


