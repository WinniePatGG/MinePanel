package de.winniepat.minePanel.persistence;

import java.sql.*;
import java.util.*;

public final class PlayerActivityRepository {

    private final Database database;

    public PlayerActivityRepository(Database database) {
        this.database = database;
    }

    public void ensureFromOffline(UUID uuid, long firstJoined, long lastSeen) {
        if (uuid == null) {
            return;
        }

        long safeFirstJoined = Math.max(0L, firstJoined);
        long safeLastSeen = Math.max(0L, lastSeen);
        String sql = "INSERT INTO player_activity(uuid, first_joined, last_seen, total_playtime_seconds, total_sessions, current_session_start, last_ip, last_country) "
                + "VALUES (?, ?, ?, 0, 0, 0, '', '') "
                + "ON CONFLICT(uuid) DO UPDATE SET "
                + "first_joined = CASE WHEN player_activity.first_joined = 0 THEN excluded.first_joined ELSE player_activity.first_joined END, "
                + "last_seen = MAX(player_activity.last_seen, excluded.last_seen)";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, safeFirstJoined);
            statement.setLong(3, safeLastSeen);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not seed player activity", exception);
        }
    }

    public void onJoin(UUID uuid, long joinedAt, String ipAddress) {
        if (uuid == null) {
            return;
        }

        String safeIp = ipAddress == null ? "" : ipAddress;
        String sql = "INSERT INTO player_activity(uuid, first_joined, last_seen, total_playtime_seconds, total_sessions, current_session_start, last_ip, last_country) "
                + "VALUES (?, ?, ?, 0, 1, ?, ?, '') "
                + "ON CONFLICT(uuid) DO UPDATE SET "
                + "first_joined = CASE WHEN player_activity.first_joined = 0 THEN excluded.first_joined ELSE player_activity.first_joined END, "
                + "last_seen = excluded.last_seen, "
                + "total_sessions = player_activity.total_sessions + 1, "
                + "current_session_start = CASE WHEN player_activity.current_session_start > 0 THEN player_activity.current_session_start ELSE excluded.current_session_start END, "
                + "last_ip = excluded.last_ip";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, joinedAt);
            statement.setLong(3, joinedAt);
            statement.setLong(4, joinedAt);
            statement.setString(5, safeIp);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update player activity on join", exception);
        }
    }

    public void onQuit(UUID uuid, long quitAt) {
        if (uuid == null) {
            return;
        }

        String sql = "UPDATE player_activity SET "
                + "last_seen = ?, "
                + "total_playtime_seconds = total_playtime_seconds + CASE "
                + "WHEN current_session_start > 0 AND ? > current_session_start THEN (? - current_session_start) / 1000 ELSE 0 END, "
                + "current_session_start = 0 "
                + "WHERE uuid = ?";

        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quitAt);
            statement.setLong(2, quitAt);
            statement.setLong(3, quitAt);
            statement.setString(4, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update player activity on quit", exception);
        }
    }

    public void updateCountry(UUID uuid, String country) {
        if (uuid == null) {
            return;
        }

        String sql = "UPDATE player_activity SET last_country = ? WHERE uuid = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, country == null ? "" : country);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update player country", exception);
        }
    }

    public Optional<PlayerActivity> findByUuid(UUID uuid) {
        String sql = "SELECT uuid, first_joined, last_seen, total_playtime_seconds, total_sessions, current_session_start, last_ip, last_country "
                + "FROM player_activity WHERE uuid = ?";
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
            throw new IllegalStateException("Could not read player activity", exception);
        }
    }

    public Map<UUID, PlayerActivity> findAllByUuid() {
        String sql = "SELECT uuid, first_joined, last_seen, total_playtime_seconds, total_sessions, current_session_start, last_ip, last_country FROM player_activity";
        Map<UUID, PlayerActivity> players = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                PlayerActivity entry = read(resultSet);
                players.put(entry.uuid(), entry);
            }
            return players;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list player activity", exception);
        }
    }

    private PlayerActivity read(ResultSet resultSet) throws SQLException {
        return new PlayerActivity(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getLong("first_joined"),
                resultSet.getLong("last_seen"),
                resultSet.getLong("total_playtime_seconds"),
                resultSet.getLong("total_sessions"),
                resultSet.getLong("current_session_start"),
                resultSet.getString("last_ip"),
                resultSet.getString("last_country")
        );
    }
}

