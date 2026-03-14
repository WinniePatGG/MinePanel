package de.winniepat.managementWebsitePlugin.persistence;

import de.winniepat.managementWebsitePlugin.logs.PanelLogEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class LogRepository {

    private final Database database;

    public LogRepository(Database database) {
        this.database = database;
    }

    public void appendLog(String kind, String source, String message) {
        String sql = "INSERT INTO panel_logs(kind, source, message, created_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, kind);
            statement.setString(2, source);
            statement.setString(3, message);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not append panel log", exception);
        }
    }

    public List<PanelLogEntry> recentLogs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        String sql = "SELECT id, kind, source, message, created_at FROM panel_logs ORDER BY id DESC LIMIT ?";
        List<PanelLogEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new PanelLogEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("kind"),
                            resultSet.getString("source"),
                            resultSet.getString("message"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not query panel logs", exception);
        }
        return entries;
    }

    public long latestLogId() {
        String sql = "SELECT COALESCE(MAX(id), 0) AS latest_id FROM panel_logs";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.getLong("latest_id");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not get latest panel log id", exception);
        }
    }

    public void clearLogs() {
        String sql = "DELETE FROM panel_logs";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not clear panel logs", exception);
        }
    }
}

