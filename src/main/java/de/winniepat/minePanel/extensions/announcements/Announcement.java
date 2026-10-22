package de.winniepat.minePanel.extensions.announcements;

public record Announcement(
        long id,
        String message,
        boolean enabled,
        int sortOrder,
        long createdAt,
        long updatedAt
) {
}

