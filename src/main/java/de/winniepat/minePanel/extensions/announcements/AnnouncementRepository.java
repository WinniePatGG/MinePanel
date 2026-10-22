package de.winniepat.minePanel.extensions.announcements;

import de.winniepat.minePanel.persistence.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class AnnouncementRepository {

    private final Database database;

    public AnnouncementRepository(Database database) {
        this.database = database;
    }

    public void initializeSchema() {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS ext_announcements ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "message TEXT NOT NULL,"
                    + "enabled INTEGER NOT NULL DEFAULT 1,"
                    + "sort_order INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS ext_announcements_config ("
                    + "id INTEGER PRIMARY KEY,"
                    + "enabled INTEGER NOT NULL DEFAULT 0,"
                    + "interval_seconds INTEGER NOT NULL DEFAULT 300,"
                    + "updated_at INTEGER NOT NULL DEFAULT 0"
                    + ")");

            statement.execute("INSERT OR IGNORE INTO ext_announcements_config(id, enabled, interval_seconds, updated_at) VALUES (1, 0, 300, 0)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize announcements schema", exception);
        }
    }

    public AnnouncementConfig readConfig() {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT enabled, interval_seconds, updated_at FROM ext_announcements_config WHERE id = 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return new AnnouncementConfig(false, 300, 0L);
            }

            return new AnnouncementConfig(
                    resultSet.getInt("enabled") == 1,
                    Math.max(10, resultSet.getInt("interval_seconds")),
                    resultSet.getLong("updated_at")
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read announcements config", exception);
        }
    }

    public void saveConfig(boolean enabled, int intervalSeconds, long updatedAt) {
        int normalizedInterval = Math.max(10, Math.min(86_400, intervalSeconds));
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE ext_announcements_config SET enabled = ?, interval_seconds = ?, updated_at = ? WHERE id = 1"
             )) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setInt(2, normalizedInterval);
            statement.setLong(3, Math.max(0L, updatedAt));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save announcements config", exception);
        }
    }

    public List<Announcement> listMessages() {
        List<Announcement> messages = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, message, enabled, sort_order, created_at, updated_at FROM ext_announcements ORDER BY sort_order ASC, id ASC"
             );
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                messages.add(new Announcement(
                        resultSet.getLong("id"),
                        resultSet.getString("message"),
                        resultSet.getInt("enabled") == 1,
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("created_at"),
                        resultSet.getLong("updated_at")
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list announcements", exception);
        }
        return messages;
    }

    public long createMessage(String message, long now) {
        int nextSortOrder = nextSortOrder();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO ext_announcements(message, enabled, sort_order, created_at, updated_at) VALUES (?, 1, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS
             )) {
            statement.setString(1, message);
            statement.setInt(2, nextSortOrder);
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.executeUpdate();

            try (ResultSet resultSet = statement.getGeneratedKeys()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
            return -1L;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create announcement", exception);
        }
    }

    public boolean deleteMessage(long id) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM ext_announcements WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete announcement", exception);
        }
    }

    public boolean setMessageEnabled(long id, boolean enabled, long updatedAt) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE ext_announcements SET enabled = ?, updated_at = ? WHERE id = ?")) {
            statement.setInt(1, enabled ? 1 : 0);
            statement.setLong(2, updatedAt);
            statement.setLong(3, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update announcement", exception);
        }
    }

    private int nextSortOrder() {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(MAX(sort_order), 0) + 1 AS next_order FROM ext_announcements");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getInt("next_order");
            }
            return 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not compute announcement sort order", exception);
        }
    }

    public record AnnouncementConfig(boolean enabled, int intervalSeconds, long updatedAt) {
    }
}

