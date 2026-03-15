package de.winniepat.minePanel.persistence;

import java.sql.*;
import java.util.*;

public final class JoinLeaveEventRepository {

    private final Database database;

    public JoinLeaveEventRepository(Database database) {
        this.database = database;
    }

    public void appendJoinEvent(UUID uuid, String username, long createdAt) {
        appendEvent("JOIN", uuid, username, createdAt);
    }

    public void appendLeaveEvent(UUID uuid, String username, long createdAt) {
        appendEvent("LEAVE", uuid, username, createdAt);
    }

    public List<JoinLeaveEvent> listEventsSince(long sinceMillis) {
        String sql = "SELECT event_type, player_uuid, player_name, created_at "
                + "FROM join_leave_events WHERE created_at >= ? ORDER BY created_at ASC";
        List<JoinLeaveEvent> events = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Math.max(0L, sinceMillis));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new JoinLeaveEvent(
                            resultSet.getString("event_type"),
                            UUID.fromString(resultSet.getString("player_uuid")),
                            resultSet.getString("player_name"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
            return events;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list join/leave events", exception);
        }
    }

    private void appendEvent(String eventType, UUID uuid, String username, long createdAt) {
        if (uuid == null || username == null || username.isBlank()) {
            return;
        }

        String sql = "INSERT INTO join_leave_events(event_type, player_uuid, player_name, created_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            statement.setString(2, uuid.toString());
            statement.setString(3, username);
            statement.setLong(4, createdAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not append join/leave event", exception);
        }
    }

    public record JoinLeaveEvent(String eventType, UUID playerUuid, String playerName, long createdAt) {
    }
}

