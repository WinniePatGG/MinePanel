package de.winniepat.minePanel.persistence;

import java.sql.*;
import java.util.Optional;

public final class ExtensionSettingsRepository {

    private final Database database;

    public ExtensionSettingsRepository(Database database) {
        this.database = database;
    }

    public Optional<String> findSettingsJson(String extensionId) {
        String normalizedId = normalizeExtensionId(extensionId);
        if (normalizedId.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT settings_json FROM extension_settings WHERE extension_id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString("settings_json"));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read extension settings", exception);
        }
    }

    public void saveSettingsJson(String extensionId, String settingsJson, long updatedAtMillis) {
        String normalizedId = normalizeExtensionId(extensionId);
        if (normalizedId.isBlank()) {
            throw new IllegalArgumentException("invalid_extension_id");
        }

        String sql = "INSERT INTO extension_settings(extension_id, settings_json, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT(extension_id) DO UPDATE SET "
                + "settings_json = excluded.settings_json, "
                + "updated_at = excluded.updated_at";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedId);
            statement.setString(2, settingsJson == null || settingsJson.isBlank() ? "{}" : settingsJson);
            statement.setLong(3, Math.max(0L, updatedAtMillis));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save extension settings", exception);
        }
    }

    private String normalizeExtensionId(String extensionId) {
        if (extensionId == null || extensionId.isBlank()) {
            return "";
        }
        return extensionId.trim().toLowerCase();
    }
}

