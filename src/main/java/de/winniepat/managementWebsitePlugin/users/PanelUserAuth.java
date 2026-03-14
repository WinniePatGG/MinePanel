package de.winniepat.managementWebsitePlugin.users;

public record PanelUserAuth(
        PanelUser user,
        String passwordHash
) {
}

