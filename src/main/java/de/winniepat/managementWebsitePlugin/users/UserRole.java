package de.winniepat.managementWebsitePlugin.users;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum UserRole {
    OWNER(EnumSet.allOf(PanelPermission.class)),
    ADMIN(EnumSet.of(PanelPermission.VIEW_DASHBOARD, PanelPermission.VIEW_LOGS, PanelPermission.SEND_CONSOLE, PanelPermission.MANAGE_USERS)),
    VIEWER(EnumSet.of(PanelPermission.VIEW_DASHBOARD, PanelPermission.VIEW_LOGS));

    private final Set<PanelPermission> permissions;

    UserRole(Set<PanelPermission> permissions) {
        this.permissions = permissions;
    }

    public boolean hasPermission(PanelPermission permission) {
        return permissions.contains(permission);
    }

    public static UserRole fromString(String raw) {
        return UserRole.valueOf(raw.toUpperCase(Locale.ROOT));
    }
}

