package de.winniepat.minePanel.users;

public record PanelUser(
        long id,
        String username,
        UserRole role,
        long createdAt
) {
}

