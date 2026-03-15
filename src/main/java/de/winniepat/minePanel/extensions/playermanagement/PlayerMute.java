package de.winniepat.minePanel.extensions.playermanagement;

import java.util.UUID;

public record PlayerMute(
        UUID uuid,
        String username,
        String reason,
        String mutedBy,
        long mutedAt,
        Long expiresAt
) {
    public boolean isExpired(long nowMillis) {
        return expiresAt != null && expiresAt > 0 && nowMillis >= expiresAt;
    }

    public boolean isActive(long nowMillis) {
        return !isExpired(nowMillis);
    }
}

