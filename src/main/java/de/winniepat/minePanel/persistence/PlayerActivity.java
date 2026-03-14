package de.winniepat.minePanel.persistence;

import java.util.UUID;

public record PlayerActivity(
        UUID uuid,
        long firstJoined,
        long lastSeen,
        long totalPlaytimeSeconds,
        long totalSessions,
        long currentSessionStart,
        String lastIp,
        String lastCountry
) {
}

