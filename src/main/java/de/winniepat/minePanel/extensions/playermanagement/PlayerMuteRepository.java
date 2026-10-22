package de.winniepat.minePanel.extensions.playermanagement;

import de.winniepat.minePanel.persistence.Database;

import java.sql.*;
import java.util.*;

public final class PlayerMuteRepository {

    private final Database database;

    public PlayerMuteRepository(Database database) {
        this.database = database;
    }

    public void initializeSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS player_mutes ("
                + "uuid TEXT PRIMARY KEY,"
                + "username TEXT NOT NULL,"
                + "reason TEXT NOT NULL,"
                + "muted_by TEXT NOT NULL,"
                + "muted_at INTEGER NOT NULL,"
                + "expires_at INTEGER"
                + ")";

        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize mute schema", exception);
        }
    }

    public void upsertMute(UUID uuid, String username, String reason, String mutedBy, long mutedAt, Long expiresAt) {
        String sql = "INSERT INTO player_mutes(uuid, username, reason, muted_by, muted_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET "
                + "username = excluded.username, "
                + "reason = excluded.reason, "
                + "muted_by = excluded.muted_by, "
                + "muted_at = excluded.muted_at, "
                + "expires_at = excluded.expires_at";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, username == null ? "" : username);
            statement.setString(3, reason == null ? "" : reason);
            statement.setString(4, mutedBy == null ? "" : mutedBy);
            statement.setLong(5, mutedAt);
            if (expiresAt == null || expiresAt <= 0) {
                statement.setNull(6, Types.BIGINT);
            } else {
                statement.setLong(6, expiresAt);
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save mute", exception);
        }
    }

    public Optional<PlayerMute> findByUuid(UUID uuid) {
        String sql = "SELECT uuid, username, reason, muted_by, muted_at, expires_at FROM player_mutes WHERE uuid = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(read(resultSet));
                }
            }
            return Optional.empty();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read mute", exception);
        }
    }

    public boolean removeMute(UUID uuid) {
        String sql = "DELETE FROM player_mutes WHERE uuid = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete mute", exception);
        }
    }

    public int clearExpired(long nowMillis) {
        String sql = "DELETE FROM player_mutes WHERE expires_at IS NOT NULL AND expires_at > 0 AND expires_at <= ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nowMillis);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not clear expired mutes", exception);
        }
    }

    private PlayerMute read(ResultSet resultSet) throws SQLException {
        long rawExpires = resultSet.getLong("expires_at");
        Long expiresAt = resultSet.wasNull() ? null : rawExpires;
        return new PlayerMute(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("username"),
                resultSet.getString("reason"),
                resultSet.getString("muted_by"),
                resultSet.getLong("muted_at"),
                expiresAt
        );
    }
}

