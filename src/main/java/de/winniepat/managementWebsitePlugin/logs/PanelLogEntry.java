package de.winniepat.managementWebsitePlugin.logs;

public record PanelLogEntry(
        long id,
        String kind,
        String source,
        String message,
        long createdAt
) {
}

