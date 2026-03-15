package de.winniepat.minePanel.extensions.reports;

import java.util.UUID;

public record PlayerReport(
        long id,
        UUID reporterUuid,
        String reporterName,
        UUID suspectUuid,
        String suspectName,
        String reason,
        String status,
        long createdAt,
        String reviewedBy,
        long reviewedAt
) {
}

