package de.winniepat.minePanel.users;

import java.util.Set;

public record PanelUser(
        long id,
        String username,
        UserRole role,
        Set<PanelPermission> permissions,
        long createdAt
) {
    public boolean hasPermission(PanelPermission permission) {
        if (role == UserRole.OWNER || permissions.contains(permission)) {
            return true;
        }

        if (permission != null) {
            String name = permission.name();
            if (name.startsWith("VIEW_")) {
                String manageName = "MANAGE_" + name.substring("VIEW_".length());
                try {
                    PanelPermission managePermission = PanelPermission.valueOf(manageName);
                    if (permissions.contains(managePermission)) {
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // ignored
                }
            }
        }

        if (permission == PanelPermission.VIEW_DASHBOARD) {
            return permissions.contains(PanelPermission.ACCESS_PANEL);
        }
        if (permission == PanelPermission.VIEW_LOGS) {
            return permissions.contains(PanelPermission.VIEW_CONSOLE);
        }
        return false;
    }
}

