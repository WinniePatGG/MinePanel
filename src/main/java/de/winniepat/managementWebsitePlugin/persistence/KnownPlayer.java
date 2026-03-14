package de.winniepat.managementWebsitePlugin.persistence;

import java.util.UUID;

public record KnownPlayer(
        UUID uuid,
        String username,
        long lastSeenAt
) {
}

