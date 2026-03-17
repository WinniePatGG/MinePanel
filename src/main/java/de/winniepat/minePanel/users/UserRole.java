package de.winniepat.minePanel.users;

import java.util.*;

public enum UserRole {
    OWNER(EnumSet.allOf(PanelPermission.class)),
    ADMIN(EnumSet.of(
            PanelPermission.ACCESS_PANEL,
            PanelPermission.VIEW_OVERVIEW,
            PanelPermission.VIEW_CONSOLE,
            PanelPermission.SEND_CONSOLE,
            PanelPermission.VIEW_RESOURCES,
            PanelPermission.VIEW_PLAYERS,
            PanelPermission.MANAGE_PLAYERS,
            PanelPermission.VIEW_BANS,
            PanelPermission.MANAGE_BANS,
            PanelPermission.VIEW_PLUGINS,
            PanelPermission.MANAGE_PLUGINS,
            PanelPermission.VIEW_USERS,
            PanelPermission.VIEW_DISCORD_WEBHOOK,
            PanelPermission.MANAGE_DISCORD_WEBHOOK,
            PanelPermission.VIEW_THEMES,
            PanelPermission.VIEW_EXTENSIONS,
            PanelPermission.VIEW_BACKUPS,
            PanelPermission.MANAGE_BACKUPS,
            PanelPermission.VIEW_REPORTS,
            PanelPermission.MANAGE_REPORTS,
            PanelPermission.VIEW_TICKETS,
            PanelPermission.MANAGE_TICKETS,
            PanelPermission.VIEW_PLAYER_MANAGEMENT,
            PanelPermission.MANAGE_PLAYER_MANAGEMENT,
            PanelPermission.VIEW_PLAYER_STATS,
            PanelPermission.VIEW_LUCKPERMS,
            PanelPermission.VIEW_AIRSTRIKE,
            PanelPermission.MANAGE_AIRSTRIKE
    )),
    VIEWER(EnumSet.of(
            PanelPermission.ACCESS_PANEL,
            PanelPermission.VIEW_OVERVIEW,
            PanelPermission.VIEW_CONSOLE,
            PanelPermission.VIEW_RESOURCES,
            PanelPermission.VIEW_PLAYERS,
            PanelPermission.VIEW_BANS,
            PanelPermission.VIEW_PLUGINS,
            PanelPermission.VIEW_THEMES,
            PanelPermission.VIEW_BACKUPS,
            PanelPermission.VIEW_REPORTS,
            PanelPermission.VIEW_TICKETS,
            PanelPermission.VIEW_PLAYER_MANAGEMENT,
            PanelPermission.VIEW_PLAYER_STATS,
            PanelPermission.VIEW_LUCKPERMS,
            PanelPermission.VIEW_AIRSTRIKE
    ));

    private final Set<PanelPermission> permissions;

    UserRole(Set<PanelPermission> permissions) {
        this.permissions = permissions;
    }

    public boolean hasPermission(PanelPermission permission) {
        return permissions.contains(permission);
    }

    public Set<PanelPermission> defaultPermissions() {
        return EnumSet.copyOf(permissions);
    }

    public static UserRole fromString(String raw) {
        return UserRole.valueOf(raw.toUpperCase(Locale.ROOT));
    }
}

