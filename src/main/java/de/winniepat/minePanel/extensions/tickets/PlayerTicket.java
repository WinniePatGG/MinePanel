package de.winniepat.minePanel.extensions.tickets;

import java.util.UUID;

public record PlayerTicket(
        long id,
        UUID creatorUuid,
        String creatorName,
        String category,
        String description,
        String status,
        long createdAt,
        long updatedAt,
        String handledBy,
        long handledAt
) {
}

