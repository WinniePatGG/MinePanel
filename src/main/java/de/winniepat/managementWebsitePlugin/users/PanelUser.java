package de.winniepat.managementWebsitePlugin.users;

public record PanelUser(
        long id,
        String username,
        UserRole role,
        long createdAt
) {
}

