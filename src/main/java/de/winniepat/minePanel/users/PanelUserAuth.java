package de.winniepat.minePanel.users;

public record PanelUserAuth(
        PanelUser user,
        String passwordHash
) {
}

