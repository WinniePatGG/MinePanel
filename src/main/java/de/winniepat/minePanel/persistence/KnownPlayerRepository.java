package de.winniepat.minePanel.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class KnownPlayerRepository {

    private final Database database;

    public KnownPlayerRepository(Database database) {
        this.database = database;
    }

    public void upsert(UUID uuid, String username) {
        upsert(uuid, username, Instant.now().toEpochMilli());
    }

    public void upsert(UUID uuid, String username, long lastSeenAt) {
        if (uuid == null || username == null || username.isBlank()) {
            return;
        }

        String sql = "INSERT INTO known_players(uuid, username, last_seen_at) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, last_seen_at = excluded.last_seen_at";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, username);
            statement.setLong(3, lastSeenAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not upsert known player", exception);
        }
    }

    public List<KnownPlayer> findAll() {
        String sql = "SELECT uuid, username, last_seen_at FROM known_players";
        List<KnownPlayer> players = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                players.add(new KnownPlayer(
                        UUID.fromString(resultSet.getString("uuid")),
                        resultSet.getString("username"),
                        resultSet.getLong("last_seen_at")
                ));
            }
            return players;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list known players", exception);
        }
    }

    public Optional<KnownPlayer> findByUuid(UUID uuid) {
        String sql = "SELECT uuid, username, last_seen_at FROM known_players WHERE uuid = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new KnownPlayer(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("username"),
                            resultSet.getLong("last_seen_at")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find known player", exception);
        }
    }

    public Optional<KnownPlayer> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        String sql = "SELECT uuid, username, last_seen_at FROM known_players WHERE LOWER(username) = LOWER(?) LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new KnownPlayer(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("username"),
                            resultSet.getLong("last_seen_at")
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find known player by username", exception);
        }
    }
}

